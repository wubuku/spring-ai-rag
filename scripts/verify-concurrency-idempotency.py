#!/usr/bin/env python3
"""真实 PostgreSQL 并发/幂等验收。

覆盖五类平时不会碰的竞态：
  1. 同一 collectionKey 并发创建
  2. 同一外部文档并发幂等同步
  3. 同一文件并发上传（同一 Idempotency-Key）
  4. 同一文档并发 reindex（force re-embed）
  5. 同一 principal 并发配额扣减（PostgreSQL 共享固定窗口）

断言：不产生重复行、不丢更新、除业务冲突（409）与限流（429）外不出现 5xx，
计数与数据库一致；并验证实现依赖唯一约束/条件写入而不是悲观锁。

用法：
  ./scripts/verify-concurrency-idempotency.py
  ./scripts/verify-concurrency-idempotency.py --keep --rebuild
  ./scripts/verify-concurrency-idempotency.py --port 18086 --mock-port 18087

依赖：JDK 21+、Maven、PostgreSQL（psql/createdb）、node、curl 可用。
退出码 0 表示全部断言通过。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))
from rag_local_stack import (  # noqa: E402
    ROOT_API_KEY,
    LocalStack,
    json_request,
    multipart_request,
)

GREEN = "\033[0;32m"
RED = "\033[0;31m"
YELLOW = "\033[1;33m"
NC = "\033[0m"

FAILURES: list[str] = []


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


def status_of(result: tuple[int, str]) -> int:
    return result[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18086)
    parser.add_argument("--mock-port", type=int, default=18087)
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--rebuild", action="store_true")
    args = parser.parse_args()

    stack = LocalStack("rag_concurrency", args.port, args.mock_port, args.keep)
    base_key = "conc-base"
    try:
        stack.create_database()
        info(f"临时数据库：{stack.db_name}")
        stack.build_demo_if_needed(force=args.rebuild)
        stack.start_mock()
        base_url = stack.start_app()

        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/collections",
            {"collectionKey": base_key, "name": "Concurrency Base",
             "embeddingModel": "BAAI/bge-m3", "dimensions": 1024})
        check(status in (200, 201), f"基准 collection 创建成功（HTTP {status}）")
        if status not in (200, 201):
            print(body[:500])
            return 1

        run_scenarios(stack, base_url, base_key)

        # 配额场景需要低阈值，单独重启，避免前四类请求互相消耗窗口。
        run_quota_scenario(stack, base_url)

        prove_constraint_based_coordination(stack)
    finally:
        stack.cleanup()

    if FAILURES:
        print(f"\n{RED}失败 {len(FAILURES)} 项：{NC}")
        for item in FAILURES:
            print(f"  - {item}")
        return 1
    print(f"\n{GREEN}并发/幂等验收全部通过{NC}")
    return 0


def run_scenarios(stack: LocalStack, base_url: str, base_key: str) -> None:
    # ---------- 1. 同一 collectionKey 并发创建 ----------
    info("场景 1：同一 collectionKey 并发创建")
    collection_key = "conc-dup-collection"

    def create_collection(_: int) -> tuple[int, str]:
        return json_request(
            "POST", f"{base_url}/api/v1/rag/collections",
            {"collectionKey": collection_key, "name": "Concurrency Duplicate",
             "embeddingModel": "BAAI/bge-m3", "dimensions": 1024})

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(create_collection, range(8)))
    statuses = [status_of(r) for r in results]
    check(all(code in (200, 201, 409) for code in statuses),
          f"collection 并发创建只出现 200/201/409（实际 {sorted(set(statuses))}）")
    check(statuses.count(200) + statuses.count(201) == 1,
          f"collection 并发创建恰好成功一次（成功 {statuses.count(200) + statuses.count(201)}）")
    rows = stack.sql(
        f"SELECT count(*) FROM rag_collection WHERE collection_key = '{collection_key}'")
    check(rows == "1", f"collectionKey 只有一行（实际 {rows}）")

    # ---------- 2. 同一外部文档并发幂等同步 ----------
    info("场景 2：同一外部文档并发 upsert（幂等同步语义）")
    collection_id = stack.sql(
        f"SELECT id FROM rag_collection WHERE collection_key = '{base_key}'")
    payload = {
        "collectionKey": base_key,
        "sourceNamespace": "sync",
        "externalId": "race-ext-1",
        "sourceRevision": "rev-1",
        "title": "Concurrent External Sync",
        "content": "Stable external content used for the concurrency race assertion.",
        "embed": False,
    }

    def upsert(_: int) -> tuple[int, str]:
        return json_request(
            "POST", f"{base_url}/api/v1/rag/documents/upsert", payload)

    with ThreadPoolExecutor(max_workers=10) as pool:
        results = list(pool.map(upsert, range(10)))
    statuses = [status_of(r) for r in results]
    check(all(code in (200, 409) for code in statuses),
          f"外部文档并发 upsert 只出现 200/409（实际 {sorted(set(statuses))}）")
    row_count = stack.sql(
        "SELECT count(*) FROM rag_documents "
        f"WHERE collection_id = {collection_id} AND source_namespace = 'sync' "
        "AND external_id = 'race-ext-1'")
    check(row_count == "1", f"外部身份只有一行（实际 {row_count}）")
    versions = stack.sql(
        "SELECT count(*) FROM rag_document_versions v JOIN rag_documents d "
        "ON d.id = v.document_id WHERE d.external_id = 'race-ext-1'")
    check(versions == "1", f"外部文档只产生一个历史版本（实际 {versions}）")
    unchanged = sum(
        1 for status, body in results
        if status == 200 and json.loads(body).get("action") == "UNCHANGED")
    created = sum(
        1 for status, body in results
        if status == 200 and json.loads(body).get("action") == "CREATED")
    check(created == 1, f"外部文档并发同步恰好创建一次（CREATED={created}）")
    check(unchanged + created + statuses.count(409) == 10,
          "外部文档并发结果分类完整（无丢响应）")

    # ---------- 3. 同一文件并发上传 ----------
    info("场景 3：同一文件并发上传（同一 Idempotency-Key）")
    file_content = (
        b"# Concurrency Upload\n\n"
        b"This file is uploaded eight times at once with one Idempotency-Key.\n"
        b"The system must converge on exactly one document row.\n"
    ) * 4
    content_hash = hashlib.sha256(file_content).hexdigest()

    def upload(_: int) -> tuple[int, str]:
        return multipart_request(
            f"{base_url}/api/v1/rag/documents/upload",
            "files", "race-upload.md", file_content,
            {"collectionKey": base_key},
            extra_headers={"Idempotency-Key": "conc-upload-key"})

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(upload, range(8)))
    document_ids = set()
    conflicts = 0
    for status, body in results:
        if status != 200:
            conflicts += 1
            continue
        parsed = json.loads(body)
        for item in parsed.get("results", []):
            if item.get("documentId") is not None:
                document_ids.add(item["documentId"])
    check(all(status_of(r) == 200 for r in results),
          "上传端点在并发下返回 200（逐文件失败通过 results 表达）")
    check(len(document_ids) == 1,
          f"并发上传收敛到同一文档（documentId 集合 {sorted(document_ids)}）")
    rows = stack.sql(
        "SELECT count(*) FROM rag_documents "
        f"WHERE collection_id = {collection_id} AND content_hash = '{content_hash}'")
    check(rows == "1", f"同一内容只有一行文档（实际 {rows}）")
    idem_rows = stack.sql(
        "SELECT count(*) FROM rag_document_idempotency_operations "
        "WHERE idempotency_key_hash = encode(sha256('conc-upload-key:0'::bytea), 'hex')")
    check(idem_rows == "1", f"同一 Idempotency-Key 只有一条操作记录（实际 {idem_rows}）")

    # ---------- 4. 同一文档并发 reindex ----------
    info("场景 4：同一文档并发 reindex（force re-embed）")
    long_text = "\n\n".join(
        f"Section {i}: Spring AI RAG concurrency reindex marker section {i}."
        for i in range(40))
    status, body = json_request(
        "POST", f"{base_url}/api/v1/rag/documents",
        {"title": "Concurrent Reindex", "content": long_text,
         "collectionKey": base_key})
    check(status == 200, f"创建 reindex 文档（HTTP {status}）")
    document_id = json.loads(body)["id"]

    status, body = json_request(
        "POST", f"{base_url}/api/v1/rag/documents/{document_id}/embed", {})
    check(status == 200, f"首次嵌入完成（HTTP {status}）")
    chunk_count = int(json.loads(body).get("chunksCreated", 0))
    check(chunk_count >= 2, f"文档被切成多个 chunk（chunks={chunk_count}）")

    def reindex(_: int) -> tuple[int, str]:
        return json_request(
            "POST", f"{base_url}/api/v1/rag/documents/{document_id}/embed?force=true",
            {}, timeout=120)

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(reindex, range(8)))
    statuses = [status_of(r) for r in results]
    check(all(code in (200, 409) for code in statuses),
          f"并发 reindex 只出现 200/409（实际 {sorted(set(statuses))}）")
    if any(code == 500 for code in statuses):
        for status, body in results:
            if status == 500:
                print(body[:800])
    embedding_rows = stack.sql(
        f"SELECT count(*) FROM rag_embeddings WHERE document_id = {document_id}")
    distinct_chunks = stack.sql(
        "SELECT count(DISTINCT chunk_index) FROM rag_embeddings "
        f"WHERE document_id = {document_id}")
    check(embedding_rows == str(chunk_count),
          f"最终向量行数等于 chunk 数（rows={embedding_rows}, chunks={chunk_count}）")
    check(embedding_rows == distinct_chunks,
          f"没有重复 chunk_index（rows={embedding_rows}, distinct={distinct_chunks}）")
    revision = stack.sql(
        f"SELECT document_revision FROM rag_documents WHERE id = {document_id}")
    check(revision.isdigit() and int(revision) >= 1,
          f"文档 revision 未丢失（revision={revision}）")


def run_quota_scenario(stack: LocalStack, base_url: str) -> None:
    info("场景 5：同一 principal 并发配额扣减（PostgreSQL 固定窗口）")
    limit = 20
    app_port = int(base_url.rsplit(":", 1)[1])
    stack.start_app(extra_env={
        "RAG_RATE_LIMIT_ENABLED": "true",
        "RAG_RATE_LIMIT_BACKEND": "postgresql",
        "RAG_RATE_LIMIT_STRATEGY": "principal",
        "RAG_RATE_LIMIT_REQUESTS_PER_MINUTE": str(limit),
        "RAG_RATE_LIMIT_CLEANUP_INTERVAL_SECONDS": "3600",
    }, port=app_port)
    base_url = f"http://127.0.0.1:{app_port}"

    def call(_: int) -> tuple[int, str]:
        return json_request("GET", f"{base_url}/api/v1/rag/collections", {})

    with ThreadPoolExecutor(max_workers=60) as pool:
        results = list(pool.map(call, range(60)))
    statuses = [status_of(r) for r in results]
    allowed = sum(1 for code in statuses if code == 200)
    rejected = statuses.count(429)
    check(all(code in (200, 429) for code in statuses),
          f"配额并发只出现 200/429（实际 {sorted(set(statuses))}）")
    check(rejected >= 1, f"至少一个请求被限流（429={rejected}）")
    check(allowed + rejected == 60, "所有 60 个响应都有结果")

    bucket_rows = stack.sql_rows(
        "SELECT request_count FROM rag_api_rate_limit_bucket "
        "WHERE principal_id = 'environment-root'")
    bucket_sum = sum(int(row[0]) for row in bucket_rows)
    check(all(int(row[0]) <= limit for row in bucket_rows),
          f"任何窗口计数都不超过上限 {limit}（buckets={[row[0] for row in bucket_rows]}）")
    check(bucket_sum == allowed,
          f"数据库计数等于放行请求数（db={bucket_sum}, allowed={allowed}）")
    retained = stack.sql(
        "SELECT count(*) FROM rag_api_rate_limit_bucket "
        "WHERE principal_id = 'environment-root' AND request_count = 0")
    check(retained == "0", "被拒绝的请求没有虚增计数")


def prove_constraint_based_coordination(stack: LocalStack) -> None:
    info("验证协调依赖唯一约束/条件写入，而不是悲观锁")
    index_rows = stack.sql(
        "SELECT indexname FROM pg_indexes WHERE indexdef LIKE '%UNIQUE%' "
        "AND tablename IN ('rag_documents','rag_collection','rag_embeddings')")
    required = [
        "uk_rag_doc_external_identity_v2",
        "uk_rag_collection_collection_key",
        "uq_rag_embedding_chunk_profile",
    ]
    present = set(index_rows.splitlines())
    for name in required:
        check(name in present, f"唯一约束存在：{name}")
    constraint = stack.sql(
        "SELECT count(*) FROM pg_constraint WHERE conname = 'uk_rag_document_idempotency'")
    check(constraint == "1", "幂等操作有唯一约束 uk_rag_document_idempotency")

    rate_limit_store = (
        Path(__file__).resolve().parents[1]
        / "spring-ai-rag-core/src/main/java/com/springairag/core/ratelimit/PostgresRateLimitStore.java"
    ).read_text()
    check("ON CONFLICT (principal_id, window_start) DO UPDATE" in rate_limit_store,
          "配额扣减使用单条 INSERT ... ON CONFLICT 条件更新")
    check("request_count < ?" in rate_limit_store,
          "配额扣减带条件谓词，不依赖显式锁")
    check(not re.search(r"FOR\s+UPDATE|SKIP\s+LOCKED|pg_advisory", rate_limit_store),
          "配额实现没有 FOR UPDATE / SKIP LOCKED / advisory lock")


if __name__ == "__main__":
    sys.exit(main())
