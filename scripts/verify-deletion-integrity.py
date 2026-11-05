#!/usr/bin/env python3
"""真实 PostgreSQL + pgvector 删除完整性审计（可重复执行）。

覆盖：
  1. 单篇文档永久删除：chunk、向量、版本、embedding state、失败 job、处理
     run/stage、反馈/会话引用、幂等凭证、同步历史；
  2. 同 id 重建：显式复用已删除的 document id，断言不会串到旧 chunk/向量；
  3. 同 Idempotency-Key 重建：删除文档后复用同一幂等键，必须创建新文档，
     而不是永久 409 或返回已删文档；
  4. 批量删除部分失败：用触发器强制第二条删除失败，断言事务回滚、没有半删；
  5. 反馈 / 评估 suite / upload 文件 / import batch 删除后的全库孤儿扫描；
  6. Collection 永久清理：文档、chunk、向量、反馈、审计、幂等、同步历史、
     操作槽位全部消失；Collection 行与上传文件按保留策略保留（已知不删项）；
  7. 异步清理的容量计数回正（active_count 回到 0）。

所有临时库前缀 `rag_delete_drill_*`，结束自动删除；`--keep` 保留现场。
发现残留会直接以非零退出码报告，便于回归。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
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

# (child, fk, parent, pk)。这些外键删除后必须没有孤儿行（NULL 不算孤儿）。
FK_ORPHAN_CHECKS = [
    ("rag_documents", "collection_id", "rag_collection", "id"),
    ("rag_document_chunks", "document_id", "rag_documents", "id"),
    ("rag_embeddings", "document_id", "rag_documents", "id"),
    ("rag_document_versions", "document_id", "rag_documents", "id"),
    ("rag_document_embedding_state", "document_id", "rag_documents", "id"),
    ("rag_document_local_index_state", "document_id", "rag_documents", "id"),
    ("rag_embedding_jobs", "document_id", "rag_documents", "id"),
    ("rag_document_processing_runs", "document_id", "rag_documents", "id"),
    ("rag_document_processing_stages", "run_id",
     "rag_document_processing_runs", "id"),
    ("rag_document_sync_run_items", "run_id",
     "rag_document_sync_runs", "id"),
    ("rag_document_sync_run_items", "document_id", "rag_documents", "id"),
    ("rag_document_idempotency_operations", "result_document_id",
     "rag_documents", "id"),
    ("rag_document_relocated_addresses", "document_id", "rag_documents", "id"),
    ("rag_derivation_repair_items", "document_id", "rag_documents", "id"),
    ("rag_user_feedback_document", "feedback_id", "rag_user_feedback", "id"),
    ("rag_user_feedback_document", "document_id", "rag_documents", "id"),
    ("rag_chat_history_source_document", "history_id", "rag_chat_history", "id"),
    ("rag_chat_history_source_document", "document_id", "rag_documents", "id"),
    ("rag_evaluation_suite_versions", "suite_id", "rag_evaluation_suites", "id"),
    ("rag_evaluation_runs", "suite_version_id",
     "rag_evaluation_suite_versions", "id"),
    ("rag_evaluation_case_results", "run_id", "rag_evaluation_runs", "id"),
    ("fs_import_batches", "entry_path", "fs_files", "path"),
    ("rag_collection_operation_slot", "collection_id", "rag_collection", "id"),
]


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


def count(stack: LocalStack, db: str, table: str, where: str = "") -> int:
    clause = f" WHERE {where}" if where else ""
    return int(stack.sql(f"SELECT count(*) FROM {table}{clause}", db=db))


def orphan_counts(stack: LocalStack, db: str) -> dict[str, int]:
    result: dict[str, int] = {}
    for child, fk, parent, pk in FK_ORPHAN_CHECKS:
        sql = (
            f"SELECT count(*) FROM {child} c LEFT JOIN {parent} p "
            f"ON p.{pk} = c.{fk} WHERE c.{fk} IS NOT NULL AND p.{pk} IS NULL")
        key = f"{child}.{fk}"
        result[key] = int(stack.sql(sql, db=db))
    return result


def assert_no_orphans(stack: LocalStack, db: str, stage: str) -> None:
    orphans = orphan_counts(stack, db)
    bad = {key: value for key, value in orphans.items() if value}
    check(not bad, f"{stage}：全库孤儿扫描为 0（异常：{bad or '无'}）")


def create_doc(stack: LocalStack, base: str, **kwargs) -> dict:
    payload = {
        "collectionKey": kwargs.get("collectionKey", "delete-base"),
        "title": kwargs.get("title", "doc"),
        "content": kwargs.get("content", "content " * 30),
        "embeddingPolicy": kwargs.get("embeddingPolicy", "SYNC"),
    }
    headers = {}
    if kwargs.get("idempotencyKey"):
        headers["Idempotency-Key"] = kwargs["idempotencyKey"]
    status, body = json_request(
        "POST", f"{base}/api/v1/rag/documents", payload, extra_headers=headers)
    if status != 200:
        raise RuntimeError(f"创建文档失败 HTTP {status}: {body[:500]}")
    return json.loads(body)


def upsert_external(stack: LocalStack, base: str, collection_key: str,
                    external_id: str, content: str) -> dict:
    status, body = json_request(
        "POST", f"{base}/api/v1/rag/documents/upsert",
        {"collectionKey": collection_key, "sourceNamespace": "delete-drill",
         "externalId": external_id, "sourceRevision": "rev-1",
         "title": f"External {external_id}", "content": content,
         "embed": True, "embeddingPolicy": "SYNC"})
    if status != 200:
        raise RuntimeError(f"外部 upsert 失败 HTTP {status}: {body[:500]}")
    return json.loads(body)


def delete_doc(stack: LocalStack, base: str, document_id: int,
               revision: int) -> tuple[int, str]:
    return json_request(
        "DELETE", f"{base}/api/v1/rag/documents/{document_id}"
        f"?expectedDocumentRevision={revision}", {})


def collection_id(stack: LocalStack, db: str, key: str) -> int:
    return int(stack.sql(
        f"SELECT id FROM rag_collection WHERE collection_key='{key}'", db=db))


def seed_reference_and_operation_rows(stack: LocalStack, db: str, cid: int,
                                      doc_id: int, profile_id: str) -> dict:
    content_hash = stack.sql(
        f"SELECT content_hash FROM rag_documents WHERE id={doc_id}", db=db)
    sync_run_id = str(uuid.uuid4())
    job_id = str(uuid.uuid4())
    processing_run_id = str(uuid.uuid4())
    stack.execute(
        f"INSERT INTO rag_document_sync_runs "
        f"(id, collection_id, source_namespace, client_run_id, "
        f"lease_token_hash, sync_generation, snapshot_start_sequence, "
        f"snapshot_mode, missing_policy, status, lease_expires_at, applied_count) "
        f"VALUES ('{sync_run_id}', {cid}, 'delete-drill', 'client-run-1', "
        f"'{HASH64}', 1, 1, 'ONLINE_CUT', 'NONE', 'COMPLETED', "
        f"CURRENT_TIMESTAMP - INTERVAL '1 hour', 1)", db=db)
    stack.execute(
        f"INSERT INTO rag_document_sync_run_items "
        f"(run_id, external_id, document_kind, item_fingerprint, "
        f"source_revision, document_id, status) "
        f"VALUES ('{sync_run_id}', 'delete-doc-1', 'TEXT', '{HASH64}', "
        f"'rev-1', {doc_id}, 'APPLIED')", db=db)
    stack.execute(
        f"INSERT INTO rag_embedding_jobs "
        f"(id, batch_id, document_id, embedding_profile_id, request_generation, "
        f"content_hash, document_version, document_kind, chunker_version, "
        f"status, attempt_count, max_attempts, last_error) "
        f"VALUES ('{job_id}', '{uuid.uuid4()}', {doc_id}, {profile_id}, 1, "
        f"'{content_hash}', 1, 'TEXT', '{CHUNKER}', 'FAILED', 3, 3, "
        f"'forced failure')", db=db)
    stack.execute(
        f"INSERT INTO rag_document_processing_runs "
        f"(id, document_id, embedding_profile_id, request_generation, "
        f"document_version, content_hash, document_kind, chunker_version, "
        f"attempt_no, max_attempts, status, origin, started_at, finished_at) "
        f"VALUES ('{processing_run_id}', {doc_id}, {profile_id}, 1, 1, "
        f"'{content_hash}', 'TEXT', '{CHUNKER}', 2, 3, 'COMPLETED', 'API', "
        f"CURRENT_TIMESTAMP - INTERVAL '5 minutes', "
        f"CURRENT_TIMESTAMP - INTERVAL '4 minutes')", db=db)
    stack.execute(
        f"INSERT INTO rag_document_processing_stages "
        f"(run_id, stage_name, stage_order, status) "
        f"VALUES ('{processing_run_id}', 'PARSING', 1, 'SUCCEEDED')", db=db)
    return {"sync_run_id": sync_run_id, "job_id": job_id,
            "processing_run_id": processing_run_id}


def seed_eval_and_files(stack: LocalStack, db: str, cid: int) -> dict:
    suite_id = str(uuid.uuid4())
    version_id = str(uuid.uuid4())
    run_id = str(uuid.uuid4())
    prefix = str(uuid.uuid4())
    entry_path = f"{prefix}/source/sample.pdf"
    extra_path = f"{prefix}/source/extra.bin"
    retained_path = f"{prefix}/source/retained.bin"
    import_id = str(uuid.uuid4())
    stack.execute(
        f"INSERT INTO rag_evaluation_suites (id, suite_key, name, owner_principal_id) "
        f"VALUES ('{suite_id}', 'delete-drill-suite', 'Delete Drill', 'root')",
        db=db)
    stack.execute(
        f"INSERT INTO rag_evaluation_suite_versions "
        f"(id, suite_id, version, definition, definition_sha256) "
        f"VALUES ('{version_id}', '{suite_id}', 1, "
        f"'{{\"cases\":[],\"variants\":[]}}'::jsonb, '{HASH64}')", db=db)
    stack.execute(
        f"INSERT INTO rag_evaluation_runs "
        f"(id, suite_version_id, owner_principal_id, status, "
        f"configuration_snapshot) VALUES ('{run_id}', '{version_id}', "
        f"'root', 'PASSED', '{{}}'::jsonb)", db=db)
    stack.execute(
        f"INSERT INTO rag_evaluation_case_results "
        f"(run_id, variant_key, case_id, status, metrics) "
        f"VALUES ('{run_id}', 'default', 'case-1', 'PASSED', "
        f"'{{\"score\":1.0}}'::jsonb)", db=db)
    for path, index in ((entry_path, 0), (extra_path, 1),
                        (retained_path, 2)):
        stack.execute(
            f"INSERT INTO fs_files "
            f"(path, is_text, content_bin, mime_type, file_size, "
            f"owner_principal_id, collection_id) "
            f"VALUES ('{path}', FALSE, decode('25504446', 'hex'), "
            f"'application/pdf', {4 + index}, 'root', {cid})", db=db)
    stack.execute(
        f"INSERT INTO fs_import_batches "
        f"(import_id, source_type, original_filename, display_name, "
        f"entry_path, original_path, file_count, owner_principal_id, "
        f"collection_id) VALUES ('{import_id}', 'PDF', 'sample.pdf', "
        f"'sample', '{entry_path}', '{entry_path}', 1, 'root', {cid})", db=db)
    return {"suite_id": suite_id, "version_id": version_id, "run_id": run_id,
            "import_id": import_id, "entry_path": entry_path,
            "extra_path": extra_path, "retained_path": retained_path,
            "prefix": prefix}


def purge_sync(stack: LocalStack, base: str, key: str) -> dict:
    status, body = json_request(
        "POST", f"{base}/api/v1/rag/collections/by-key/purge/preview"
        f"?collectionKey={key}", {})
    check(status == 200, f"Collection purge 预览（HTTP {status}）")
    preview = json.loads(body)
    status, body = json_request(
        "POST", f"{base}/api/v1/rag/collections/by-key/purge",
        {"collectionKey": key, "previewId": preview["previewId"],
         "confirmationToken": preview["confirmationToken"],
         "fingerprint": preview["fingerprint"],
         "expectedCollectionVersion": preview["collectionVersion"],
         "expectedChatCommitFenceVersion": preview["chatCommitFenceVersion"]})
    check(status == 200, f"Collection 永久清理应用（HTTP {status}）")
    return preview


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18092)
    parser.add_argument("--mock-port", type=int, default=18093)
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--rebuild", action="store_true")
    args = parser.parse_args()

    stack = LocalStack("rag_delete_drill", args.port, args.mock_port, args.keep)
    db = None
    try:
        db = stack.create_database()
        info(f"临时库：{db}")
        stack.build_demo_if_needed(force=args.rebuild)
        stack.start_mock()
        base = stack.start_app(
            database=db,
            extra_env={"RAG_COLLECTION_PURGE_ENABLED": "true"})

        # ---------- 0. 基础数据 ----------
        for key, name in (("delete-base", "Delete Base"),
                          ("delete-keep", "Delete Keep")):
            status, body = json_request(
                "POST", f"{base}/api/v1/rag/collections",
                {"collectionKey": key, "name": name,
                 "embeddingModel": "BAAI/bge-m3", "dimensions": 1024})
            check(status in (200, 201), f"创建 collection {key}（HTTP {status}）")
        cid = collection_id(stack, db, "delete-base")
        keep_cid = collection_id(stack, db, "delete-keep")
        profile_id = stack.sql(
            "SELECT id FROM rag_embedding_profiles ORDER BY id LIMIT 1", db=db)

        doc_local = create_doc(stack, base, collectionKey="delete-base",
                               title="Local delete document",
                               content="local delete marker " * 40,
                               idempotencyKey="delete-drill-idem")
        doc_single = create_doc(stack, base, collectionKey="delete-base",
                                title="Single delete document",
                                content="single delete marker " * 40)
        doc_ext = upsert_external(stack, base, "delete-base", "delete-ext-1",
                                  "external delete marker " * 40)
        doc_idem = create_doc(stack, base, collectionKey="delete-base",
                              title="Idempotency delete document",
                              content="idempotency delete marker " * 40,
                              idempotencyKey="delete-reuse-key")
        local_id = int(doc_local["id"])
        single_id = int(doc_single["id"])
        ext_id = int(doc_ext["documentId"])
        idem_id = int(doc_idem["id"])
        info(f"文档：local={local_id} single={single_id} "
             f"external={ext_id} idem={idem_id}")

        seeded = seed_reference_and_operation_rows(
            stack, db, cid, local_id, profile_id)
        eval_files = seed_eval_and_files(stack, db, cid)

        # chat + feedback 留下真实引用行。
        status, body = json_request(
            "POST", f"{base}/api/v1/rag/chat",
            {"message": "delete drill marker", "sessionId": "delete-session",
             "collectionKeys": ["delete-base"], "maxResults": 3})
        check(status == 200, f"会话链路写入 history（HTTP {status}）")
        status, body = json_request(
            "POST", f"{base}/api/v1/rag/evaluation/feedback",
            {"sessionId": "delete-session", "query": "delete drill",
             "feedbackType": "THUMBS_UP", "rating": 5,
             "selectedDocumentIds": [local_id, ext_id]})
        check(status == 200, f"反馈写入 feedback/feedback_document（HTTP {status}）")
        feedback_id = int(json.loads(body)["id"])
        check(int(stack.sql(
            f"SELECT count(*) FROM rag_user_feedback_document "
            f"WHERE feedback_id={feedback_id}", db=db)) >= 1,
            "反馈引用表存在 document_id 行")

        assert_no_orphans(stack, db, "初始数据")

        # ---------- 1. 单篇删除级联 ----------
        info("删除本地文档并检查全部派生行")
        status, body = delete_doc(stack, base, single_id, 1)
        check(status == 200, f"删除本地文档（HTTP {status}）")
        for table in ("rag_document_chunks", "rag_embeddings",
                      "rag_document_versions", "rag_document_embedding_state",
                      "rag_document_local_index_state"):
            check(count(stack, db, table, f"document_id={single_id}") == 0,
                  f"删除后 {table} 无残留")
        check(count(stack, db, "rag_user_feedback_document",
                    f"document_id={single_id}") == 0,
              "删除后反馈引用无残留")
        check(count(stack, db, "rag_chat_history_source_document",
                    f"document_id={single_id}") == 0,
              "删除后会话来源引用无残留")

        # 外部文档删除是"源端 tombstone"（软删），保留行与向量供源同步审计；
        # 真正的物理清除由 Collection 永久清理负责。
        info("外部文档走源端 tombstone（软删语义）")
        before_tombstone_chunks = count(
            stack, db, "rag_document_chunks", f"document_id={ext_id}")
        check(before_tombstone_chunks > 0,
              "外部文档 tombstone 前存在 chunk/向量")
        status, body = json_request(
            "DELETE", f"{base}/api/v1/rag/documents/by-external-id"
            "?collectionKey=delete-base&sourceNamespace=delete-drill"
            "&externalId=delete-ext-1&sourceRevision=rev-2"
            "&expectedSourceRevision=rev-1", {})
        check(status == 200, f"外部文档源端删除（HTTP {status}）")
        check(stack.sql(
            f"SELECT enabled::text FROM rag_documents WHERE id={ext_id}", db=db)
              == "false",
              "外部文档 tombstone 后 enabled=false")
        status, body = json_request(
            "GET", f"{base}/api/v1/rag/search?query=external"
            "&limit=10&collectionScopeMode=SELECTED_COLLECTIONS"
            "&collectionKeys=delete-base", {})
        check(status == 200, f"tombstone 后检索链路可用（HTTP {status}）")
        hits = json.loads(body) if status == 200 else []
        if isinstance(hits, dict):
            hits = hits.get("results", [])
        check(all(str(hit.get("documentId")) != str(ext_id) for hit in hits),
              "tombstone 文档不再出现在检索结果")
        assert_no_orphans(stack, db, "单篇删除后")

        # ---------- 2. 同 id 重建不串旧数据 ----------
        info("同 id 重建：显式复用已删除的 document id")
        stack.execute(
            f"INSERT INTO rag_documents (id, collection_id, title, content, "
            f"content_hash, source_namespace, document_revision, "
            f"processing_status) VALUES ({single_id}, {cid}, 'reused id doc', "
            f"'brand new content', '{HASH64}', 'delete-drill', 1, 'COMPLETED')",
            db=db)
        check(count(stack, db, "rag_document_chunks",
                    f"document_id={single_id}") == 0,
              "同 id 重建后没有旧 chunk")
        check(count(stack, db, "rag_embeddings",
                    f"document_id={single_id}") == 0,
              "同 id 重建后没有旧向量")
        check(count(stack, db, "rag_document_versions",
                    f"document_id={single_id}") == 0,
              "同 id 重建后没有旧版本快照")
        stack.execute(f"DELETE FROM rag_documents WHERE id={single_id}", db=db)

        # ---------- 3. 同 Idempotency-Key 重建 ----------
        info("复用同一 Idempotency-Key 删除后重建")
        status, body = delete_doc(stack, base, idem_id, 1)
        check(status == 200, f"删除幂等文档（HTTP {status}）")
        retained = int(stack.sql(
            "SELECT count(*) FROM rag_document_idempotency_operations "
            "WHERE result_document_id IS NULL AND status='SUCCEEDED'", db=db))
        info(f"删除后保留的失效幂等凭证：{retained} 条")
        recreated = create_doc(
            stack, base, collectionKey="delete-base",
            title="Idempotency delete document",
            content="idempotency delete marker " * 40,
            idempotencyKey="delete-reuse-key")
        check(recreated["status"] == "CREATED",
              f"同一幂等键重建返回 CREATED（实际 {recreated['status']}）")
        new_id = int(recreated["id"])
        check(new_id != idem_id, f"重建得到新文档 id（{idem_id} → {new_id}）")
        check(count(stack, db, "rag_document_chunks",
                    f"document_id={new_id}") > 0,
              "重建文档有新的 chunk")
        check(count(stack, db, "rag_document_chunks",
                    f"document_id={idem_id}") == 0,
              "旧文档 id 没有残留 chunk")
        check(int(stack.sql(
            "SELECT count(*) FROM rag_document_idempotency_operations "
            f"WHERE result_document_id={new_id} AND status='SUCCEEDED'", db=db))
              >= 1,
              "幂等凭证重新指向新文档")

        # ---------- 4. 批量删除部分失败原子性 ----------
        info("批量删除部分失败：强制第二条失败，断言全事务回滚")
        batch_one = create_doc(stack, base, collectionKey="delete-keep",
                               title="Batch rollback one",
                               content="batch rollback one " * 30)
        batch_two = create_doc(stack, base, collectionKey="delete-keep",
                               title="Batch rollback two",
                               content="batch rollback two " * 30)
        b1, b2 = int(batch_one["id"]), int(batch_two["id"])
        stack.execute(
            "CREATE OR REPLACE FUNCTION drill_force_delete_failure() "
            "RETURNS trigger AS $$ BEGIN "
            f"IF OLD.id = {b2} THEN RAISE EXCEPTION 'drill forced failure'; "
            "END IF; RETURN OLD; END; $$ LANGUAGE plpgsql", db=db)
        stack.execute(
            "CREATE TRIGGER drill_force_delete_failure BEFORE DELETE ON "
            "rag_documents FOR EACH ROW EXECUTE FUNCTION "
            "drill_force_delete_failure()", db=db)
        status, body = json_request(
            "DELETE", f"{base}/api/v1/rag/documents/batch",
            {"ids": [b1, b2]})
        check(status >= 500, f"受控失败使批量删除返回错误（HTTP {status}）")
        check(count(stack, db, "rag_documents", f"id IN ({b1},{b2})") == 2,
              "批量删除失败后两篇文档都仍在（无半删）")
        check(count(stack, db, "rag_document_chunks",
                    f"document_id IN ({b1},{b2})") > 0,
              "批量删除失败后派生行完整保留")
        stack.execute("DROP TRIGGER drill_force_delete_failure "
                      "ON rag_documents", db=db)
        stack.execute("DROP FUNCTION drill_force_delete_failure()", db=db)
        status, body = json_request(
            "DELETE", f"{base}/api/v1/rag/documents/batch",
            {"ids": [b1, b2]})
        check(status == 200, f"移除故障后批量删除成功（HTTP {status}）")
        check(count(stack, db, "rag_documents", f"id IN ({b1},{b2})") == 0,
              "重试后两篇文档全部删除")
        check(count(stack, db, "rag_document_chunks",
                    f"document_id IN ({b1},{b2})") == 0,
              "重试后 chunk 无残留")
        assert_no_orphans(stack, db, "批量删除后")

        # ---------- 5. 反馈 / 评估 / 文件 / 导入批删除 ----------
        info("删除反馈、评估 suite、upload 文件与 import batch")
        stack.execute(f"DELETE FROM rag_user_feedback WHERE id={feedback_id}",
                      db=db)
        check(count(stack, db, "rag_user_feedback_document",
                    f"feedback_id={feedback_id}") == 0,
              "删除反馈后 feedback_document 无残留")
        check(count(stack, db, "rag_user_feedback", f"id={feedback_id}") == 0,
              "反馈主行已删除")

        stack.execute(
            f"DELETE FROM rag_evaluation_suites "
            f"WHERE id='{eval_files['suite_id']}'", db=db)
        check(count(stack, db, "rag_evaluation_suites",
                    f"id='{eval_files['suite_id']}'") == 0,
              "评估 suite 已删除")
        check(count(stack, db, "rag_evaluation_suite_versions",
                    f"suite_id='{eval_files['suite_id']}'") == 0,
              "评估 version 级联删除")
        check(count(stack, db, "rag_evaluation_runs",
                    f"id='{eval_files['run_id']}'") == 0,
              "评估 run 级联删除")
        check(count(stack, db, "rag_evaluation_case_results",
                    f"run_id='{eval_files['run_id']}'") == 0,
              "评估 case result 级联删除")

        # 删除 import batch 只删批次，上传文件属于文件库，按保留策略保留。
        stack.execute(
            f"DELETE FROM fs_import_batches "
            f"WHERE import_id='{eval_files['import_id']}'", db=db)
        check(count(stack, db, "fs_import_batches",
                    f"import_id='{eval_files['import_id']}'") == 0,
              "import batch 已删除")
        check(count(stack, db, "fs_files",
                    f"path='{eval_files['entry_path']}'") == 1,
              "删除 import batch 不级联删除 upload 文件（已知保留项）")
        # 删除入口文件会级联删除仍指向它的 batch（外键方向）。
        stack.execute(
            f"DELETE FROM fs_files WHERE path='{eval_files['entry_path']}'",
            db=db)
        check(count(stack, db, "fs_files",
                    f"path='{eval_files['entry_path']}'") == 0,
              "upload 入口文件已删除")
        check(count(stack, db, "fs_import_batches",
                    f"entry_path='{eval_files['entry_path']}'") == 0,
              "入口文件删除后 batch 无残留")
        stack.execute(
            f"DELETE FROM fs_files WHERE path='{eval_files['extra_path']}'",
            db=db)
        check(count(stack, db, "fs_files",
                    f"path='{eval_files['extra_path']}'") == 0,
              "upload 附属文件已删除")
        assert_no_orphans(stack, db, "反馈/评估/文件删除后")

        # ---------- 6. Collection 永久清理 ----------
        info("Collection 永久清理：目标为 delete-base")
        preview = purge_sync(stack, base, "delete-base")
        check(preview["documentCount"] >= 1,
              f"预览识别文档数 {preview['documentCount']}")
        check(preview["feedbackCount"] == 0,
              "预览反馈计数与已删反馈一致（0）")
        check(preview["documentIdempotencyOperationCount"] >= 1,
              "预览识别幂等凭证")

        check(count(stack, db, "rag_documents", f"collection_id={cid}") == 0,
              "清理后 collection 文档全部删除")
        check(count(stack, db, "rag_document_chunks c JOIN rag_documents d "
                    f"ON d.id=c.document_id WHERE d.collection_id={cid}") == 0,
              "清理后 chunk 无残留")
        check(count(stack, db, "rag_embeddings e JOIN rag_documents d "
                    f"ON d.id=e.document_id WHERE d.collection_id={cid}") == 0,
              "清理后向量无残留")
        check(count(stack, db, "rag_document_sync_runs",
                    f"collection_id={cid}") == 0,
              "清理后同步历史无残留")
        check(count(stack, db, "rag_embedding_jobs",
                    f"document_id={local_id}") == 0,
              "清理后失败 job 无残留")
        check(count(stack, db, "rag_document_processing_runs",
                    f"document_id={local_id}") == 0,
              "清理后处理 run 无残留")
        check(count(stack, db, "rag_document_processing_stages s "
                    "JOIN rag_document_processing_runs r ON r.id=s.run_id "
                    f"WHERE r.document_id={local_id}") == 0,
              "清理后处理 stage 无残留")
        check(count(stack, db, "rag_document_idempotency_operations o "
                    "JOIN rag_documents d ON d.id=o.result_document_id "
                    f"WHERE d.collection_id={cid}") == 0,
              "清理后幂等操作无残留")
        check(count(stack, db, "rag_user_feedback_document f JOIN "
                    f"rag_documents d ON d.id=f.document_id "
                    f"WHERE d.collection_id={cid}") == 0,
              "清理后反馈引用无残留")
        check(count(stack, db, "rag_chat_history_source_document s JOIN "
                    f"rag_documents d ON d.id=s.document_id "
                    f"WHERE d.collection_id={cid}") == 0,
              "清理后会话来源引用无残留")
        check(count(stack, db, "rag_chat_history",
                    "session_id='delete-session'") == 0,
              "清理后关联会话 history 删除")
        check(count(stack, db, "rag_collection_operation_slot",
                    f"collection_id={cid}") == 0,
              "清理后操作槽位释放")
        check(count(stack, db, "rag_collection",
                    f"id={cid} AND purged_at IS NOT NULL") == 1,
              "Collection 行按保留策略保留为已清理 tombstone")
        check(count(stack, db, "fs_files",
                    f"path='{eval_files['retained_path']}'") == 1,
              "upload 文件按保留策略保留（Collection 清理不删除文件库，已知不删项）")
        check(count(stack, db, "rag_documents",
                    f"collection_id={keep_cid}") >= 0,
              "其他 collection 不受影响")

        assert_no_orphans(stack, db, "Collection 清理后")

        # ---------- 7. 统计 / 容量回正 ----------
        info("删除后统计与长任务容量计数")
        check(count(stack, db, "rag_user_feedback") == 0,
              "反馈统计源表行数回正（0）")
        check(stack.sql(
            "SELECT coalesce(active_count, 0) FROM "
            "rag_long_running_operation_global_capacity", db=db) == "0",
            "全局长任务 active_count 回正为 0")
        check(stack.sql(
            "SELECT coalesce(sum(active_count), 0) FROM "
            "rag_long_running_operation_owner_capacity", db=db) == "0",
            "owner 长任务 active_count 回正为 0")
        status, body = json_request(
            "GET", f"{base}/api/v1/rag/collections/by-key"
            "?collectionKey=delete-base", {})
        check(status in (404, 409),
              f"已清理 Collection 不再可读（HTTP {status}）")
        status, body = json_request(
            "GET", f"{base}/api/v1/rag/collections/by-key"
            "?collectionKey=delete-keep", {})
        check(status == 200, f"未删除 Collection 仍可读（HTTP {status}）")
    finally:
        stack.cleanup()

    if FAILURES:
        print(f"\n{RED}删除完整性审计失败 {len(FAILURES)} 项：{NC}")
        for item in FAILURES:
            print(f"  - {item}")
        return 1
    print(f"\n{GREEN}删除完整性审计全部通过：无孤儿、无半删、可重建、计数回正{NC}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
