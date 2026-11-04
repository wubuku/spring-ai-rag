#!/usr/bin/env python3
"""崩溃一致性验收：真实 PostgreSQL + mock provider + 真实 SIGKILL。

本脚本覆盖四类"正常流程测不出来"的进程强杀场景：

1. 异步摄入/嵌入任务中途 kill -9：租约到期后由重启后的实例接管，
   最终状态收敛且向量数量与 chunk_count 一致（不出现半写向量）。
2. 聊天 SSE 流中途 kill -9：会话租约到期后可重新获取，历史表不出现
   半截消息。
3. 带 Idempotency-Key 的聊天请求中途 kill -9：重试在租约到期后继续，
   不会被永久 409 拒绝；最终只记一条历史。
4. multipart 上传中途 kill -9：幂等记录与半成品文档可被重试/恢复，
   重试不会永久 409，最终文档与向量收敛。

可观测收敛上界（脚本内会断言，不只写在文档里）：

    converge_bound = lease_ttl + poll_interval + worker_processing_margin

- 嵌入任务：lease 30s（仓库下限）+ poll 10s（属性下限）+ 处理余量 20s = 60s
- 会话租约：lease 30s（history.lease-ttl-seconds 默认值，不可低于 10s）
- 聊天幂等操作：lease = max(30s, chat-ask-ms + lease-grace 10s)；
  脚本把 chat-ask-ms 压到 20s，因此仍是 30s 下限，另加 15s 重试余量。

失败时退出码非 0；成功时打印每个场景的实测收敛耗时。
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
import time
import traceback
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from lib.rag_local_stack import (  # noqa: E402
    ROOT_API_KEY,
    LocalStack,
    json_request,
    multipart_request,
)

APP_PORT = 18083
MOCK_PORT = 18084
MOCK_DELAY_MS = 2500

EMBEDDING_LEASE_SECONDS = 30
# 属性 setter 把 poll 下限钳到 10s；收敛上界按真实下限计算。
EMBEDDING_POLL_MS = 10000
EMBEDDING_CONVERGE_BOUND_SECONDS = 60

CHAT_LEASE_SECONDS = 30
CHAT_CONVERGE_BOUND_SECONDS = 45

APP_ARGS = [
    "--rag.embedding-jobs.poll-interval-ms=%d" % EMBEDDING_POLL_MS,
    "--rag.embedding-jobs.lease-seconds=%d" % EMBEDDING_LEASE_SECONDS,
    "--rag.embedding-jobs.retry-backoff-seconds=2",
    # 幂等操作租约 = max(30s, chat-ask-ms + 10s grace)；压低超时以免验收等待过久。
    "--rag.timeout.chat-ask-ms=20000",
    "--rag.timeout.chat-stream-ms=20000",
]

API_HEADERS = {"X-API-Key": ROOT_API_KEY}


class ScenarioFailure(AssertionError):
    pass


def log(message: str) -> None:
    print(message, flush=True)


def wait_until(label: str, timeout: float, probe, interval: float = 0.2):
    deadline = time.time() + timeout
    last_error: Exception | None = None
    while time.time() < deadline:
        try:
            value = probe()
        except Exception as error:  # 数据库尚未迁移完成/连接暂不可用时继续等
            last_error = error
            value = None
        if value:
            return value
        time.sleep(interval)
    detail = f"; 最后一次错误: {last_error}" if last_error else ""
    raise ScenarioFailure(f"{label} 未在 {timeout:.0f}s 内满足条件{detail}")


def sql_scalar(stack: LocalStack, statement: str) -> str:
    return stack.sql(statement)


def sql_count(stack: LocalStack, statement: str) -> int:
    value = stack.sql(statement).strip()
    return int(value) if value else 0


# ---------------------------------------------------------------- 场景 1


def scenario_embedding_kill(stack: LocalStack, base_url: str) -> None:
    log("\n[1/4] 嵌入任务中途 kill -9")
    title = f"crash-embed-{int(time.time())}"
    content = ("Spring AI RAG crash consistency probe. " * 40).strip()
    status, body = json_request(
        "POST", f"{base_url}/api/v1/rag/documents",
        {"title": title, "content": content, "embeddingPolicy": "ASYNC"},
        timeout=30)
    if status != 200:
        raise ScenarioFailure(f"创建文档失败: HTTP {status} {body[:300]}")
    document_id = json.loads(body)["id"]

    started = time.time()
    running = wait_until(
        "文档进入 RUNNING/EMBEDDING",
        20,
        lambda: sql_scalar(stack,
            "SELECT status || '|' || coalesce(lease_expires_at::text,'') FROM rag_embedding_jobs "
            f"WHERE document_id={document_id} AND status='RUNNING' LIMIT 1"),
        interval=0.1,
    )
    log(f"  观察到任务运行中: {running}")
    stack.kill_app()

    # 强杀瞬间允许 RUNNING，但不得有半写向量（事务提交前不会出现）。
    job_status = sql_scalar(stack,
        f"SELECT status FROM rag_embedding_jobs WHERE document_id={document_id} ORDER BY id DESC LIMIT 1")
    vectors = sql_count(stack,
        f"SELECT count(*) FROM rag_embeddings WHERE document_id={document_id}")
    if job_status not in ("RUNNING", "QUEUED"):
        raise ScenarioFailure(f"kill 后任务状态异常: {job_status}")
    if vectors != 0:
        raise ScenarioFailure(f"kill 瞬间出现半写向量: {vectors}")

    base_url = stack.start_app(extra_args=APP_ARGS)
    wait_until(
        "重启后嵌入任务被接管并完成",
        EMBEDDING_CONVERGE_BOUND_SECONDS,
        lambda: sql_scalar(stack,
            "SELECT status FROM rag_embedding_jobs "
            f"WHERE document_id={document_id} ORDER BY id DESC LIMIT 1") == "SUCCEEDED",
    )
    state = wait_until(
        "嵌入状态 COMPLETED",
        10,
        lambda: sql_scalar(stack,
            "SELECT status || '|' || chunk_count FROM rag_document_embedding_state "
            f"WHERE document_id={document_id}"),
    )
    state_status, chunk_count = state.split("|")
    vectors = sql_count(stack,
        f"SELECT count(*) FROM rag_embeddings WHERE document_id={document_id}")
    if state_status != "COMPLETED":
        raise ScenarioFailure(f"嵌入状态未收敛: {state}")
    if int(chunk_count) <= 0 or vectors != int(chunk_count):
        raise ScenarioFailure(
            f"向量数量与 chunk_count 不一致: vectors={vectors} chunk_count={chunk_count}")
    processing = sql_scalar(stack,
        f"SELECT processing_status FROM rag_documents WHERE id={document_id}")
    if processing in ("INDEXING", "PROCESSING"):
        raise ScenarioFailure(f"文档永久停在 {processing}")
    elapsed = time.time() - started
    log(f"  收敛耗时 {elapsed:.1f}s（上界 {EMBEDDING_CONVERGE_BOUND_SECONDS}s），"
        f"chunk={chunk_count} vectors={vectors} status={processing}")


# ---------------------------------------------------------------- 场景 2


def _stream_request(base_url: str, session_id: str) -> threading.Thread:
    def run() -> None:
        request = urllib.request.Request(
            f"{base_url}/api/v1/rag/chat/stream",
            data=json.dumps({"message": "讲一句欢迎语", "sessionId": session_id}).encode(),
            method="POST",
        )
        request.add_header("Content-Type", "application/json")
        request.add_header("Accept", "text/event-stream")
        request.add_header("X-API-Key", ROOT_API_KEY)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                response.read()
        except Exception:
            pass  # 进程被 kill 后连接断开属预期

    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    return thread


def scenario_chat_sse_kill(stack: LocalStack, base_url: str) -> None:
    log("\n[2/4] 聊天 SSE 流中途 kill -9")
    session_id = f"crash-sse-{int(time.time())}"
    started = time.time()
    thread = _stream_request(base_url, session_id)
    wait_until(
        "会话租约已写入",
        15,
        lambda: sql_scalar(stack,
            "SELECT owner_token FROM rag_chat_session_lease "
            f"WHERE session_id='{session_id}'"),
        interval=0.1,
    )
    stack.kill_app()
    thread.join(timeout=5)

    history = sql_count(stack,
        "SELECT count(*) FROM rag_chat_history "
        f"WHERE session_id='{session_id}'")
    if history != 0:
        raise ScenarioFailure(f"SSE 被 kill 后写入半截历史: {history} 条")
    lease = sql_count(stack,
        "SELECT count(*) FROM rag_chat_session_lease "
        f"WHERE session_id='{session_id}'")
    if lease != 1:
        raise ScenarioFailure("会话租约记录缺失，无法验证过期接管")

    base_url = stack.start_app(extra_args=APP_ARGS)
    # 租约未到期前允许 409；到期后必须可重新获取并成功。
    deadline = time.time() + CHAT_CONVERGE_BOUND_SECONDS
    last_status = None
    while time.time() < deadline:
        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/chat/ask",
            {"message": "重启后继续", "sessionId": session_id}, timeout=20)
        last_status = status
        if status == 200:
            break
        if status not in (409, 423, 429, 500, 503):
            raise ScenarioFailure(f"会话重试出现非预期状态: HTTP {status} {body[:200]}")
        time.sleep(1)
    else:
        raise ScenarioFailure(
            f"会话租约未在 {CHAT_CONVERGE_BOUND_SECONDS}s 内释放，最后状态 {last_status}")

    history = sql_count(stack,
        "SELECT count(*) FROM rag_chat_history "
        f"WHERE session_id='{session_id}' AND turn_status='COMPLETE'")
    if history != 1:
        raise ScenarioFailure(f"重试后历史条数异常: {history}")
    partial = sql_count(stack,
        "SELECT count(*) FROM rag_chat_history "
        f"WHERE session_id='{session_id}' AND (ai_response IS NULL OR ai_response='')")
    if partial != 0:
        raise ScenarioFailure(f"存在半截消息 {partial} 条")
    log(f"  租约释放并重试成功，耗时 {time.time() - started:.1f}s"
        f"（上界 {CHAT_CONVERGE_BOUND_SECONDS}s）")


# ---------------------------------------------------------------- 场景 3


def scenario_chat_idempotency_kill(stack: LocalStack, base_url: str) -> None:
    log("\n[3/4] 幂等聊天请求中途 kill -9")
    session_id = f"crash-idem-{int(time.time())}"
    idem_key = f"crash-idem-key-{int(time.time())}"
    payload = {"message": "幂等崩溃验收", "sessionId": session_id}
    started = time.time()

    def first_attempt() -> None:
        try:
            json_request(
                "POST", f"{base_url}/api/v1/rag/chat/ask", payload,
                extra_headers={"Idempotency-Key": idem_key}, timeout=30)
        except Exception:
            pass

    thread = threading.Thread(target=first_attempt, daemon=True)
    thread.start()
    wait_until(
        "幂等操作已进入执行",
        15,
        lambda: sql_scalar(stack,
            "SELECT status FROM rag_chat_turn_operations "
            f"WHERE session_id='{session_id}' ORDER BY id DESC LIMIT 1"),
        interval=0.1,
    )
    stack.kill_app()
    thread.join(timeout=5)

    operation_status = sql_scalar(stack,
        "SELECT status FROM rag_chat_turn_operations "
        f"WHERE session_id='{session_id}' ORDER BY id DESC LIMIT 1")
    if operation_status != "IN_PROGRESS":
        raise ScenarioFailure(f"kill 后操作状态异常: {operation_status}")

    base_url = stack.start_app(extra_args=APP_ARGS)
    deadline = time.time() + CHAT_CONVERGE_BOUND_SECONDS
    saw_in_progress = False
    while time.time() < deadline:
        status, body = json_request(
            "POST", f"{base_url}/api/v1/rag/chat/ask", payload,
            extra_headers={"Idempotency-Key": idem_key, "Content-Type": "application/json"},
            timeout=20)
        if status == 409:
            saw_in_progress = True
            time.sleep(1)
            continue
        if status == 200:
            answer = json.loads(body).get("answer", "")
            if not answer:
                raise ScenarioFailure("重试成功但答案为空")
            break
        raise ScenarioFailure(f"幂等重试出现非预期状态: HTTP {status} {body[:200]}")
    else:
        raise ScenarioFailure(
            f"幂等操作在 {CHAT_CONVERGE_BOUND_SECONDS}s 内未恢复，疑似永久 409")

    final_status = sql_scalar(stack,
        "SELECT status FROM rag_chat_turn_operations "
        f"WHERE session_id='{session_id}' ORDER BY id DESC LIMIT 1")
    if final_status != "SUCCEEDED":
        raise ScenarioFailure(f"幂等操作未收敛: {final_status}")
    history = sql_count(stack,
        "SELECT count(*) FROM rag_chat_history "
        f"WHERE session_id='{session_id}'")
    if history != 1:
        raise ScenarioFailure(f"幂等重试产生了 {history} 条历史（应为 1）")
    log(f"  期间观察到 409={'是' if saw_in_progress else '否'}，"
        f"最终恢复耗时 {time.time() - started:.1f}s（上界 {CHAT_CONVERGE_BOUND_SECONDS}s）")


# ---------------------------------------------------------------- 场景 4


def scenario_upload_kill(stack: LocalStack, base_url: str) -> None:
    log("\n[4/4] multipart 上传中途 kill -9")
    filename = f"crash-upload-{int(time.time())}.md"
    content = ("# crash upload\n\n" + "upload crash consistency. " * 200).encode()
    idem_key = f"crash-upload-key-{int(time.time())}"
    started = time.time()

    def first_upload() -> None:
        try:
            multipart_request(
                f"{base_url}/api/v1/rag/documents/upload", "files", filename,
                content, {},
                extra_headers={"Idempotency-Key": idem_key}, timeout=30)
        except Exception:
            pass

    thread = threading.Thread(target=first_upload, daemon=True)
    thread.start()
    wait_until(
        "上传文档记录已创建",
        20,
        lambda: sql_scalar(stack,
            f"SELECT id FROM rag_documents WHERE source='upload:{filename}' "
            "ORDER BY id DESC LIMIT 1"),
        interval=0.1,
    )
    wait_until(
        "幂等记录已预留",
        10,
        lambda: sql_scalar(stack,
            "SELECT status FROM rag_document_idempotency_operations "
            "WHERE operation_type='FILE_UPLOAD' ORDER BY id DESC LIMIT 1"),
        interval=0.1,
    )
    stack.kill_app()
    thread.join(timeout=5)

    document_id = int(sql_scalar(stack,
        f"SELECT id FROM rag_documents WHERE source='upload:{filename}' "
        "ORDER BY id DESC LIMIT 1"))
    vectors = sql_count(stack,
        f"SELECT count(*) FROM rag_embeddings WHERE document_id={document_id}")
    if vectors != 0:
        raise ScenarioFailure(f"kill 瞬间出现半写向量: {vectors}")

    base_url = stack.start_app(extra_args=APP_ARGS)
    # 重试同一 Idempotency-Key：不得永久 409；成功或复用已有文档都算收敛。
    status, body = multipart_request(
        f"{base_url}/api/v1/rag/documents/upload", "files", filename,
        content, {},
        extra_headers={"Idempotency-Key": idem_key}, timeout=30)
    if status == 409:
        deadline = time.time() + CHAT_CONVERGE_BOUND_SECONDS
        while status == 409 and time.time() < deadline:
            time.sleep(2)
            status, body = multipart_request(
                f"{base_url}/api/v1/rag/documents/upload", "files", filename,
                content, {},
                extra_headers={"Idempotency-Key": idem_key}, timeout=30)
        if status == 409:
            raise ScenarioFailure("上传幂等记录永久 409，未在收敛上界内恢复")
    if status != 200:
        raise ScenarioFailure(f"上传重试失败: HTTP {status} {body[:300]}")

    wait_until(
        "上传文档嵌入收敛",
        EMBEDDING_CONVERGE_BOUND_SECONDS,
        lambda: sql_scalar(stack,
            "SELECT status FROM rag_embedding_jobs "
            f"WHERE document_id={document_id} ORDER BY id DESC LIMIT 1") == "SUCCEEDED",
    )
    state = sql_scalar(stack,
        "SELECT status || '|' || chunk_count FROM rag_document_embedding_state "
        f"WHERE document_id={document_id}")
    state_status, chunk_count = state.split("|")
    vectors = sql_count(stack,
        f"SELECT count(*) FROM rag_embeddings WHERE document_id={document_id}")
    if state_status != "COMPLETED" or vectors != int(chunk_count):
        raise ScenarioFailure(
            f"上传文档未收敛: {state} vectors={vectors}")
    duplicate = sql_count(stack,
        f"SELECT count(*) FROM rag_documents WHERE source='upload:{filename}'")
    if duplicate != 1:
        raise ScenarioFailure(f"重试产生了重复文档: {duplicate}")
    stuck = sql_count(stack,
        "SELECT count(*) FROM rag_document_idempotency_operations "
        "WHERE operation_type='FILE_UPLOAD' AND status='IN_PROGRESS'")
    if stuck != 0:
        raise ScenarioFailure(f"仍存在 {stuck} 条 IN_PROGRESS 上传幂等记录")
    log(f"  重试成功，文档/向量收敛，耗时 {time.time() - started:.1f}s"
        f"（上界 {EMBEDDING_CONVERGE_BOUND_SECONDS + CHAT_CONVERGE_BOUND_SECONDS}s）")


def main() -> int:
    parser = argparse.ArgumentParser(description="崩溃一致性验收")
    parser.add_argument("--keep", action="store_true", help="保留临时目录与日志")
    parser.add_argument("--only", choices=["embed", "sse", "idem", "upload"],
                        help="只执行单个场景（调试用）")
    args = parser.parse_args()
    stack = LocalStack("crash_consistency", APP_PORT, MOCK_PORT, keep=args.keep)
    failures: list[str] = []
    try:
        stack.build_demo_if_needed()
        stack.create_database()
        stack.start_mock(delay_ms=MOCK_DELAY_MS)
        base_url = stack.start_app(extra_args=APP_ARGS)
        log(f"临时库 {stack.db_name}，应用 {base_url}，mock 延迟 {MOCK_DELAY_MS}ms")

        scenarios = (
            ("embed", "嵌入任务", scenario_embedding_kill),
            ("sse", "聊天 SSE", scenario_chat_sse_kill),
            ("idem", "聊天幂等", scenario_chat_idempotency_kill),
            ("upload", "multipart 上传", scenario_upload_kill),
        )
        for key, name, scenario in scenarios:
            if args.only and args.only != key:
                continue
            try:
                scenario(stack, base_url)
                base_url = f"http://127.0.0.1:{APP_PORT}"
            except Exception as error:
                failures.append(f"{name}: {error}")
                log(f"  [FAIL] {error}")
                traceback.print_exc()
                # 场景失败后重启，避免后续场景被上一个 kill 影响。
                try:
                    base_url = stack.start_app(extra_args=APP_ARGS)
                except Exception as restart_error:
                    failures.append(f"{name}: 重启失败 {restart_error}")
                    break
    finally:
        stack.cleanup()

    if failures:
        log("\n崩溃一致性验收失败：")
        for failure in failures:
            log(f"  - {failure}")
        return 1
    log("\n崩溃一致性验收全部通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
