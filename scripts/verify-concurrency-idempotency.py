#!/usr/bin/env python3
"""真实 PostgreSQL 并发/幂等验收。

覆盖平时不会碰的竞态：
  1. 同一 collectionKey 并发创建
  2. 同一外部文档并发幂等同步
  3. 同一文件并发上传（同一 Idempotency-Key）
  4. 同一文档并发 reindex（force re-embed）
  5. 同一 principal 并发配额扣减（PostgreSQL 共享固定窗口）
  6. 同一 Idempotency-Key 并发建 Collection
  7. 同一 Idempotency-Key 并发建 Document
  8. 同一外部标识并发 JSON record upsert

断言：不产生重复行、不丢更新、除业务冲突（409）与限流（429）外不出现 5xx，
计数与数据库一致；并验证实现依赖唯一约束/条件写入而不是悲观锁。
这些场景与 `scripts/testdata/write-endpoints.json` 的登记口径对应。

用法：
  ./scripts/verify-concurrency-idempotency.py
  ./scripts/verify-concurrency-idempotency.py --repeat 3
  ./scripts/verify-concurrency-idempotency.py --keep --rebuild
  ./scripts/verify-concurrency-idempotency.py --port 18086 --mock-port 18087

依赖：JDK 21+、Maven、PostgreSQL（psql/createdb）、node、curl 可用。
退出码 0 表示全部断言通过。
"""

from __future__ import annotations

import argparse
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
    http_request,
    http_request_full,
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


def run_iteration(args: argparse.Namespace, iteration: int) -> bool:
    stack = LocalStack("rag_concurrency", args.port, args.mock_port, args.keep)
    base_key = "conc-base"
    try:
        stack.create_database()
        info(f"第 {iteration + 1} 轮临时数据库：{stack.db_name}")
        stack.build_demo_if_needed(force=args.rebuild)
        stack.start_mock()
        base_url = stack.start_app(extra_env={
            "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "32",
            "RAG_EMBEDDING_JOBS_WORKER_CONCURRENCY": "1",
        })

        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/collections",
            {"collectionKey": base_key, "name": "Concurrency Base",
             "embeddingModel": "BAAI/bge-m3", "dimensions": 1024})
        check(status in (200, 201),
              f"第 {iteration + 1} 轮基准 collection 创建成功（HTTP {status}）")
        if status not in (200, 201):
            print(body[:500])
            return False

        run_scenarios(stack, base_url, base_key)
        run_document_write_races(stack, base_url, base_key)
        run_external_revision_races(stack, base_url, base_key)
        run_idempotency_key_isolation(base_url, base_key)
        run_export_cursor_race(stack, base_url, "conc-export")
        run_async_reindex_race(stack, base_url, base_key)
        run_provisioning_idempotency_scenarios(stack, base_url, base_key)

        # 配额场景需要低阈值，单独重启，避免前四类请求互相消耗窗口。
        run_quota_scenario(stack, base_url)

        prove_constraint_based_coordination(stack)
        return True
    finally:
        stack.cleanup()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18086)
    parser.add_argument("--mock-port", type=int, default=18087)
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--rebuild", action="store_true")
    parser.add_argument(
        "--repeat", type=int, default=1,
        help="连续运行轮数；稳定性门槛用 --repeat 3")
    args = parser.parse_args()

    for iteration in range(args.repeat):
        run_iteration(args, iteration)

    if FAILURES:
        print(f"\n{RED}失败 {len(FAILURES)} 项：{NC}")
        for item in FAILURES:
            print(f"  - {item}")
        return 1
    print(f"\n{GREEN}并发/幂等验收全部通过（{args.repeat} 轮）{NC}")
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
    id_list = ",".join(str(value) for value in sorted(document_ids))
    rows = stack.sql(
        "SELECT count(*) FROM rag_documents "
        f"WHERE collection_id = {collection_id} AND id IN ({id_list})")
    check(rows == "1", f"同一幂等键只有一行文档（实际 {rows}）")
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


def api_get(url: str) -> tuple[int, str]:
    return http_request("GET", url, headers={"X-API-Key": ROOT_API_KEY})


def api_delete(url: str) -> tuple[int, str]:
    return http_request("DELETE", url, headers={"X-API-Key": ROOT_API_KEY})


def error_code_of(body: str) -> str:
    try:
        return str(json.loads(body).get("error", ""))
    except (TypeError, ValueError):
        return ""


def create_document(base_url: str, base_key: str, title: str,
                    content: str, extra: dict | None = None) -> tuple[int, str]:
    payload = {
        "title": title,
        "content": content,
        "collectionKey": base_key,
        "embeddingPolicy": "SKIP",
    }
    if extra:
        payload.update(extra)
    return json_request(
        "POST", f"{base_url}/api/v1/rag/documents", payload)


def collection_id_of(stack: LocalStack, collection_key: str) -> str:
    return stack.sql(
        "SELECT id FROM rag_collection "
        f"WHERE collection_key = '{collection_key}'")


def run_document_write_races(stack: LocalStack, base_url: str,
                             base_key: str) -> None:
    # ---------- 1a. 同一文档并发更新（CAS） ----------
    info("场景 1a：同一文档并发更新（expectedDocumentRevision CAS）")
    status, body = create_document(
        base_url, base_key, "Concurrent Update", "update-version-0")
    check(status == 200, f"更新基准文档创建成功（HTTP {status}）")
    if status != 200:
        return
    document_id = json.loads(body)["id"]
    revision = json.loads(body)["documentRevision"]

    def update(index: int) -> tuple[int, str]:
        return json_request(
            "PATCH", f"{base_url}/api/v1/rag/documents/{document_id}",
            {"expectedDocumentRevision": revision,
             "title": f"Concurrent Update {index}",
             "content": f"update-version-{index}",
             "embeddingPolicy": "SKIP"})

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(update, range(8)))
    statuses = [status_of(r) for r in results]
    check(all(code in (200, 409) for code in statuses),
          f"并发更新只出现 200/409（实际 {sorted(set(statuses))}）")
    successes = [index for index, (code, _) in enumerate(results)
                 if code == 200]
    check(len(successes) == 1,
          f"并发更新恰好成功一次（成功 {len(successes)}）")
    conflict_codes = {error_code_of(body) for code, body in results
                      if code == 409}
    check(conflict_codes <= {"DOCUMENT_REVISION_CONFLICT",
                             "CONCURRENT_MODIFICATION"},
          f"并发更新冲突码稳定（实际 {sorted(conflict_codes)}）")
    if successes:
        winning_index = successes[0]
        _, detail = api_get(f"{base_url}/api/v1/rag/documents/{document_id}")
        detail_json = json.loads(detail)
        new_revision = detail_json["documentRevision"]
        check(detail_json["content"] == f"update-version-{winning_index}",
              "最终内容等于唯一成功请求的内容（没有丢更新）")
        check(new_revision == revision + 1,
              f"revision 只前进一格（{revision} -> {new_revision}）")
        db_revision = stack.sql(
            "SELECT document_revision FROM rag_documents "
            f"WHERE id = {document_id}")
        check(db_revision == str(new_revision),
              f"数据库 revision 与响应一致（{db_revision}）")

    # ---------- 1b. 并发删除与读取交错 ----------
    info("场景 1b：删除与读取交错")
    status, body = create_document(
        base_url, base_key, "Delete Race", "delete-race-content")
    check(status == 200, f"删除基准文档创建成功（HTTP {status}）")
    if status != 200:
        return
    delete_id = json.loads(body)["id"]
    delete_revision = json.loads(body)["documentRevision"]

    def read_document(_: int) -> tuple[int, str]:
        return api_get(f"{base_url}/api/v1/rag/documents/{delete_id}")

    def delete_document(_: int) -> tuple[int, str]:
        return api_delete(
            f"{base_url}/api/v1/rag/documents/{delete_id}"
            f"?expectedDocumentRevision={delete_revision}")

    tasks = [lambda i=i: read_document(i) for i in range(10)]
    tasks.append(lambda: delete_document(0))
    with ThreadPoolExecutor(max_workers=len(tasks)) as pool:
        results = list(pool.map(lambda task: task(), tasks))
    read_codes = [status_of(r) for r in results[:-1]]
    delete_code = status_of(results[-1])
    check(all(code in (200, 404) for code in read_codes),
          f"删除期间的读取只出现 200/404（实际 {sorted(set(read_codes))}）")
    check(delete_code == 200,
          f"并发删除返回 200（实际 {delete_code}）")
    torn = [json.loads(body)["content"] for code, body in results[:-1]
            if code == 200]
    check(all(content == "delete-race-content" for content in torn),
          "删除期间的 200 读取没有撕裂内容")
    status, _ = api_get(f"{base_url}/api/v1/rag/documents/{delete_id}")
    check(status == 404, f"删除完成后读取返回 404（实际 {status}）")

    # ---------- 1c. 同一文档并发删除 ----------
    info("场景 1c：同一文档并发删除")
    status, body = create_document(
        base_url, base_key, "Double Delete", "double-delete-content")
    check(status == 200, f"并发删除基准文档创建成功（HTTP {status}）")
    if status != 200:
        return
    double_id = json.loads(body)["id"]
    double_revision = json.loads(body)["documentRevision"]

    def delete_again(_: int) -> tuple[int, str]:
        return api_delete(
            f"{base_url}/api/v1/rag/documents/{double_id}"
            f"?expectedDocumentRevision={double_revision}")

    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(delete_again, range(6)))
    statuses = [status_of(r) for r in results]
    check(all(code in (200, 404, 409) for code in statuses),
          f"并发删除只出现 200/404/409（实际 {sorted(set(statuses))}）")
    check(statuses.count(200) == 1,
          f"并发删除恰好成功一次（成功 {statuses.count(200)}）")
    rows = stack.sql(
        f"SELECT count(*) FROM rag_documents WHERE id = {double_id}")
    check(rows == "0", f"文档最终确实被删除（残留 {rows}）")

    # ---------- 1d. 同名文件并发上传（无幂等键） ----------
    info("场景 1d：同名文件并发上传（无 Idempotency-Key）")
    upload_content = (
        b"# Same Name Upload\n\nNo idempotency key is supplied.\n") * 4
    uploaded_ids = []

    def upload(_: int) -> tuple[int, str]:
        return multipart_request(
            f"{base_url}/api/v1/rag/documents/upload",
            "files", "same-name.md", upload_content,
            {"collectionKey": base_key})

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(upload, range(8)))
    check(all(status_of(r) == 200 for r in results),
          f"同名并发上传全部 200（实际 {sorted({status_of(r) for r in results})}）")
    for _, body in results:
        for item in json.loads(body).get("results", []):
            if item.get("documentId") is not None:
                uploaded_ids.append(item["documentId"])
    check(len(uploaded_ids) == 8 and len(set(uploaded_ids)) == 8,
          f"无幂等键时每次上传都是独立文档，且 8 次写入全部落库"
          f"（ids={len(uploaded_ids)}, distinct={len(set(uploaded_ids))}）")
    collection_id = collection_id_of(stack, base_key)
    id_list = ",".join(str(value) for value in sorted(set(uploaded_ids)))
    rows = stack.sql(
        "SELECT count(*) FROM rag_documents "
        f"WHERE collection_id = {collection_id} AND id IN ({id_list})")
    check(rows == "8",
          f"数据库与响应一致，没有丢失写入（实际 {rows}）")


def run_external_revision_races(stack: LocalStack, base_url: str,
                                base_key: str) -> None:
    info("场景 4：外部文档不同 revision 并发（CAS 收敛）")
    namespace = "sync-race"
    external_id = "rev-race-1"
    base_payload = {
        "collectionKey": base_key,
        "sourceNamespace": namespace,
        "externalId": external_id,
        "sourceRevision": "rev-1",
        "title": "External Rev Base",
        "content": "base-revision-content",
        "embed": False,
    }
    status, body = json_request(
        "POST", f"{base_url}/api/v1/rag/documents/upsert", base_payload)
    check(status == 200 and json.loads(body).get("action") == "CREATED",
          f"外部文档基准 revision 建立（HTTP {status}）")
    if status != 200:
        return

    candidates = {
        0: {"sourceRevision": "rev-2-a", "title": "Candidate A",
            "content": "candidate-a-content"},
        1: {"sourceRevision": "rev-2-b", "title": "Candidate B",
            "content": "candidate-b-content"},
    }

    def upsert(index: int) -> tuple[int, str]:
        payload = dict(base_payload)
        payload.update(candidates[index % 2])
        payload["expectedSourceRevision"] = "rev-1"
        return json_request(
            "POST", f"{base_url}/api/v1/rag/documents/upsert", payload)

    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(upsert, range(6)))
    statuses = [status_of(r) for r in results]
    check(all(code in (200, 409) for code in statuses),
          f"不同 revision 并发 upsert 只出现 200/409"
          f"（实际 {sorted(set(statuses))}）")
    winners = [index % 2 for index, (code, _) in enumerate(results)
               if code == 200]
    check(len(set(winners)) == 1,
          f"不同 revision 并发 upsert 只收敛到一个候选（候选 {sorted(set(winners))}）")
    # 同一目标 revision 的重复提交是幂等的（1 次 UPDATED + N 次 UNCHANGED），
    # 因此成功数量可以大于 1；关键是另一个候选的所有请求都被拒。
    if winners:
        losing_candidate = 1 - winners[0]
        rejected = [index % 2 for index, (code, _) in enumerate(results)
                    if code == 409]
        check(set(rejected) == {losing_candidate},
              f"另一个 revision 的请求全部被拒（被拒候选 {sorted(set(rejected))}）")
    conflict_codes = {error_code_of(body) for code, body in results
                      if code == 409}
    check(conflict_codes <= {"DOCUMENT_REVISION_CONFLICT",
                             "CONCURRENT_MODIFICATION"},
          f"不同 revision 冲突码稳定（实际 {sorted(conflict_codes)}）")
    if winners:
        winner = candidates[winners[0]]
        status, detail = api_get(
            f"{base_url}/api/v1/rag/documents/by-external-id"
            f"?collectionKey={base_key}&sourceNamespace={namespace}"
            f"&externalId={external_id}")
        detail_json = json.loads(detail)
        check(status == 200
              and detail_json.get("sourceRevision") == winner["sourceRevision"]
              and detail_json.get("content") == winner["content"],
              "最终状态与 CAS 获胜者完全一致（先提交者为准）")

        repeated = {
            "collectionKey": base_key,
            "sourceNamespace": namespace,
            "externalId": external_id,
            "sourceRevision": winner["sourceRevision"],
            "title": winner["title"],
            "content": winner["content"],
            "embed": False,
            "expectedSourceRevision": winner["sourceRevision"],
        }

        def replay(_: int) -> tuple[int, str]:
            return json_request(
                "POST", f"{base_url}/api/v1/rag/documents/upsert", repeated)

        with ThreadPoolExecutor(max_workers=8) as pool:
            replays = list(pool.map(replay, range(8)))
        repeated_actions = [json.loads(body).get("action")
                            for code, body in replays if code == 200]
        check(all(status_of(r) == 200 for r in replays)
              and all(action == "UNCHANGED" for action in repeated_actions),
              f"同 revision 并发重放稳定判定 UNCHANGED"
              f"（动作 {sorted(set(repeated_actions))}）")
        rows = stack.sql(
            "SELECT count(*) FROM rag_documents "
            "WHERE external_id = 'rev-race-1'"
            " AND source_namespace = 'sync-race'")
        check(rows == "1", f"外部身份仍只有一行（{rows}）")


def run_idempotency_key_isolation(base_url: str, base_key: str) -> None:
    info("场景 2：不同 Idempotency-Key 并发互不串扰")
    payloads = {
        "iso-key-a": {"title": "Iso A", "content": "isolation-content-a",
                      "collectionKey": base_key,
                      "embeddingPolicy": "SKIP"},
        "iso-key-b": {"title": "Iso B", "content": "isolation-content-b",
                      "collectionKey": base_key,
                      "embeddingPolicy": "SKIP"},
    }
    jobs = [("iso-key-a", index) for index in range(3)] + \
           [("iso-key-b", index) for index in range(3)]

    def create(job: tuple[str, int]) -> tuple[str, int, str]:
        key, _ = job
        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/documents",
            payloads[key], extra_headers={"Idempotency-Key": key})
        return key, status, body

    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(create, jobs))
    check(all(code in (200, 409) for _, code, _ in results),
          f"同键并发提交只出现 200/409"
          f"（实际 {sorted({code for _, code, _ in results})}）")
    ids_by_key = {"iso-key-a": set(), "iso-key-b": set()}
    for key, code, body in results:
        if code == 200:
            ids_by_key[key].add(json.loads(body)["id"])
    check(all(len(ids) == 1 for ids in ids_by_key.values()),
          f"每个键收敛到唯一结果（{ {k: sorted(v) for k, v in ids_by_key.items()} }）")
    conflict_codes = {error_code_of(body) for _, code, body in results
                      if code == 409}
    # 现有契约把"同键仍在执行"和"同键不同载荷"统一映射为
    # 409 DOCUMENT_REVISION_CONFLICT，调用方按同一冲突分支处理。
    check(conflict_codes <= {"DOCUMENT_REVISION_CONFLICT"},
          f"同键并发冲突码稳定（实际 {sorted(conflict_codes)}）")

    final_ids = {}
    for key, payload in payloads.items():
        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/documents", payload,
            extra_headers={"Idempotency-Key": key})
        check(status == 200, f"{key} 串行重放返回 200（实际 {status}）")
        if status == 200:
            parsed = json.loads(body)
            final_ids[key] = parsed["id"]
            check(parsed.get("status") == "REPLAYED",
                  f"{key} 串行重放识别为上轮结果（{parsed.get('status')}）")
    check(final_ids.get("iso-key-a") != final_ids.get("iso-key-b"),
          "不同幂等键产生独立副作用，没有串到同一文档")

    reused = dict(payloads["iso-key-a"])
    reused["content"] = "different-content-for-reuse"
    status, body = json_request(
        "POST", f"{base_url}/api/v1/rag/documents", reused,
        extra_headers={"Idempotency-Key": "iso-key-a"})
    check(status == 409
          and error_code_of(body) == "DOCUMENT_REVISION_CONFLICT",
          f"同键不同载荷返回约定的 409 冲突码（HTTP {status}, "
          f"code {error_code_of(body)}）")


def run_export_cursor_race(stack: LocalStack, base_url: str,
                           export_key: str) -> None:
    info("场景 5：流式 keyset 导出在并发写入下不漏不重")
    status, body = json_request(
        "POST", f"{base_url}/api/v1/rag/collections",
        {"collectionKey": export_key, "name": "Concurrent Export",
         "embeddingModel": "BAAI/bge-m3", "dimensions": 1024})
    check(status in (200, 201), f"导出 collection 创建成功（HTTP {status}）")
    if status not in (200, 201):
        return

    baseline = 500

    def seed(index: int) -> tuple[int, str]:
        return create_document(
            base_url, export_key, f"baseline-{index}",
            f"baseline-content-{index}")

    with ThreadPoolExecutor(max_workers=10) as pool:
        seeded = list(pool.map(seed, range(baseline)))
    check(all(status_of(r) == 200 for r in seeded),
          f"{baseline} 条基线文档全部创建成功"
          f"（实际 {sorted({status_of(r) for r in seeded})}）")
    baseline_titles = {f"baseline-{index}" for index in range(baseline)}

    inserted_titles = {f"concurrent-{index}" for index in range(150)}

    def insert(index: int) -> tuple[int, str]:
        return create_document(
            base_url, export_key, f"concurrent-{index}",
            f"concurrent-content-{index}")

    def export(_: int) -> tuple[int, dict[str, str], str]:
        return http_request_full(
            "GET",
            f"{base_url}/api/v1/rag/collections/by-key/export"
            f"?collectionKey={export_key}",
            headers={"X-API-Key": ROOT_API_KEY}, timeout=120)

    with ThreadPoolExecutor(max_workers=14) as pool:
        insert_futures = [pool.submit(insert, index)
                          for index in range(150)]
        export_futures = [pool.submit(export, index) for index in range(8)]
        exports = [future.result() for future in export_futures]
        inserted = [future.result() for future in insert_futures]

    check(all(status_of(r) == 200 for r in inserted),
          f"并发插入全部成功（实际 {sorted({status_of(r) for r in inserted})}）")
    for index, (code, headers, body) in enumerate(exports):
        check(code == 200, f"导出 #{index} 返回 200（实际 {code}）")
        if code != 200:
            continue
        documents = json.loads(body)["documents"]
        titles = [document["title"] for document in documents]
        snapshot = int(headers.get("x-rag-export-documents", "-1"))
        check(len(titles) == snapshot,
              f"导出 #{index} 行数与快照计数一致"
              f"（rows={len(titles)}, snapshot={snapshot}）")
        check(len(titles) == len(set(titles)),
              f"导出 #{index} 没有重复文档（{len(titles)} 行）")
        check(baseline_titles <= set(titles),
              f"导出 #{index} 未漏任何基线文档"
              f"（缺 {len(baseline_titles - set(titles))}）")
        check(set(titles) <= baseline_titles | inserted_titles,
              f"导出 #{index} 只包含快照内文档（无幽灵行）")

    final_count = stack.sql(
        "SELECT count(*) FROM rag_documents d JOIN rag_collection c "
        f"ON c.id = d.collection_id WHERE c.collection_key = '{export_key}'")
    check(final_count == str(baseline + 150),
          f"并发写入后数据库文档数完整（实际 {final_count}）")


def _trigger_async_embed(base_url: str, document_id: int) -> tuple[int, str]:
    return json_request(
        "POST",
        f"{base_url}/api/v1/rag/documents/{document_id}/embed"
        "?force=true&embeddingPolicy=ASYNC", {}, timeout=120)


def _job_status(stack: LocalStack, document_id: int) -> str:
    return stack.sql(
        "SELECT status FROM rag_embedding_jobs "
        f"WHERE document_id = {document_id} ORDER BY id DESC LIMIT 1")


def _wait_for_job_status(stack: LocalStack, document_id: int,
                         expected: str, timeout: float = 60.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if _job_status(stack, document_id) == expected:
            return True
        time.sleep(0.1)
    return False


def run_async_reindex_race(stack: LocalStack, base_url: str,
                           base_key: str) -> None:
    info("场景 3：并发 ASYNC 重索引只产生一个 job 与一套 chunk")
    # 单 worker（应用启动时配置）先被占用，目标文档的 job 在并发触发期间
    # 保持在 QUEUED，能让 8 个请求真正重叠，而不是靠 sleep 碰运气。
    blocker_content = "\n\n".join(
        f"Async blocker section {index}: hold the only job worker."
        for index in range(120))
    status, body = create_document(
        base_url, base_key, "Async Blocker", blocker_content)
    check(status == 200, f"占位文档创建成功（HTTP {status}）")
    if status != 200:
        return
    blocker_id = json.loads(body)["id"]

    stack.stop_mock()
    stack.start_mock(delay_ms=2500)
    stack.start_app(
        extra_env={
            "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "32",
            "RAG_EMBEDDING_JOBS_WORKER_CONCURRENCY": "1",
        })
    # 应用重启后连接仍可用；先确认健康。
    probe_status, _ = api_get(f"{base_url}/api/v1/rag/collections")
    check(probe_status == 200, f"应用重启后可用（HTTP {probe_status}）")

    status, body = _trigger_async_embed(base_url, blocker_id)
    check(status in (200, 202),
          f"占位 job 入队成功（HTTP {status}）")
    check(_wait_for_job_status(stack, blocker_id, "RUNNING"),
          "占位 job 进入 RUNNING，单 worker 已占用")

    content = "\n\n".join(
        f"Async reindex section {index}: concurrency marker for job coalescing."
        for index in range(80))
    status, body = create_document(
        base_url, base_key, "Async Concurrent Reindex", content)
    check(status == 200, f"异步重索引文档创建成功（HTTP {status}）")
    if status != 200:
        return
    document_id = json.loads(body)["id"]

    def trigger(_: int) -> tuple[int, str]:
        return _trigger_async_embed(base_url, document_id)

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(trigger, range(8)))
    statuses = [status_of(r) for r in results]
    check(all(code in (200, 202, 409) for code in statuses),
          f"并发 ASYNC 重索引只出现 200/202/409"
          f"（实际 {sorted(set(statuses))}）")

    job_rows = int(stack.sql(
        "SELECT count(*) FROM rag_embedding_jobs "
        f"WHERE document_id = {document_id}"))
    check(job_rows == 1,
          f"并发重索引只产生一个 job（实际 {job_rows}）")
    generations = stack.sql(
        "SELECT count(*) - count(DISTINCT request_generation) "
        "FROM rag_embedding_jobs "
        f"WHERE document_id = {document_id}")
    check(generations == "0", f"没有重复 generation（{generations}）")

    deadline = time.time() + 180
    terminal = None
    while time.time() < deadline:
        terminal = _job_status(stack, document_id)
        if terminal in ("SUCCEEDED", "FAILED", "STALE", "CANCELLED"):
            break
        time.sleep(0.2)
    check(terminal == "SUCCEEDED",
          f"重索引 job 最终成功（实际 {terminal}）")

    embedding_rows = stack.sql(
        f"SELECT count(*) FROM rag_embeddings WHERE document_id = {document_id}")
    distinct_chunks = stack.sql(
        "SELECT count(DISTINCT chunk_index) FROM rag_embeddings "
        f"WHERE document_id = {document_id}")
    check(embedding_rows == distinct_chunks,
          f"没有重复 chunk（rows={embedding_rows}, "
          f"distinct={distinct_chunks}）")
    state_chunks = stack.sql(
        "SELECT chunk_count FROM rag_document_embedding_state "
        f"WHERE document_id = {document_id} "
        "AND embedding_profile_id = (SELECT id FROM rag_embedding_profiles "
        "ORDER BY id LIMIT 1)")
    check(state_chunks == embedding_rows,
          f"状态行 chunk 数与向量行数一致（{state_chunks} vs {embedding_rows}）")
    active_jobs = stack.sql(
        "SELECT count(*) FROM rag_embedding_jobs "
        f"WHERE document_id = {document_id} "
        "AND status IN ('QUEUED', 'RUNNING')")
    check(active_jobs == "0", f"没有残留活动 job（{active_jobs}）")


def run_provisioning_idempotency_scenarios(stack: LocalStack, base_url: str, base_key: str) -> None:
    """登记表里标为 header-key 的写端点：真实 PostgreSQL 并发重复提交。

    这些断言对应 scripts/testdata/write-endpoints.json 的 header-key 条目：
    同一个 Idempotency-Key 的并发重复请求必须收敛到同一资源，而不是两行。
    """
    collection_id = stack.sql(
        f"SELECT id FROM rag_collection WHERE collection_key = '{base_key}'")

    # ---------- 6. 同一 Idempotency-Key 并发创建 collection ----------
    info("场景 6：同一 Idempotency-Key 并发创建 collection")
    collection_key = "conc-idem-collection"

    def create_with_key(_: int) -> tuple[int, str]:
        return json_request(
            "POST", f"{base_url}/api/v1/rag/collections",
            {"collectionKey": collection_key, "name": "Idempotent Collection",
             "embeddingModel": "BAAI/bge-m3", "dimensions": 1024},
            extra_headers={"Idempotency-Key": "conc-collection-key"})

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(create_with_key, range(8)))
    statuses = [status_of(r) for r in results]
    check(all(code in (200, 201) for code in statuses),
          f"同 key 并发建 collection 全部重放 2xx（实际 {sorted(set(statuses))}）")
    rows = stack.sql(
        f"SELECT count(*) FROM rag_collection WHERE collection_key = '{collection_key}'")
    check(rows == "1", f"同 key 并发建 collection 只有一行（实际 {rows}）")

    # ---------- 7. 同一 Idempotency-Key 并发创建 document ----------
    info("场景 7：同一 Idempotency-Key 并发创建 document")
    title = "Concurrent Idempotent Document"
    content = ("Idempotency key race: eight concurrent creates must converge "
               "on one document row.\n\n") * 6

    def create_document_with_key(_: int) -> tuple[int, str]:
        return json_request(
            "POST", f"{base_url}/api/v1/rag/documents",
            {"title": title, "content": content, "collectionKey": base_key},
            extra_headers={"Idempotency-Key": "conc-document-key"})

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(create_document_with_key, range(8)))
    statuses = [status_of(r) for r in results]
    # 文档创建有两层并发保护：Collection 写入位用版本 CAS，同 key 用幂等表。
    # 并发重复提交因此只会出现两种 409：CAS 落败（CONCURRENT_MODIFICATION）
    # 或同 key 首个仍在处理（DOCUMENT_REVISION_CONFLICT）；两者都是“用同一
    # key 重试即可重放”，不会多建记录。下面的重试把这一点直接验证一遍。
    conflict_codes = set()
    for status, body in results:
        if status not in (200, 201):
            payload = json.loads(body)
            conflict_codes.add(payload.get("error"))
    check(all(code in (200, 201, 409) for code in statuses),
          f"同 key 并发建 document 只出现 2xx/409（实际 {sorted(set(statuses))}）")
    check(conflict_codes <= {"CONCURRENT_MODIFICATION",
                             "DOCUMENT_REVISION_CONFLICT"},
          f"409 语义在契约内（实际 {sorted(conflict_codes)}）")
    check(any(code in (200, 201) for code in statuses),
          f"同 key 并发建 document 至少一个成功（实际 {sorted(set(statuses))}）")
    retried = [json_request(
        "POST", f"{base_url}/api/v1/rag/documents",
        {"title": title, "content": content, "collectionKey": base_key},
        extra_headers={"Idempotency-Key": "conc-document-key"})
        for status in statuses if status not in (200, 201)]
    check(all(status_of(r) in (200, 201) for r in retried),
          f"同一 key 重试后全部重放 2xx（实际 {sorted({status_of(r) for r in retried})}）")
    ids = set()
    for status, body in results:
        if status in (200, 201):
            ids.add(json.loads(body).get("id"))
    for status, body in retried:
        if status in (200, 201):
            ids.add(json.loads(body).get("id"))
    check(len(ids) == 1, f"同 key 并发建 document 收敛到同一个 id（实际 {sorted(ids)}）")
    rows = stack.sql(
        f"SELECT count(*) FROM rag_documents WHERE collection_id = {collection_id} "
        f"AND title = '{title}'")
    check(rows == "1", f"同 key 并发建 document 只有一行（实际 {rows}）")
    embedding_rows = stack.sql(
        "SELECT count(*) FROM rag_embeddings e JOIN rag_documents d "
        f"ON d.id = e.document_id WHERE d.collection_id = {collection_id} "
        f"AND d.title = '{title}'")
    check(embedding_rows == "0",
          f"并发重复提交没有触发重复嵌入（实际 {embedding_rows} 行）")
    operation_status = stack.sql(
        "SELECT status FROM rag_document_idempotency_operations "
        "WHERE operation_type = 'LOCAL_CREATE' "
        "AND idempotency_key_hash = encode(sha256('conc-document-key'::bytea), 'hex')")
    operation_count = stack.sql(
        "SELECT count(*) FROM rag_document_idempotency_operations "
        "WHERE operation_type = 'LOCAL_CREATE' "
        "AND idempotency_key_hash = encode(sha256('conc-document-key'::bytea), 'hex')")
    check(operation_status == "SUCCEEDED" and operation_count == "1",
          f"同 key 只留一条 SUCCEEDED 幂等记录"
          f"（实际 status={operation_status}, count={operation_count}）")

    # ---------- 8. 同一外部标识并发 JSON record upsert ----------
    info("场景 8：同一外部标识并发 JSON record upsert")
    payload = {
        "collectionKey": base_key,
        "sourceNamespace": "jsonrace",
        "externalId": "json-race-1",
        "sourceRevision": "rev-1",
        "title": "JSON Record Race",
        "retrievalText": "Structured record used for the JSON upsert concurrency race.",
        "jsonbPayload": {"race": "json", "revision": 1},
        "embed": False,
    }

    def json_upsert(_: int) -> tuple[int, str]:
        return json_request(
            "POST", f"{base_url}/api/v1/rag/json-records/upsert", payload)

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(json_upsert, range(8)))
    statuses = [status_of(r) for r in results]
    check(all(code in (200, 409) for code in statuses),
          f"JSON record 并发 upsert 只出现 200/409（实际 {sorted(set(statuses))}）")
    rows = stack.sql(
        "SELECT count(*) FROM rag_documents "
        f"WHERE collection_id = {collection_id} AND source_namespace = 'jsonrace' "
        "AND external_id = 'json-race-1'")
    check(rows == "1", f"JSON 外部身份只有一行（实际 {rows}）")
    revisions = stack.sql(
        "SELECT count(*) FROM rag_document_versions v JOIN rag_documents d "
        "ON d.id = v.document_id WHERE d.external_id = 'json-race-1'")
    check(revisions == "1", f"JSON 记录只产生一个历史版本（实际 {revisions}）")


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
