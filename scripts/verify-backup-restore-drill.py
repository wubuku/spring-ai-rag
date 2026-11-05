#!/usr/bin/env python3
"""真实 PostgreSQL 备份 → 恢复 → 完整性校验演练。

流程（全部在临时库上，不碰开发者日常库）：
  1. 启动 demo（mock provider）写入真实数据：collection、文档、chunk、向量、
     collection、评估 suite/run、反馈、文件元数据、同步历史、失败 job、
     处理阶段与幂等凭证；
  2. 写入进行中用原生 pg_dump 做一次逻辑快照并恢复到第三个库，验证
     PostgreSQL MVCC 快照不会出现"文档在主表、派生行缺失"的半写状态；
  3. 停写后用项目既有 `scripts/rag-backup.py` 做官方备份；
  4. 恢复到另一个全新空库，逐表比对行数与内容摘要，校验 Flyway
     installed_rank 连续、HNSW 索引有效且检索走索引；
  5. 用恢复库启动 demo，跑 health / search / chat，证明恢复库可直接服务；
  6. 删除全部临时库（--keep 时保留临时目录供排查）。

结论语义（与 docs/backup-restore-zh-CN.md 一致）：
  - `rag-backup.py` 是 PostgreSQL **逻辑**备份（pg_dump custom archive），
    不是物理卷快照、增量备份或 PITR；
  - pc_dump 本身是单事务一致性快照；工具要求显式 `--quiesced`，即操作者
    确认已停止业务写入与后台 worker；不提供"在线备份"的受支持入口；
  - 写入进行中的原生 pg_dump 仍是一致快照，但应用级任务/租约可能停在
    中间状态，恢复后应由既有 lease/reconciliation 接管。本脚本用第 2 步
    验证数据库层不出现半写行，而不是承诺 RPO=0。

用法：
  ./scripts/verify-backup-restore-drill.py
  ./scripts/verify-backup-restore-drill.py --keep --rebuild
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))
from rag_local_stack import LocalStack, json_request  # noqa: E402

GREEN = "\033[0;32m"
RED = "\033[0;31m"
YELLOW = "\033[1;33m"
NC = "\033[0m"
FAILURES: list[str] = []

HASH64 = "a" * 64
CHUNKER = "bounded-v3:1000:100:100:1000000:20000"


def pass_(message: str) -> None:
    print(f"{GREEN}[PASS]{NC} {message}")


def info(message: str) -> None:
    print(f"{YELLOW}[INFO]{NC} {message}")


def check(condition: bool, message: str) -> None:
    if condition:
        pass_(message)
    else:
        FAILURES.append(message)
        print(f"{RED}[FAIL]{NC} {message}")


def restore_archive(database: str, archive: Path, env: dict[str, str]) -> subprocess.CompletedProcess:
    """兼容 pg_dump 17 / PostgreSQL 16 的归档恢复。

    pg_restore 17 会无条件写入 `SET transaction_timeout = 0`，PG16 及以下
    不认识该参数。先转成纯 SQL、过滤这一行，再用 psql 单事务应用，保持
    与 rag-backup.py 相同的兼容策略。
    """
    plain = archive.with_suffix(".sql")
    generated = subprocess.run(
        ["pg_restore", "--no-owner", "--no-privileges", "-f", str(plain),
         str(archive)], env=env, capture_output=True, text=True)
    if generated.returncode != 0:
        return generated
    filtered = archive.with_suffix(".filtered.sql")
    with plain.open("r", encoding="utf-8") as source, \
            filtered.open("w", encoding="utf-8") as target:
        for line in source:
            if line.strip() == "SET transaction_timeout = 0;":
                continue
            target.write(line)
    return subprocess.run(
        ["psql", "-v", "ON_ERROR_STOP=1", "--single-transaction",
         "-f", str(filtered)], env=env, capture_output=True, text=True)


def backup_env(stack: LocalStack, database: str) -> dict[str, str]:
    env = dict(os.environ)
    env.update({
        "PGHOST": stack.pg_host,
        "PGPORT": stack.pg_port,
        "PGDATABASE": database,
        "PGUSER": stack.pg_user,
    })
    if stack.pg_password:
        env["PGPASSWORD"] = stack.pg_password
    return env


def run_backup(action: str, directory: Path, env: dict[str, str],
               extra: list[str]) -> tuple[int, str]:
    result = subprocess.run(
        [sys.executable, str(Path(__file__).resolve().parent / "rag-backup.py"),
         action, str(directory), *extra],
        env=env, capture_output=True, text=True,
    )
    return result.returncode, f"{result.stdout}\n{result.stderr}"


def backup_or_fail(action: str, directory: Path, env: dict[str, str],
                   extra: list[str]) -> None:
    code, output = run_backup(action, directory, env, extra)
    if code != 0:
        raise RuntimeError(f"rag-backup.py {action} 失败：{output}")


def public_tables(stack: LocalStack, database: str) -> list[str]:
    rows = stack.sql_rows(
        "SELECT tablename FROM pg_tables WHERE schemaname='public' "
        "ORDER BY tablename", db=database)
    return [row[0] for row in rows]


def table_digest(stack: LocalStack, database: str, table: str) -> str:
    if table == "rag_embeddings":
        # 向量列文本很长，摘要只取 id/索引/向量哈希/文本，避免把 3000 行
        # 1024 维向量整体拼成几十 MB 字符串。
        return stack.sql(
            "SELECT coalesce(md5(string_agg("
            "id || ':' || chunk_index || ':' || "
            "coalesce(md5(embedding_1024::text), '') || ':' || "
            "coalesce(md5(chunk_text), ''), ',' ORDER BY id)), 'empty') "
            "FROM rag_embeddings", db=database)
    return stack.sql(
        "SELECT coalesce(md5(string_agg(row_to_json(t)::text, E'\\n' "
        "ORDER BY row_to_json(t)::text)), 'empty') FROM "
        f"\"{table}\" t", db=database)


def table_snapshot(stack: LocalStack, database: str) -> dict[str, dict[str, str]]:
    snapshot: dict[str, dict[str, str]] = {}
    for table in public_tables(stack, database):
        snapshot[table] = {
            "count": stack.sql(
                f'SELECT count(*) FROM "{table}"', db=database),
            "digest": table_digest(stack, database, table),
        }
    return snapshot


def flyway_contiguity(stack: LocalStack, database: str) -> tuple[int, int, bool]:
    rows = stack.sql_rows(
        "SELECT count(*), min(installed_rank), max(installed_rank) "
        "FROM flyway_schema_history", db=database)
    count, minimum, maximum = (int(value) for value in rows[0])
    return count, minimum, maximum == count and minimum == 1


def hnsw_status(stack: LocalStack, database: str) -> list[str]:
    return [row[0] for row in stack.sql_rows(
        "SELECT indexname FROM pg_indexes WHERE schemaname='public' "
        "AND tablename='rag_embeddings' AND indexdef ILIKE '%USING hnsw%' "
        "ORDER BY indexname", db=database)]


def seed_drill_rows(stack: LocalStack, database: str, collection_id: str,
                    document_id: str, profile_id: str) -> dict[str, str]:
    """写入 SQL 中才能构造的评估/文件/任务/同步历史行。"""
    suite_id = str(uuid.uuid4())
    version_id = str(uuid.uuid4())
    run_id = str(uuid.uuid4())
    processing_run_id = str(uuid.uuid4())
    sync_run_id = str(uuid.uuid4())
    job_id = str(uuid.uuid4())
    import_id = str(uuid.uuid4())
    file_uuid = str(uuid.uuid4())
    file_path = f"{file_uuid}/original/sample.pdf"
    content_hash = stack.sql(
        f"SELECT content_hash FROM rag_documents WHERE id={document_id}",
        db=database)

    statements = [
        # 评估 suite → version → run → case result（依赖外键级联）。
        f"INSERT INTO rag_evaluation_suites (id, suite_key, name, owner_principal_id) "
        f"VALUES ('{suite_id}', 'backup-drill-suite', 'Backup Drill Suite', 'root')",
        f"INSERT INTO rag_evaluation_suite_versions "
        f"(id, suite_id, version, definition, definition_sha256) "
        f"VALUES ('{version_id}', '{suite_id}', 1, "
        f"'{{\"cases\":[],\"variants\":[]}}'::jsonb, '{HASH64}')",
        f"INSERT INTO rag_evaluation_runs "
        f"(id, suite_version_id, owner_principal_id, status, "
        f"configuration_snapshot, aggregate_metrics) "
        f"VALUES ('{run_id}', '{version_id}', 'root', 'PASSED', "
        f"'{{}}'::jsonb, '{{\"passRate\":1.0}}'::jsonb)",
        f"INSERT INTO rag_evaluation_case_results "
        f"(run_id, variant_key, case_id, status, metrics) "
        f"VALUES ('{run_id}', 'default', 'case-1', 'PASSED', "
        f"'{{\"score\":1.0}}'::jsonb)",
        # 上传文件元数据 + import batch（entry_path 外键）。
        f"INSERT INTO fs_files "
        f"(path, is_text, content_bin, content_txt, mime_type, file_size, "
        f"owner_principal_id, collection_id) "
        f"VALUES ('{file_path}', FALSE, decode('25504446', 'hex'), NULL, "
        f"'application/pdf', 4, 'root', {collection_id})",
        f"INSERT INTO fs_import_batches "
        f"(import_id, source_type, original_filename, display_name, "
        f"entry_path, original_path, file_count, owner_principal_id, "
        f"collection_id) VALUES ('{import_id}', 'PDF', 'sample.pdf', "
        f"'sample', '{file_path}', '{file_path}', 1, 'root', {collection_id})",
        # 同步历史（run + item）。
        f"INSERT INTO rag_document_sync_runs "
        f"(id, collection_id, source_namespace, client_run_id, "
        f"lease_token_hash, sync_generation, snapshot_start_sequence, "
        f"snapshot_mode, missing_policy, status, lease_expires_at, "
        f"applied_count) VALUES ('{sync_run_id}', {collection_id}, "
        f"'backup-drill', 'client-run-1', '{HASH64}', 1, 1, 'ONLINE_CUT', "
        f"'NONE', 'COMPLETED', CURRENT_TIMESTAMP - INTERVAL '1 hour', 1)",
        f"INSERT INTO rag_document_sync_run_items "
        f"(run_id, external_id, document_kind, item_fingerprint, "
        f"source_revision, document_id, status) "
        f"VALUES ('{sync_run_id}', 'backup-doc-1', 'TEXT', '{HASH64}', "
        f"'rev-1', {document_id}, 'APPLIED')",
        # 失败 job：删除文档时必须随文档一起消失，不能留残留。
        f"INSERT INTO rag_embedding_jobs "
        f"(id, batch_id, document_id, embedding_profile_id, request_generation, "
        f"content_hash, document_version, document_kind, chunker_version, "
        f"status, attempt_count, max_attempts, last_error) "
        f"VALUES ('{job_id}', '{uuid.uuid4()}', {document_id}, {profile_id}, 1, "
        f"'{content_hash}', 1, 'TEXT', '{CHUNKER}', 'FAILED', 3, 3, "
        f"'mock upstream failure')",
        # 处理 run + stage（删除文档时级联）。
        f"INSERT INTO rag_document_processing_runs "
        f"(id, document_id, embedding_profile_id, request_generation, "
        f"document_version, content_hash, document_kind, chunker_version, "
        f"attempt_no, max_attempts, status, origin, started_at, finished_at) "
        f"VALUES ('{processing_run_id}', {document_id}, {profile_id}, 1, 1, "
        f"'{content_hash}', 'TEXT', '{CHUNKER}', 2, 3, 'COMPLETED', 'API', "
        f"CURRENT_TIMESTAMP - INTERVAL '5 minutes', "
        f"CURRENT_TIMESTAMP - INTERVAL '4 minutes')",
        f"INSERT INTO rag_document_processing_stages "
        f"(run_id, stage_name, stage_order, status) "
        f"VALUES ('{processing_run_id}', 'PARSING', 1, 'SUCCEEDED')",
        f"INSERT INTO rag_audit_log "
        f"(operation, entity_type, entity_id, operator, description) "
        f"VALUES ('DRILL_AUDIT', 'DRILL', '{document_id}', 'root', "
        f"'backup drill audit row')",
    ]
    for statement in statements:
        stack.execute(statement, db=database)
    return {
        "suite_id": suite_id,
        "version_id": version_id,
        "run_id": run_id,
        "processing_run_id": processing_run_id,
        "sync_run_id": sync_run_id,
        "job_id": job_id,
        "import_id": import_id,
        "file_path": file_path,
    }


def bulk_vector_rows(stack: LocalStack, database: str, document_id: str,
                     profile_id: str, total: int = 3000) -> None:
    """让 HNSW 在真实数据量下被规划器选中（小表会走 Seq Scan）。"""
    stack.execute(
        "INSERT INTO rag_embeddings "
        "(document_id, chunk_text, chunk_index, embedding_profile_id, "
        "embedding_1024) SELECT "
        f"{document_id}, 'bulk vector ' || g, 1000 + g, {profile_id}, "
        "(SELECT ('[' || array_to_string("
        "array_fill(0.02::real, ARRAY[1024]), ',') || ']')::vector) "
        f"FROM generate_series(1, {total}) g", db=database)


def explain_vector_scan(stack: LocalStack, database: str,
                        profile_id: str) -> str:
    return stack.sql(
        "EXPLAIN (COSTS OFF) SELECT id FROM rag_embeddings "
        f"WHERE embedding_profile_id = {profile_id} "
        "AND embedding_1024 IS NOT NULL "
        "ORDER BY embedding_1024 <=> "
        "(array_fill(0.02::real, ARRAY[1024])::vector) LIMIT 10",
        db=database)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18088)
    parser.add_argument("--mock-port", type=int, default=18089)
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--rebuild", action="store_true")
    args = parser.parse_args()

    stack = LocalStack("rag_backup_drill", args.port, args.mock_port, args.keep)
    source_db = None
    online_db = None
    target_db = None
    try:
        source_db = stack.create_database()
        online_db = f"{source_db}_online"
        target_db = f"{source_db}_restore"
        info(f"源库：{source_db}")
        stack.build_demo_if_needed(force=args.rebuild)
        stack.start_mock()
        base_url = stack.start_app(database=source_db)

        # ---------- 1. 写入真实数据 ----------
        info("写入真实数据（collection / 文档 / 向量 / 评估 / 反馈 / 文件 / 任务）")
        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/collections",
            {"collectionKey": "backup-base", "name": "Backup Drill",
             "embeddingModel": "BAAI/bge-m3", "dimensions": 1024})
        check(status in (200, 201), f"创建 collection（HTTP {status}）")
        collection_id = stack.sql(
            "SELECT id FROM rag_collection WHERE collection_key='backup-base'",
            db=source_db)

        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/documents/upsert",
            {"collectionKey": "backup-base", "sourceNamespace": "drill",
             "externalId": "backup-doc-1", "sourceRevision": "rev-1",
             "title": "Backup Drill Document",
             "content": "Backup restore drill marker. " * 80,
             "embed": True})
        check(status == 200, f"写入外部文档并同步嵌入（HTTP {status}）")
        document_id = str(json.loads(body)["documentId"])

        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/documents/upsert",
            {"collectionKey": "backup-base", "sourceNamespace": "drill",
             "externalId": "backup-doc-2", "sourceRevision": "rev-1",
             "title": "Backup Drill Second Document",
             "content": "Second document for restore verification. " * 40,
             "embed": True})
        check(status == 200, f"写入第二篇外部文档（HTTP {status}）")

        # 本地创建 + Idempotency-Key，留下幂等凭证供恢复比对。
        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/documents",
            {"collectionKey": "backup-base", "title": "Backup Local Document",
             "content": "Local document with idempotency receipt. " * 30,
             "embeddingPolicy": "SYNC"},
            extra_headers={"Idempotency-Key": "backup-drill-idem-key"})
        check(status == 200, f"本地创建文档并写幂等凭证（HTTP {status}）")

        # 真实 chat 留下 history + source references；反馈留下 feedback 行。
        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/chat",
            {"message": "backup restore drill marker",
             "sessionId": "backup-session",
             "collectionKeys": ["backup-base"], "maxResults": 3})
        check(status == 200, f"chat 写入会话历史（HTTP {status}）")
        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/evaluation/feedback",
            {"sessionId": "backup-session", "query": "backup drill",
             "feedbackType": "THUMBS_UP", "rating": 5,
             "selectedDocumentIds": [int(document_id)]})
        check(status == 200, f"提交反馈（HTTP {status}）")

        profile_id = stack.sql(
            "SELECT id FROM rag_embedding_profiles ORDER BY id LIMIT 1",
            db=source_db)
        seeded = seed_drill_rows(
            stack, source_db, collection_id, document_id, profile_id)
        bulk_vector_rows(stack, source_db, document_id, profile_id)

        pre_online_docs = int(stack.sql(
            "SELECT count(*) FROM rag_documents", db=source_db))
        info(f"源库已写入：documents={pre_online_docs}")

        # ---------- 2. 写入进行中的原生逻辑快照 ----------
        info("写入进行中的原生 pg_dump 快照（验证数据库层无半写行）")
        stop_writes = threading.Event()
        writer_errors: list[str] = []

        def concurrent_writer() -> None:
            index = 0
            while not stop_writes.is_set() and index < 20:
                index += 1
                code, _ = json_request(
                    "POST", f"{base_url}/api/v1/rag/documents",
                    {"collectionKey": "backup-base",
                     "title": f"Concurrent online {index}",
                     "content": f"online write {index} " * 20,
                     "embeddingPolicy": "SYNC"})
                if code != 200:
                    writer_errors.append(f"HTTP {code}")
                time.sleep(0.02)

        writer = threading.Thread(target=concurrent_writer)
        writer.start()
        online_dir = stack.work_dir / "online-backup"
        online_dir.mkdir()
        online_dump = online_dir / "database.dump"
        dump_env = backup_env(stack, source_db)
        dump = subprocess.run(
            ["pg_dump", "-Fc", "-f", str(online_dump)], env=dump_env,
            capture_output=True, text=True)
        stop_writes.set()
        writer.join(timeout=30)
        check(dump.returncode == 0, "写入进行中的原生 pg_dump 成功")
        check(not writer_errors,
              f"并发写入请求全部成功（错误 {writer_errors[:3]}）")

        stack.create_database(online_db)
        restore_env = backup_env(stack, online_db)
        restored_online = restore_archive(online_db, online_dump, restore_env)
        check(restored_online.returncode == 0,
              "写入进行中的快照可原子恢复（单事务 SQL 兼容路径）"
              + ("" if restored_online.returncode == 0 else
                 f"：{restored_online.stderr.strip()[:300]}"))
        online_docs = 0
        post_online_docs = int(stack.sql(
            "SELECT count(*) FROM rag_documents", db=source_db))
        if restored_online.returncode == 0:
            online_docs = int(stack.sql(
                "SELECT count(*) FROM rag_documents", db=online_db))
            check(pre_online_docs <= online_docs <= post_online_docs,
                  f"快照文档数落在写入区间内（{pre_online_docs}.."
                  f"{post_online_docs} / 快照 {online_docs}）")
            half_written = int(stack.sql(
                "SELECT count(*) FROM rag_documents d "
                "LEFT JOIN rag_document_chunks c ON c.document_id = d.id "
                "WHERE d.processing_status = 'COMPLETED' AND c.id IS NULL",
                db=online_db))
            check(half_written == 0,
                  f"快照中不存在有文档无 chunk 的半写状态（{half_written}）")
            online_count, online_min, online_contiguous = flyway_contiguity(
                stack, online_db)
            check(online_contiguous and online_min == 1,
                  f"快照 Flyway installed_rank 连续（{online_count} 条）")

        # ---------- 3. 停写 + 官方备份 ----------
        info("停止应用（静写），生成 rag-backup.py 备份包")
        stack.stop_app()
        source_snapshot = table_snapshot(stack, source_db)
        source_flyway = flyway_contiguity(stack, source_db)
        source_hnsw = hnsw_status(stack, source_db)
        source_plan = explain_vector_scan(stack, source_db, profile_id)
        check(source_hnsw, f"源库 HNSW 索引存在（{source_hnsw}）")
        check("Seq Scan" not in source_plan,
              "源库向量查询走索引而非 Seq Scan")
        info(f"源库表数：{len(source_snapshot)}，Flyway {source_flyway[0]} 条")

        # 不支持在线备份：工具有意要求操作者确认停写。
        code, output = run_backup(
            "backup", stack.work_dir / "no-quiesce",
            backup_env(stack, source_db), ["--ack-sensitive-data"])
        check(code != 0,
              "缺少 --quiesced 时备份工具拒绝执行（在线备份不是受支持入口）")

        backup_dir = stack.work_dir / "backup"
        backup_or_fail("backup", backup_dir, backup_env(stack, source_db),
                       ["--ack-sensitive-data", "--quiesced"])
        check((backup_dir / "database.dump").stat().st_size > 0,
              "备份包存在且非空")
        manifest = json.loads((backup_dir / "manifest.json").read_text())
        check(manifest.get("schemaVersion") == stack.sql(
            "SELECT version FROM flyway_schema_history WHERE success "
            "ORDER BY installed_rank DESC LIMIT 1", db=source_db),
            f"manifest 迁移版本与源库一致（{manifest.get('schemaVersion')}）")
        backup_or_fail("verify", backup_dir, backup_env(stack, source_db), [])
        pass_("备份包校验和/格式通过 rag-backup.py verify")

        # ---------- 4. 恢复到全新空库并逐表比对 ----------
        info(f"恢复到全新空库：{target_db}")
        stack.create_database(target_db)
        backup_or_fail("restore", backup_dir, backup_env(stack, target_db),
                       ["--ack-isolated-target"])

        target_snapshot = table_snapshot(stack, target_db)
        check(set(source_snapshot) == set(target_snapshot),
              "恢复库与源库表集合一致")
        for table in sorted(source_snapshot):
            if table not in target_snapshot:
                check(False, f"{table} 在恢复库中缺失")
                continue
            source_row = source_snapshot[table]
            target_row = target_snapshot[table]
            check(source_row["count"] == target_row["count"],
                  f"{table} 行数一致（源 {source_row['count']} / "
                  f"恢复 {target_row['count']}）")
            check(source_row["digest"] == target_row["digest"],
                  f"{table} 内容摘要一致")

        target_flyway = flyway_contiguity(stack, target_db)
        check(target_flyway[0] == source_flyway[0],
              f"Flyway 行数一致（源 {source_flyway[0]} / 恢复 {target_flyway[0]}）")
        check(target_flyway[1] == source_flyway[1],
              "Flyway installed_rank 连续且从 1 开始")
        target_hnsw = hnsw_status(stack, target_db)
        check(target_hnsw == source_hnsw,
              f"恢复库 HNSW 索引一致（{target_hnsw}）")
        index_valid = stack.sql(
            "SELECT count(*) FROM pg_index i JOIN pg_class c "
            "ON c.oid=i.indexrelid WHERE c.relname = "
            f"'{source_hnsw[0] if source_hnsw else ''}' "
            "AND i.indisvalid AND i.indisready", db=target_db)
        check(index_valid == "1", "恢复库 HNSW 索引 valid/ready")
        target_plan = explain_vector_scan(stack, target_db, profile_id)
        check("Seq Scan" not in target_plan,
              "恢复库向量查询走索引而非 Seq Scan")
        if "Seq Scan" in target_plan:
            info(f"恢复库 EXPLAIN：\n{target_plan}")

        # ---------- 5. 恢复库直接服务 ----------
        info("用恢复库启动应用，验证可直接服务")
        restored_url = stack.start_app(database=target_db)
        status, body = json_request(
            "GET", f"{restored_url}/api/v1/rag/search?query=backup"
            "&limit=5&collectionScopeMode=SELECTED_COLLECTIONS"
            "&collectionKeys=backup-base", {})
        check(status == 200, f"恢复库 search（HTTP {status}）")
        payload = json.loads(body) if status == 200 else []
        results = payload.get("results", []) if isinstance(payload, dict) else payload
        check(len(results) >= 1,
              f"恢复库检索返回结果（命中 {len(results)} 条）")
        status, body = json_request(
            "POST", f"{restored_url}/api/v1/rag/chat",
            {"message": "backup restore drill marker",
             "sessionId": "restore-session",
             "collectionKeys": ["backup-base"], "maxResults": 3})
        check(status == 200, f"恢复库 chat 提问链路（HTTP {status}）")
        if status == 200:
            reply = json.loads(body)
            check(bool(reply.get("answer") or reply.get("response")),
                  "恢复库 chat 返回非空答案")
        status, body = json_request(
            "GET", f"{restored_url}/api/v1/rag/documents/{document_id}", {})
        check(status == 200, f"恢复库可按原 ID 读取文档（HTTP {status}）")
        status, body = json_request(
            "GET", f"{restored_url}/api/v1/rag/evaluation/feedback/history"
            "?page=0&size=5", {})
        check(status == 200, f"恢复库可读反馈历史（HTTP {status}）")

        stack.stop_app()
        check(stack.sql(
            f"SELECT count(*) FROM rag_documents WHERE id = {document_id}",
            db=target_db) == "1", "恢复库中能查到该文档行")
    finally:
        stack.cleanup()

    if FAILURES:
        print(f"\n{RED}备份恢复演练失败 {len(FAILURES)} 项：{NC}")
        for item in FAILURES:
            print(f"  - {item}")
        return 1
    print(f"\n{GREEN}备份 → 恢复 → 逐表/索引/服务校验全部通过{NC}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
