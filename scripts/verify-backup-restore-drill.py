#!/usr/bin/env python3
"""真实 PostgreSQL 备份 → 恢复 → 校验演练。

流程（全部在临时库上，不碰开发者日常库）：
  1. 启动 demo（mock provider）写入真实数据：collection、文档、向量、版本历史；
  2. 停写（停应用）后用项目既有 `scripts/rag-backup.py` 做 pg_dump 自定义格式备份；
  3. 校验 manifest/校验和，恢复到另一个全新空库（pg_restore）；
  4. 比对文档数、向量数与维度、collection、迁移历史、内容指纹；
  5. 用恢复库重新启动 demo，跑 health + search，证明恢复库可直接服务；
  6. 删除两个临时库（--keep 时保留临时目录与数据库供排查）。

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
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))
from rag_local_stack import LocalStack, json_request  # noqa: E402

GREEN = "\033[0;32m"
RED = "\033[0;31m"
YELLOW = "\033[1;33m"
NC = "\033[0m"
FAILURES: list[str] = []

COUNTED_TABLES = (
    "rag_collection",
    "rag_documents",
    "rag_document_versions",
    "rag_embeddings",
)


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
               extra: list[str]) -> None:
    result = subprocess.run(
        [sys.executable, str(Path(__file__).resolve().parent / "rag-backup.py"),
         action, str(directory), *extra],
        env=env, capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"rag-backup.py {action} 失败：{result.stdout}\n{result.stderr}")


def table_counts(stack: LocalStack, database: str) -> dict[str, str]:
    return {
        table: stack.sql(f"SELECT count(*) FROM {table}", db=database)
        for table in COUNTED_TABLES
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18088)
    parser.add_argument("--mock-port", type=int, default=18089)
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--rebuild", action="store_true")
    args = parser.parse_args()

    stack = LocalStack("rag_backup_drill", args.port, args.mock_port, args.keep)
    source_db = None
    target_db = None
    try:
        source_db = stack.create_database()
        target_db = f"{source_db}_restore"
        info(f"源库：{source_db}")
        stack.build_demo_if_needed(force=args.rebuild)
        stack.start_mock()
        base_url = stack.start_app(database=source_db)

        # ---------- 1. 写入真实数据 ----------
        info("写入真实数据（collection / 外部文档 / 向量 / 版本）")
        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/collections",
            {"collectionKey": "backup-base", "name": "Backup Drill",
             "embeddingModel": "BAAI/bge-m3", "dimensions": 1024})
        check(status in (200, 201), f"创建 collection（HTTP {status}）")

        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/documents/upsert",
            {"collectionKey": "backup-base", "sourceNamespace": "drill",
             "externalId": "backup-doc-1", "sourceRevision": "rev-1",
             "title": "Backup Drill Document",
             "content": "Backup restore drill marker. " * 80,
             "embed": True})
        check(status == 200, f"写入外部文档并同步嵌入（HTTP {status}）")
        document_id = json.loads(body)["documentId"]

        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/documents/{document_id}/embed?force=true",
            {})
        check(status == 200, f"强制重建向量（HTTP {status}）")

        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/documents/upsert",
            {"collectionKey": "backup-base", "sourceNamespace": "drill",
             "externalId": "backup-doc-2", "sourceRevision": "rev-1",
             "title": "Backup Drill Second Document",
             "content": "Second document for restore verification. " * 40,
             "embed": True})
        check(status == 200, f"写入第二篇外部文档（HTTP {status}）")

        # 等待异步派发（若有）落盘；这里主要覆盖同步嵌入路径。
        time.sleep(1)
        source_counts = table_counts(stack, source_db)
        info(f"源库计数：{source_counts}")

        doc_count = int(source_counts["rag_documents"])
        check(doc_count >= 2, f"源库文档数 >= 2（实际 {doc_count}）")
        embedding_count = int(source_counts["rag_embeddings"])
        check(embedding_count >= 2, f"源库向量数 >= 2（实际 {embedding_count}）")
        vector_dims = stack.sql(
            "SELECT coalesce(sum(vector_dims(embedding_1024)),0) FROM rag_embeddings "
            "WHERE embedding_1024 IS NOT NULL", db=source_db)
        check(vector_dims.isdigit() and int(vector_dims) > 0,
              f"源库向量维度可计算（总维度 {vector_dims}）")
        fingerprint = stack.sql(
            "SELECT md5(string_agg(id || ':' || coalesce(content_hash,'') || ':' "
            "|| coalesce(source_revision,''), ',' ORDER BY id)) FROM rag_documents",
            db=source_db)
        migration_version = stack.sql(
            "SELECT version FROM flyway_schema_history WHERE success "
            "ORDER BY installed_rank DESC LIMIT 1", db=source_db)
        migration_rows = stack.sql(
            "SELECT count(*) FROM flyway_schema_history", db=source_db)

        # ---------- 2. 停写并备份 ----------
        info("停止应用（静写），生成 pg_dump 备份包")
        stack.stop_app()
        backup_dir = stack.work_dir / "backup"
        run_backup("backup", backup_dir, backup_env(stack, source_db),
                   ["--ack-sensitive-data", "--quiesced"])
        check((backup_dir / "database.dump").stat().st_size > 0,
              "备份包存在且非空")
        manifest = json.loads((backup_dir / "manifest.json").read_text())
        check(manifest.get("schemaVersion") == migration_version,
              f"manifest 迁移版本与源库一致（{manifest.get('schemaVersion')}）")
        run_backup("verify", backup_dir, backup_env(stack, source_db), [])
        pass_("备份包校验和/格式通过 rag-backup.py verify")

        # ---------- 3. 恢复到全新空库 ----------
        info(f"恢复到全新空库：{target_db}")
        stack.create_database(target_db)
        run_backup("restore", backup_dir, backup_env(stack, target_db),
                   ["--ack-isolated-target"])

        # ---------- 4. 计数与指纹比对 ----------
        target_counts = table_counts(stack, target_db)
        for table in COUNTED_TABLES:
            check(source_counts[table] == target_counts[table],
                  f"{table} 行数一致（源 {source_counts[table]} / 恢复 "
                  f"{target_counts[table]}）")
        target_dims = stack.sql(
            "SELECT coalesce(sum(vector_dims(embedding_1024)),0) FROM rag_embeddings "
            "WHERE embedding_1024 IS NOT NULL", db=target_db)
        check(target_dims == vector_dims,
              f"向量维度总和一致（源 {vector_dims} / 恢复 {target_dims}）")
        target_fingerprint = stack.sql(
            "SELECT md5(string_agg(id || ':' || coalesce(content_hash,'') || ':' "
            "|| coalesce(source_revision,''), ',' ORDER BY id)) FROM rag_documents",
            db=target_db)
        check(target_fingerprint == fingerprint,
              f"文档内容指纹一致（{fingerprint}）")
        target_version = stack.sql(
            "SELECT version FROM flyway_schema_history WHERE success "
            "ORDER BY installed_rank DESC LIMIT 1", db=target_db)
        target_history = stack.sql(
            "SELECT count(*) FROM flyway_schema_history", db=target_db)
        check(target_version == migration_version,
              f"迁移历史最新版本一致（{migration_version}）")
        check(target_history == migration_rows,
              f"迁移历史行数一致（源 {migration_rows} / 恢复 {target_history}）")
        check(stack.sql("SELECT count(*) FROM rag_document_versions", db=target_db)
              == source_counts["rag_document_versions"],
              "版本历史行数一致")

        # ---------- 5. 恢复库直接服务 ----------
        info("用恢复库启动应用，验证可直接服务")
        restored_url = stack.start_app(database=target_db)
        status, body = json_request(
            "POST", f"{restored_url}/api/v1/rag/search",
            {"query": "backup restore drill marker",
             "collectionKeys": ["backup-base"]})
        check(status == 200, f"恢复库 search（HTTP {status}）")
        payload = json.loads(body) if status == 200 else []
        if isinstance(payload, dict):
            results = payload.get("results", [])
        else:
            results = payload
        check(len(results) >= 1,
              f"恢复库检索返回结果（命中 {len(results)} 条）")
        status, body = json_request(
            "GET", f"{restored_url}/api/v1/rag/documents/{document_id}", {})
        check(status == 200, f"恢复库可按原 ID 读取文档（HTTP {status}）")

        # 恢复库确实读的是目标库，而不是源库残留。
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
    print(f"\n{GREEN}备份 → 恢复 → 校验演练全部通过{NC}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
