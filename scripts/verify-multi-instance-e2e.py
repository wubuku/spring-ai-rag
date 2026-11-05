#!/usr/bin/env python3
"""多实例（双进程 A/B，不同端口）真实 PostgreSQL + pgvector 验收。

覆盖：
1. SSE 跨实例续传：keyed turn 在 A 上开始、中途断开，带 Last-Event-ID 到 B 续上；
2. 幂等键跨实例：同一 Idempotency-Key 并发打到 A/B，只产生一次副作用；
3. lease/job 跨实例：两个 worker 同批消费不双写；SIGKILL 一个实例后另一实例接管；
4. 本地缓存/权限：A 写、B 立刻可见；A 吊销凭证后 B 立即拒绝；
5. 限流配额：PostgreSQL 共享桶，并发打到两个实例的总量不会被副本数放大；
6. Flyway 并发启动：两实例同时启动，迁移只应用一次且 history 连续。

脚本使用临时库前缀 rag_multi_instance_*，默认结束即清理。`--keep` 保留现场。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from lib.rag_local_stack import (  # noqa: E402
    LocalStack,
    ROOT_API_KEY,
    http_request,
    json_request,
)

APP_A_PORT = int(os.environ.get("RAG_MULTI_APP_A_PORT", "18086"))
APP_B_PORT = int(os.environ.get("RAG_MULTI_APP_B_PORT", "18087"))
MOCK_PORT = int(os.environ.get("RAG_MULTI_MOCK_PORT", "18085"))
COMMON_ENV = {
    "RAG_MULTI_INSTANCE_ENABLED": "true",
    # 共享配额：全局默认放得很宽，具体测试用带 requestsPerMinute 的 key。
    "RAG_RATE_LIMIT_ENABLED": "true",
    "RAG_RATE_LIMIT_BACKEND": "postgresql",
    "RAG_RATE_LIMIT_STRATEGY": "principal",
    "RAG_RATE_LIMIT_REQUESTS_PER_MINUTE": "100000",
    # 缩短恢复扫描与 lease，让真实进程测试在可接受时间内收敛。
    "RAG_EMBEDDING_JOBS_POLL_INTERVAL_MS": "10000",
    "RAG_EMBEDDING_JOBS_LEASE_SECONDS": "30",
    # 单条领取 + 低并发，让两个实例都有机会参与同一批任务；
    # 批量测试要证明“两个 worker 同时消费”，不能只让快的那个包办。
    "RAG_EMBEDDING_JOBS_CLAIM_BATCH_SIZE": "1",
    "RAG_EMBEDDING_JOBS_WORKER_CONCURRENCY": "1",
}

FAILURES: list[str] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    message = f"{'PASS' if ok else 'FAIL'} {label}"
    if detail:
        message += f"：{detail}"
    print(message, flush=True)
    if not ok:
        FAILURES.append(label)


def sha256_hex(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def wait_until(predicate, timeout: float, label: str) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(0.25)
    print(f"[WARN] 等待超时：{label}", file=sys.stderr, flush=True)
    return False


def sql_int(stack: LocalStack, statement: str) -> int:
    raw = stack.sql(statement)
    return int(raw) if raw else 0


# ---------- SSE ----------


def stream_chat(base: str, session_id: str, *,
                idempotency_key: str | None = None,
                last_event_id: str | None = None,
                disconnect_after_content: bool = False,
                timeout: float = 120.0) -> dict:
    payload = json.dumps({
        "message": "Reply READY.",
        "mode": "PLAIN",
        "sessionId": session_id,
    }).encode()
    request = urllib.request.Request(
        f"{base}/api/v1/rag/chat/stream", data=payload, method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            "X-API-Key": ROOT_API_KEY,
        })
    if idempotency_key:
        request.add_header("Idempotency-Key", idempotency_key)
    if last_event_id is not None:
        request.add_header("Last-Event-ID", last_event_id)
    started = time.time()
    try:
        response = urllib.request.urlopen(request, timeout=timeout)
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", "replace")
        return {"status": error.code, "headers": {},
                "events": [], "ids": [], "body": body,
                "elapsed_ms": int((time.time() - started) * 1000)}
    except Exception as error:  # noqa: BLE001 - 网络失败同样要作为证据返回
        return {"status": 0, "headers": {}, "events": [], "ids": [],
                "body": str(error),
                "elapsed_ms": int((time.time() - started) * 1000)}

    events: list[str] = []
    ids: list[str] = []
    body_tail: list[str] = []
    headers = {k.lower(): v for k, v in response.headers.items()}
    try:
        current = None
        pending_disconnect = False
        for raw in response:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if line.startswith("id:"):
                ids.append(line[len("id:"):].strip())
                if pending_disconnect:
                    # SSE 字段顺序是 event → id → data；读完 id 才能断开，
                    # 否则客户端拿不到 Last-Event-ID 游标。
                    pending_disconnect = False
                    break
            if line.startswith("event:"):
                current = line[len("event:"):].strip()
                events.append(current)
                if current in ("done", "error"):
                    break
                if disconnect_after_content and current == "content":
                    pending_disconnect = True
            if line.startswith("data:"):
                body_tail.append(line)
                if len(body_tail) > 6:
                    body_tail.pop(0)
                if pending_disconnect:
                    pending_disconnect = False
                    break
    finally:
        try:
            response.close()
        except Exception:  # noqa: BLE001 - 关闭失败不改变断言
            pass
    return {
        "status": response.status,
        "headers": headers,
        "events": events,
        "ids": ids,
        "body": "\n".join(body_tail),
        "elapsed_ms": int((time.time() - started) * 1000),
    }


def sse_cross_instance(stack: LocalStack, base_a: str, base_b: str) -> dict:
    stamp = int(time.time() * 1000)
    key = f"mi-sse-{stamp}"
    session = f"mi-sse-session-{stamp}"
    key_hash = sha256_hex(key)

    started_on_a = stream_chat(base_a, session, idempotency_key=key,
                               disconnect_after_content=True)
    operation_row = (
        "SELECT status FROM rag_chat_turn_operations "
        f"WHERE idempotency_key_sha256='{key_hash}'")

    def finished() -> bool:
        return stack.sql(operation_row) == "SUCCEEDED"

    completed = wait_until(finished, 90, "A 实例 keyed turn 完成落库")
    status_in_db = stack.sql(operation_row)

    last_id = started_on_a["ids"][0] if started_on_a["ids"] else None
    resumed = stream_chat(base_b, session, idempotency_key=key,
                          last_event_id=last_id) if last_id else {
        "status": 0, "events": [], "ids": [], "headers": {}, "body": "no id"}
    full = stream_chat(base_b, session, idempotency_key=key,
                       last_event_id="not-a-number")

    failures = []
    if started_on_a["status"] != 200:
        failures.append(f"A 首流 HTTP {started_on_a['status']}")
    if not completed:
        failures.append(f"A keyed turn 未完成（DB status={status_in_db}）")
    if last_id is None:
        failures.append("A 首流没有 id 事件，无法续传")
    if resumed["status"] != 200:
        failures.append(f"B 续传 HTTP {resumed['status']}")
    if resumed["headers"].get("x-rag-idempotent-replay") != "true":
        failures.append("B 续传未标记为幂等重放")
    if "content" in resumed["events"]:
        failures.append("B 续传重复发送已消费的 content")
    if "done" not in resumed["events"]:
        failures.append("B 续传没有补发 done")
    if resumed["ids"] and any(int(i) <= int(last_id) for i in resumed["ids"]):
        failures.append("B 续传 id 未推进到 Last-Event-ID 之后")
    if full["status"] != 200 or "content" not in full["events"]:
        failures.append("非法 Last-Event-ID 未降级为整轮重放")

    if last_id and resumed["status"] == 200:
        expected_ids = full["ids"][1:]
        expected_events = full["events"][1:]
        if resumed["ids"] != expected_ids:
            failures.append(
                f"续传 id 序列不等于完整回放尾部（{resumed['ids']} != {expected_ids}）")
        if resumed["events"] != expected_events:
            failures.append(
                f"续传事件不等于完整回放尾部（{resumed['events']} != {expected_events}）")
        if started_on_a["ids"] + resumed["ids"] != full["ids"]:
            failures.append("A 首段 + B 续传没有精确拼成完整事件序列")
        if len(set(full["ids"])) != len(full["ids"]):
            failures.append("完整回放 id 有重复")

    # 无 Idempotency-Key 的实时流：文档限定为不可续传。这里验证它是
    # “确定行为”（忽略 Last-Event-ID、整轮新生成），而不是静默丢帧。
    unkeyed_session = session + "-unkeyed"
    unkeyed_first = stream_chat(base_a, unkeyed_session,
                                disconnect_after_content=True)
    lease_rows = (
        "SELECT count(*) FROM rag_chat_session_lease "
        f"WHERE session_id='{unkeyed_session}'")
    wait_until(lambda: stack.sql(lease_rows) == "0", 30,
               "无 key 流断开后释放会话 lease")
    unkeyed_retry_id = (unkeyed_first["ids"][0]
                        if unkeyed_first["ids"] else "1")
    unkeyed_retry = stream_chat(base_b, unkeyed_session,
                                last_event_id=unkeyed_retry_id)
    unkeyed_failures = []
    if unkeyed_retry["status"] != 200:
        unkeyed_failures.append(
            f"无 key 流在 B 未整轮重放（HTTP {unkeyed_retry['status']}）")
    if "content" not in unkeyed_retry["events"]:
        unkeyed_failures.append("无 key 流在 B 没有产出完整 content")
    if "done" not in unkeyed_retry["events"]:
        unkeyed_failures.append("无 key 流在 B 没有 done")
    if unkeyed_retry["headers"].get("x-rag-idempotent-replay") == "true":
        unkeyed_failures.append("无 key 流被误标为幂等续传")

    check(not failures, "SSE 跨实例续传（keyed）：A 断开后 B 带 Last-Event-ID 续上且不重不漏",
          "；".join(failures) or
          f"A 首流 HTTP {started_on_a['status']}，首个 id={last_id}，"
          f"B 补发 {resumed['events']}，DB={status_in_db}")
    check(not unkeyed_failures,
          "SSE 无 key 实时流：B 忽略 Last-Event-ID 整轮新生成（文档限定的确定行为）",
          "；".join(unkeyed_failures) or
          f"B HTTP {unkeyed_retry['status']}，事件 {unkeyed_retry['events']}")
    return {"failures": failures + unkeyed_failures, "resumed": resumed,
            "full": full, "last_id": last_id, "status_in_db": status_in_db,
            "unkeyed": unkeyed_retry}


# ---------- 幂等 ----------


def concurrent_pair(first, second):
    barrier = threading.Barrier(2)
    results: list[object] = [None, None]

    def run(index: int, function) -> None:
        barrier.wait()
        results[index] = function()

    threads = [
        threading.Thread(target=run, args=(0, first), daemon=True),
        threading.Thread(target=run, args=(1, second), daemon=True),
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return results


def idempotency_across_instances(stack: LocalStack, base_a: str, base_b: str) -> dict:
    stamp = int(time.time() * 1000)
    key = f"mi-idem-{stamp}"
    title = f"mi-idem-{stamp}"
    payload = {
        "collectionKey": "mi-base",
        "title": title,
        "content": f"cross instance idempotency {stamp} " * 30,
        "embeddingPolicy": "SKIP",
    }

    def call(base: str):
        return json_request("POST", f"{base}/api/v1/rag/documents", payload,
                            extra_headers={"Idempotency-Key": key})

    (status_a, body_a), (status_b, body_b) = concurrent_pair(
        lambda: call(base_a), lambda: call(base_b))

    parsed = []
    for status, body in ((status_a, body_a), (status_b, body_b)):
        try:
            parsed.append((status, json.loads(body)))
        except json.JSONDecodeError:
            parsed.append((status, {"status": "UNPARSEABLE", "raw": body[:200]}))

    actions = [item[1].get("status") for item in parsed]
    created = [item for item in parsed if item[1].get("status") == "CREATED"]
    loser_ok = [item for item in parsed
                if item[1].get("status") == "REPLAYED" or item[0] == 409]
    document_ids = {item[1].get("id") for item in created}
    key_hash = sha256_hex(key)

    rows = sql_int(
        stack,
        "SELECT count(*) FROM rag_document_idempotency_operations "
        f"WHERE idempotency_key_hash='{key_hash}'")
    documents = sql_int(
        stack, f"SELECT count(*) FROM rag_documents WHERE title='{title}'")
    operations = sql_int(
        stack,
        "SELECT count(*) FROM rag_document_idempotency_operations "
        f"WHERE idempotency_key_hash='{key_hash}' AND status='SUCCEEDED'")
    unique_indexes = sql_int(
        stack,
        "SELECT count(*) FROM pg_indexes "
        "WHERE tablename='rag_document_idempotency_operations' "
        "AND indexdef LIKE '%UNIQUE%' AND indexdef LIKE '%idempotency_key_hash%'")

    third_status, third_body = json_request(
        "POST", f"{base_b}/api/v1/rag/documents", payload,
        extra_headers={"Idempotency-Key": key})
    third = json.loads(third_body) if third_body else {}

    failures = []
    if not created:
        failures.append(f"没有任何一侧返回 CREATED（actions={actions}）")
    if len(created) != 1:
        failures.append(f"同一幂等键产生了多次 CREATED（actions={actions}）")
    if len(loser_ok) < 1:
        failures.append(f"另一侧既不是 REPLAYED 也不是 409（actions={actions}）")
    if documents != 1:
        failures.append(f"库中同名文档 {documents} 篇，应只有 1 篇")
    if rows != 1 or operations != 1:
        failures.append(f"幂等操作记录 {rows} 条（SUCCEEDED {operations}），应只有 1 条")
    if unique_indexes < 1:
        failures.append("库中缺少覆盖 idempotency_key_hash 的唯一索引")
    if third_status != 200 or third.get("status") != "REPLAYED":
        failures.append(f"后续同键请求未 REPLAYED（HTTP {third_status}）")
    if third.get("id") not in document_ids:
        failures.append(f"重放文档 id {third.get('id')} 与首次不同")

    check(not failures, "幂等键跨实例：同一 Idempotency-Key 并发只产生一次副作用",
          "；".join(failures) or
          f"A/B actions={actions}，document={list(document_ids)}，凭证 1 条")
    return {"actions": actions, "document_ids": document_ids,
            "rows": rows, "third": third}


# ---------- 缓存与权限 ----------


def create_collection(base: str, key: str, name: str) -> None:
    status, body = json_request(
        "POST", f"{base}/api/v1/rag/collections",
        {"collectionKey": key, "name": name})
    if status not in (200, 201, 409):
        raise RuntimeError(f"创建 Collection 失败 HTTP {status}: {body[:300]}")


def create_doc(base: str, title: str, content: str, **extra) -> dict:
    payload = {
        "collectionKey": extra.get("collectionKey", "mi-base"),
        "title": title,
        "content": content,
        "embeddingPolicy": extra.get("embeddingPolicy", "SKIP"),
    }
    status, body = json_request("POST", f"{base}/api/v1/rag/documents", payload)
    if status != 200:
        raise RuntimeError(f"创建文档失败 HTTP {status}: {body[:300]}")
    return json.loads(body)


def create_api_key(base: str, name: str, *, quota: int | None = None,
                   capabilities: list[str] | None = None) -> dict:
    payload = {"name": name, "expiresAt": "2030-01-01T00:00:00"}
    if quota is not None:
        payload["requestsPerMinute"] = quota
    if capabilities:
        payload["capabilities"] = capabilities
    status, body = json_request(
        "POST", f"{base}/api/v1/rag/api-keys", payload)
    if status not in (200, 201):
        raise RuntimeError(f"创建 API Key 失败 HTTP {status}: {body[:300]}")
    return json.loads(body)


def cache_and_permissions(base_a: str, base_b: str) -> dict:
    stamp = int(time.time() * 1000)
    doc_title = f"mi-cache-{stamp}"
    failures: list[str] = []

    # Collection 由 A 创建，B 立即读取。
    status, body = json_request(
        "GET", f"{base_b}/api/v1/rag/collections/by-key?collectionKey=mi-base", {})
    if status != 200:
        failures.append(f"B 读不到 A 创建的 Collection（HTTP {status}）")

    created = create_doc(base_a, doc_title, f"cache ${stamp} " * 20)
    document_id = int(created["id"])
    revision = int(created.get("documentRevision") or 1)

    status, body = json_request(
        "GET", f"{base_b}/api/v1/rag/documents/{document_id}", {})
    if status != 200:
        failures.append(f"B 读不到 A 创建的文档（HTTP {status}）")
    else:
        detail = json.loads(body)
        if detail.get("title") != doc_title:
            failures.append("B 读到旧标题")

    new_title = f"{doc_title}-rev2"
    status, body = json_request(
        "PATCH", f"{base_a}/api/v1/rag/documents/{document_id}",
        {"expectedDocumentRevision": revision, "title": new_title,
         "embeddingPolicy": "SKIP"})
    if status != 200:
        failures.append(f"A 更新文档失败 HTTP {status}")
    else:
        updated = json.loads(body)
        new_revision = int(updated["documentRevision"])
        status_b, body_b = json_request(
            "GET", f"{base_b}/api/v1/rag/documents/{document_id}", {})
        if status_b != 200:
            failures.append(f"B 读更新后文档失败 HTTP {status_b}")
        else:
            detail_b = json.loads(body_b)
            if detail_b.get("title") != new_title:
                failures.append("B 仍读到旧标题（本地缓存未失效）")
            if int(detail_b.get("documentRevision") or 0) != new_revision:
                failures.append(
                    f"B revision={detail_b.get('documentRevision')} != {new_revision}")

    # 权限变更：A 创建的 key 在 B 可用；A 吊销后 B 立即拒绝。
    key = create_api_key(base_a, f"mi-cache-key-{stamp}",
                         capabilities=["RAG_READ"])
    raw = key["rawKey"]
    key_id = key["keyId"]
    status_b, _ = http_request(
        "GET", f"{base_b}/api/v1/rag/collections/by-key?collectionKey=mi-base",
        headers={"X-API-Key": raw})
    if status_b != 200:
        failures.append(f"B 未接受 A 创建的 key（HTTP {status_b}）")

    status_a, _ = http_request(
        "DELETE", f"{base_a}/api/v1/rag/api-keys/{key_id}",
        headers={"X-API-Key": ROOT_API_KEY})
    if status_a not in (204, 200):
        failures.append(f"A 吊销 key 失败 HTTP {status_a}")

    status_b_revoked, _ = http_request(
        "GET", f"{base_b}/api/v1/rag/collections/by-key?collectionKey=mi-base",
        headers={"X-API-Key": raw})
    status_a_revoked, _ = http_request(
        "GET", f"{base_a}/api/v1/rag/collections/by-key?collectionKey=mi-base",
        headers={"X-API-Key": raw})
    if status_b_revoked not in (401, 403):
        failures.append(f"B 仍接受已吊销 key（HTTP {status_b_revoked}）")
    if status_a_revoked not in (401, 403):
        failures.append(f"A 仍接受已吊销 key（HTTP {status_a_revoked}）")

    check(not failures, "缓存与权限跨实例：A 写 B 立即可见，吊销后两实例立即拒绝",
          "；".join(failures) or
          f"文档 {document_id} revision={revision}→{new_revision}，"
          f"吊销后 A/B={status_a_revoked}/{status_b_revoked}")
    return {"failures": failures, "documentId": document_id,
            "newRevision": new_revision}


# ---------- 限流 ----------


def align_to_utc_minute() -> None:
    now = datetime.now(timezone.utc)
    if now.second > 40:
        time.sleep(61 - now.second)


def rate_limit_across_instances(stack: LocalStack, base_a: str,
                                base_b: str) -> dict:
    stamp = int(time.time() * 1000)
    limit = 6
    key = create_api_key(base_a, f"mi-rate-{stamp}", quota=limit,
                         capabilities=["RAG_READ"])
    raw = key["rawKey"]
    principal = key["principalId"]
    url_path = "/api/v1/rag/collections/by-key?collectionKey=mi-base"

    align_to_utc_minute()
    # 5 个请求打 A、5 个打 B，同步起跑；共享桶应只放行 limit 次。
    targets = [f"{base_a}{url_path}"] * 5 + [f"{base_b}{url_path}"] * 5
    barrier = threading.Barrier(len(targets))
    results: list[tuple[int, str]] = [(-1, "")] * len(targets)

    def run(index: int, url: str) -> None:
        barrier.wait()
        results[index] = http_request(
            "GET", url, headers={"X-API-Key": raw}, timeout=30)

    with ThreadPoolExecutor(max_workers=len(targets)) as pool:
        futures = [pool.submit(run, i, url) for i, url in enumerate(targets)]
        for future in futures:
            future.result()

    statuses = [status for status, _ in results]
    successes = statuses.count(200)
    limited = statuses.count(429)
    per_instance = {
        "A": [status for status, _ in results[:5]],
        "B": [status for status, _ in results[5:]],
    }

    bucket_max = sql_int(
        stack,
        "SELECT COALESCE(MAX(request_count), 0) FROM rag_api_rate_limit_bucket "
        f"WHERE principal_id='{principal}'")
    bucket_rows = sql_int(
        stack,
        "SELECT count(*) FROM rag_api_rate_limit_bucket "
        f"WHERE principal_id='{principal}'")

    failures = []
    if successes != limit:
        failures.append(
            f"两实例合计放行 {successes} 次，期望共享配额 {limit} 次")
    if limited != len(targets) - limit:
        failures.append(f"429 数量 {limited}，期望 {len(targets) - limit}")
    if any(status not in (200, 429) for status in statuses):
        failures.append(f"出现非预期状态码：{statuses}")
    if bucket_max != limit:
        failures.append(f"共享桶 request_count={bucket_max}，期望 {limit}")
    if bucket_rows != 1:
        failures.append(f"当前窗口桶行数 {bucket_rows}，期望 1")

    check(not failures, "限流配额跨实例：PostgreSQL 共享桶不被副本数放大",
          "；".join(failures) or
          f"10 并发 → 放行 {successes} / 429 {limited}，"
          f"A={per_instance['A']} B={per_instance['B']}")
    return {"successes": successes, "limited": limited,
            "perInstance": per_instance, "bucket": bucket_max}


# ---------- 任务 lease ----------


def job_rows_for_titles(stack: LocalStack, prefix: str) -> list[list[str]]:
    return stack.sql_rows(
        "SELECT d.id, j.status, j.attempt_count, "
        "COALESCE(j.lease_owner, ''), "
        "COALESCE(j.lease_expires_at::text, '') "
        "FROM rag_embedding_jobs j JOIN rag_documents d ON d.id = j.document_id "
        f"WHERE d.title LIKE '{prefix}%' ORDER BY d.id")


def embeddings_vs_chunks(stack: LocalStack, document_id: int) -> tuple[int, int]:
    chunks = sql_int(
        stack, f"SELECT count(*) FROM rag_document_chunks WHERE document_id={document_id}")
    vectors = sql_int(
        stack, f"SELECT count(*) FROM rag_embeddings WHERE document_id={document_id}")
    return chunks, vectors


def jobs_across_instances(stack: LocalStack, base_a: str, base_b: str) -> dict:
    stamp = int(time.time() * 1000)
    prefix = f"mi-job-{stamp}-"
    documents = []
    for index in range(6):
        base = base_a if index % 2 == 0 else base_b
        documents.append(create_doc(
            base, f"{prefix}{index}", f"multi instance embedding {stamp} {index} " * 20,
            embeddingPolicy="ASYNC"))

    observed_owners: set[str] = set()

    def all_succeeded() -> bool:
        running = stack.sql_rows(
            "SELECT DISTINCT j.lease_owner FROM rag_embedding_jobs j "
            "JOIN rag_documents d ON d.id = j.document_id "
            f"WHERE d.title LIKE '{prefix}%' AND j.status='RUNNING' "
            "AND j.lease_owner IS NOT NULL")
        observed_owners.update(
            # lease owner 形如 <进程 workerId>-<每次领取随机后缀>；
            # 去掉后缀才是“哪个进程实例”的稳定身份。
            row[0].rsplit("-", 1)[0] for row in running)
        rows = job_rows_for_titles(stack, prefix)
        return len(rows) == 6 and all(row[1] == "SUCCEEDED" for row in rows)

    settled = wait_until(all_succeeded, 150, "6 个 embedding job 全部由两个 worker 完成")
    rows = job_rows_for_titles(stack, prefix)

    failures = []
    if not settled:
        failures.append(f"任务未全部完成：{rows}")
    if len(rows) != 6:
        failures.append(f"任务行数 {len(rows)}，期望 6")
    if len(observed_owners) < 2:
        failures.append(
            f"只观察到 {len(observed_owners)} 个 worker owner，未证明两个实例同时消费")
    attempts = [int(row[2]) for row in rows]
    if attempts and max(attempts) != 1:
        failures.append(f"存在重复领取（attempt_count={attempts}）")
    if attempts and min(attempts) != 1:
        failures.append(f"存在未领取任务（attempt_count={attempts}）")

    for document in documents:
        chunks, vectors = embeddings_vs_chunks(stack, int(document["id"]))
        if chunks <= 0:
            failures.append(f"文档 {document['id']} 没有 chunk")
        if vectors != chunks:
            failures.append(
                f"文档 {document['id']} 向量 {vectors} != chunk {chunks}")
    duplicates = sql_int(
        stack,
        "SELECT count(*) FROM (SELECT document_id, chunk_index, count(*) "
        "FROM rag_embeddings GROUP BY 1,2 HAVING count(*) > 1) dup")
    if duplicates:
        failures.append(f"存在 {duplicates} 组重复 (document_id, chunk_index) 向量")

    check(not failures, "lease/job 跨实例：两个 worker 同批消费，每个 job 只处理一次",
          "；".join(failures) or
          f"6 个任务 attempt_count 均为 1，观察到 {len(observed_owners)} 个 worker，"
          "向量数与 chunk 数一致")
    return {"prefix": prefix, "documents": [int(d["id"]) for d in documents],
            "rows": rows, "failures": failures,
            "workers": len(observed_owners)}


def crash_takeover(stack: LocalStack, base_a: str,
                   second_instance: tuple[subprocess.Popen, int]) -> dict:
    """停掉 B，只留 A 领取任务；SIGKILL A 后重启 B，等待 lease 过期接管。"""
    proc_b, port_b = second_instance
    stack.stop_extra_app(proc_b, port_b)
    stamp = int(time.time() * 1000)
    title = f"mi-crash-{stamp}"
    document = create_doc(
        base_a, title, f"crash takeover {stamp} " * 20, embeddingPolicy="ASYNC")
    document_id = int(document["id"])
    prefix = title

    def running_owner() -> str:
        rows = job_rows_for_titles(stack, prefix)
        if not rows:
            return ""
        status, attempts, owner, expires = rows[0][1], int(rows[0][2]), rows[0][3], rows[0][4]
        if status == "RUNNING" and owner and expires:
            return owner
        return ""

    owner = ""
    deadline = time.time() + 45
    while time.time() < deadline:
        owner = running_owner()
        if owner:
            break
        time.sleep(0.2)
    if not owner:
        rows = job_rows_for_titles(stack, prefix)
        check(False, "崩溃接管前置条件：A 实例领取 job",
              f"45s 内未观察到 RUNNING/lease_owner：{rows}")
        return {"ok": False}

    rows_before = job_rows_for_titles(stack, prefix)
    attempts_before = int(rows_before[0][2])
    stack.kill_app()

    # B 重新上线；A 的 lease 过期后由 B 接管。
    new_proc = stack.spawn_app(extra_env=COMMON_ENV, port=port_b)
    stack.extra_app_processes.append((new_proc, port_b))
    stack.wait_app(port_b, new_proc)

    def takeover_succeeded() -> bool:
        rows = job_rows_for_titles(stack, prefix)
        return (len(rows) == 1 and rows[0][1] == "SUCCEEDED"
                and int(rows[0][2]) >= attempts_before + 1)

    settled = wait_until(takeover_succeeded, 150, "B 接管过期 lease 并完成任务")
    rows_after = job_rows_for_titles(stack, prefix)
    chunks, vectors = embeddings_vs_chunks(stack, document_id)
    duplicates = sql_int(
        stack,
        "SELECT count(*) FROM (SELECT document_id, chunk_index, count(*) "
        "FROM rag_embeddings GROUP BY 1,2 HAVING count(*) > 1) dup")

    failures = []
    if not settled:
        failures.append(f"B 未接管：{rows_after}")
    if rows_after:
        if int(rows_after[0][2]) < attempts_before + 1:
            failures.append(f"attempt_count 未递增：{rows_after[0][2]}")
        if rows_after[0][3] == owner:
            failures.append("lease_owner 仍是已崩溃的 A")
    if vectors != chunks or chunks <= 0:
        failures.append(f"接管后向量 {vectors} != chunk {chunks}")
    if duplicates:
        failures.append(f"接管后出现 {duplicates} 组重复向量")

    check(not failures, "崩溃接管：SIGKILL A 后 B 在 lease 过期后接管且不双写",
          "；".join(failures) or
          f"owner {owner[:18]}… attempt {attempts_before}→{int(rows_after[0][2])}，"
          f"向量 {vectors}/{chunks}")
    return {"ok": not failures, "documentId": document_id,
            "attemptsBefore": attempts_before,
            "attemptsAfter": int(rows_after[0][2]) if rows_after else -1}


# ---------- 启动与 Flyway ----------


def migration_files() -> list[Path]:
    directory = (Path(__file__).resolve().parents[1]
                 / "spring-ai-rag-core" / "src" / "main" / "resources"
                 / "db" / "migration")
    return sorted(directory.glob("V*__*.sql"))


def flyway_concurrent_start(stack: LocalStack, base_a: str, base_b: str,
                            logs: list[Path]) -> dict:
    history = stack.sql_rows(
        "SELECT installed_rank, version, success FROM flyway_schema_history "
        "ORDER BY installed_rank")
    expected = len(migration_files())
    failures = []
    if len(history) != expected:
        failures.append(f"history 行数 {len(history)} != 迁移文件 {expected}")
    ranks = [int(row[0]) for row in history]
    if ranks != list(range(1, len(history) + 1)):
        failures.append(f"installed_rank 不连续：{ranks[:5]}…")
    versions = [row[1] for row in history]
    if len(set(versions)) != len(versions):
        failures.append("存在重复迁移版本")
    if any(row[2] != "t" for row in history):
        failures.append("存在失败迁移记录")

    applied = 0
    errors: list[str] = []
    for log in logs:
        if not log.exists():
            continue
        text = log.read_text(errors="replace")
        applied += len([line for line in text.splitlines()
                        if "Migrating schema" in line and "to version" in line])
        for marker in ("FlywayException", "Unable to obtain lock",
                       "APPLICATION FAILED TO START"):
            if marker in text:
                errors.append(f"{log.name}: {marker}")
    if applied != expected:
        failures.append(f"日志中实际应用 {applied} 条迁移，期望 {expected}")
    if errors:
        failures.append("；".join(errors))

    health_a = http_request("GET", f"{base_a}/actuator/health")[0]
    health_b = http_request("GET", f"{base_b}/actuator/health")[0]

    check(not failures, "Flyway 并发启动：两实例同时起，迁移只应用一次且 history 连续",
          "；".join(failures) or
          f"{expected} 条迁移、rank 1..{expected}、两实例 health {health_a}/{health_b}")
    return {"history": len(history), "expected": expected,
            "applied": applied, "failures": failures}


def startup_assertion_negative(stack: LocalStack) -> None:
    """多实例声明 + local 限流必须在启动期被拒绝（文档承诺的行为）。"""
    port = 18088
    process = stack.spawn_app(
        extra_env={
            "RAG_MULTI_INSTANCE_ENABLED": "true",
            "RAG_RATE_LIMIT_ENABLED": "true",
            "RAG_RATE_LIMIT_BACKEND": "local",
            "RAG_RATE_LIMIT_STRATEGY": "ip",
        },
        port=port)
    try:
        try:
            code = process.wait(timeout=90)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
            code = -1
        log = (stack.log_dir / f"app-{port}.log")
        text = log.read_text(errors="replace") if log.exists() else ""
        rejected = code not in (0, -1) and "keeps counters inside each process" in text
        check(rejected, "启动断言：多实例 + local 限流被拒绝启动",
              f"exit={code}，日志含拒绝原因={'keeps counters inside each process' in text}")
    finally:
        if process.poll() is None:
            process.kill()


# ---------- 主流程 ----------


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true", help="保留临时库与日志")
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()

    stack = LocalStack("rag_multi_instance", APP_A_PORT, MOCK_PORT, keep=args.keep)
    base_a = f"http://127.0.0.1:{APP_A_PORT}"
    base_b = f"http://127.0.0.1:{APP_B_PORT}"
    try:
        stack.create_database()
        stack.start_mock(delay_ms=3000, stream_delay_ms=20, stream_chunks=8,
                         stream_first_delay_ms=5)
        if not args.skip_build:
            stack.build_demo_if_needed()

        # 两实例同时启动：Flyway 迁移锁串行化，先到者应用，另一个等待后直接复用。
        proc_a = stack.spawn_app(extra_env=COMMON_ENV, port=APP_A_PORT)
        proc_b = stack.spawn_app(extra_env=COMMON_ENV, port=APP_B_PORT)
        stack.app_process = proc_a
        stack.extra_app_processes.append((proc_b, APP_B_PORT))
        stack.wait_app(APP_A_PORT, proc_a)
        stack.wait_app(APP_B_PORT, proc_b)
        log_a = stack.log_dir / f"app-{APP_A_PORT}.log"
        log_b = stack.log_dir / f"app-{APP_B_PORT}.log"

        flyway = flyway_concurrent_start(
            stack, base_a, base_b, [log_a, log_b])

        create_collection(base_a, "mi-base", "Multi instance base")

        sse = sse_cross_instance(stack, base_a, base_b)
        idem = idempotency_across_instances(stack, base_a, base_b)
        cache = cache_and_permissions(base_a, base_b)
        rate = rate_limit_across_instances(stack, base_a, base_b)
        jobs = jobs_across_instances(stack, base_a, base_b)
        crash = crash_takeover(stack, base_a, (proc_b, APP_B_PORT))
        startup_assertion_negative(stack)

        print("\n=== 多实例验收汇总 ===")
        print(f"SSE: DB={sse['status_in_db']}，首 id={sse['last_id']}，"
              f"B 补发={sse['resumed']['events']}")
        print(f"幂等: actions={idem['actions']}，文档={idem['document_ids']}，"
              f"凭证表 {idem['rows']} 条")
        print(f"缓存/权限: 文档 {cache['documentId']} revision={cache['newRevision']}")
        print(f"限流: 放行 {rate['successes']}/10，429={rate['limited']}，"
              f"A={rate['perInstance']['A']} B={rate['perInstance']['B']}，"
              f"共享桶={rate['bucket']}")
        print(f"job: {len(jobs['rows'])} 个 attempt={[int(r[2]) for r in jobs['rows']]}，"
              f"参与的 worker={jobs['workers']}")
        print(f"崩溃接管: attempt {crash['attemptsBefore']}→{crash['attemptsAfter']}")
        print(f"Flyway: history={flyway['history']}，日志应用={flyway['applied']}")
    finally:
        stack.cleanup()

    if FAILURES:
        print("\n失败项：", file=sys.stderr)
        for failure in FAILURES:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print("\n多实例验收全部通过：跨实例续传/幂等/任务 lease/权限/共享限流/Flyway 并发启动")
    return 0


if __name__ == "__main__":
    sys.exit(main())
