#!/usr/bin/env python3
"""敏感数据落盘/落日志审计（真实进程）。

结论目标：
1. 用户 prompt、检索 query、文档标题/正文、上传文件名/内容、客户端错误文本
   不会出现在 INFO 日志里；
2. API Key、Authorization、Idempotency-Key 不会出现在日志、actuator 输出、
   错误体或导出产物里；
3. `/actuator/env`、`/actuator/beans`、`/actuator/loggers` 等诊断端点未暴露；
4. 错误体与导出产物不包含本机内部路径。

做法：向真实进程发送一组带 ``SECRET_CANARY_*`` 标记的请求，然后把
应用日志、actuator 响应、错误体、导出产物全文扫描一遍。允许出现 canary
的地方只有「数据面」：collection/chat 导出本身就是被导出的数据。

运行前提：本地 PostgreSQL 可用（与其它真实进程脚本一致），demo jar 已构建。
"""

from __future__ import annotations

import json
import sys
import urllib.parse
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lib.rag_local_stack import (  # noqa: E402
    ROOT_API_KEY,
    LocalStack,
    http_request,
    http_request_full,
    json_request,
    multipart_request,
)

APP_PORT = 18123
MOCK_PORT = 18124

CANARY = {
    # 请求/数据面内容：允许出现在响应与导出（数据本身），不允许进日志/指标。
    "prompt": "SECRET_CANARY_PROMPT_A1F4",
    "prompt_stream": "SECRET_CANARY_STREAM_B2E7",
    "history": "SECRET_CANARY_HISTORY_C3D8",
    "query_get": "SECRET_CANARY_QUERY_D4C9",
    "query_post": "SECRET_CANARY_QUERYPOST_E5BA",
    "doc_title": "SECRET_CANARY_DOCTITLE_F6AB",
    "doc_content": "SECRET_CANARY_DOCCONTENT_A7BC",
    "upload_filename": "SECRET_CANARY_FILENAME_B8CD.txt",
    "upload_content": "SECRET_CANARY_FILECONTENT_C9DE",
    "client_error": "SECRET_CANARY_CLIENTERROR_DADF",
    "collection_name": "SECRET_CANARY_COLLECTION_EBA0",
    # 凭据/请求头：任何日志、指标、错误体、导出里都不允许出现。
    "api_key": "SECRET_CANARY_APIKEY_F1B2",
    "bearer": "SECRET_CANARY_BEARER_A2C3",
    "idempotency": "SECRET_CANARY_IDEM_B3D4",
}

DATA_PLANE = {
    "prompt", "prompt_stream", "history", "query_get", "query_post",
    "doc_title", "doc_content", "upload_filename", "upload_content",
    "client_error", "collection_name",
}
CREDENTIAL = {"api_key", "bearer", "idempotency"}

# 错误体 / 导出产物 / actuator 里不应出现的本机内部路径特征。
INTERNAL_PATH_MARKERS = [
    "/Users/",
    "/tmp/rag-verify-",
    "spring-ai-rag-core/src",
    "target/classes",
    "file:/",
    "jdbc:postgresql://",
]

PASSED: list[str] = []
FAILED: list[str] = []


def pass_check(message: str) -> None:
    PASSED.append(message)
    print(f"[PASS] {message}")


def fail(message: str) -> None:
    FAILED.append(message)
    print(f"[FAIL] {message}", file=sys.stderr)


def scan(label: str, text: str, *, allowed: set[str],
         forbidden_markers: list[str] | None = None) -> None:
    """断言 text 不含未授权的 canary 与内部路径特征。"""
    leaked = [name for name, value in CANARY.items()
              if name not in allowed and value in text]
    if leaked:
        fail(f"{label}: 泄漏 canary {sorted(leaked)}")
        return
    if forbidden_markers:
        hit = [marker for marker in forbidden_markers if marker in text]
        if hit:
            fail(f"{label}: 包含内部路径特征 {hit}")
            return
    pass_check(f"{label}: 无未授权 canary" + ("、无内部路径" if forbidden_markers else ""))


def body_text(response_body: str) -> str:
    return response_body or ""


def main() -> int:
    stack = LocalStack("log-secrecy", APP_PORT, MOCK_PORT)
    try:
        stack.build_demo_if_needed()
        stack.start_mock(stream_chunks=4,
                         extra_env={"MOCK_DETERMINISTIC_EMBEDDINGS": "true"})
        database = stack.create_database()
        base = stack.start_app(database=database)

        collection_key = "log-secrecy-probe"
        status, body = json_request("POST", f"{base}/api/v1/rag/collections", {
            "collectionKey": collection_key,
            "name": CANARY["collection_name"],
            "embeddingModel": "BAAI/bge-m3",
            "dimensions": 1024,
        })
        if status not in (200, 201):
            raise RuntimeError(f"创建 collection 失败 {status} {body}")

        status, body = json_request("POST", f"{base}/api/v1/rag/documents/upsert", {
            "collectionKey": collection_key,
            "sourceNamespace": "log-secrecy",
            "externalId": "canary-1",
            "sourceRevision": "rev-1",
            "title": CANARY["doc_title"],
            "content": CANARY["doc_content"] * 8,
            "metadata": {"probe": "log-secrecy"},
            "embed": True,
            "embeddingPolicy": "SYNC",
        }, extra_headers={"Idempotency-Key": CANARY["idempotency"]})
        if status != 200:
            raise RuntimeError(f"写入文档失败 {status} {body}")

        session_id = str(uuid.uuid4())
        status, body = json_request("POST", f"{base}/api/v1/rag/chat/ask", {
            "message": CANARY["prompt"],
            "mode": "KNOWLEDGE",
            "sessionId": session_id,
            "collectionKeys": [collection_key],
        }, extra_headers={"Idempotency-Key": CANARY["idempotency"] + "-chat"})
        if status != 200:
            raise RuntimeError(f"非流式 chat 失败 {status} {body}")

        status, body = json_request("POST", f"{base}/api/v1/rag/chat/ask", {
            "message": CANARY["history"],
            "mode": "KNOWLEDGE",
            "sessionId": session_id,
            "collectionKeys": [collection_key],
        })
        if status != 200:
            raise RuntimeError(f"chat 历史轮次失败 {status} {body}")

        status, _, stream_body = http_request_full(
            "POST", f"{base}/api/v1/rag/chat/stream",
            headers={"Content-Type": "application/json", "X-API-Key": ROOT_API_KEY},
            body=json.dumps({
                "message": CANARY["prompt_stream"],
                "mode": "KNOWLEDGE",
                "sessionId": session_id,
                "collectionKeys": [collection_key],
            }).encode(), timeout=120)
        if status != 200:
            raise RuntimeError(f"流式 chat 失败 {status} {stream_body}")

        query = urllib.parse.quote(CANARY["query_get"])
        status, search_body = http_request(
            "GET", f"{base}/api/v1/rag/search?query={query}"
                   f"&collectionKeys={urllib.parse.quote(collection_key)}",
            headers={"X-API-Key": ROOT_API_KEY})
        if status != 200:
            raise RuntimeError(f"GET 检索失败 {status} {search_body}")

        status, body = json_request("POST", f"{base}/api/v1/rag/search", {
            "query": CANARY["query_post"],
            "collectionKeys": [collection_key],
        })
        if status != 200:
            raise RuntimeError(f"POST 检索失败 {status} {body}")

        status, body = multipart_request(
            f"{base}/api/v1/rag/documents/upload",
            "files", CANARY["upload_filename"],
            CANARY["upload_content"].encode(), {"collectionKey": collection_key})
        if status not in (200, 201):
            raise RuntimeError(f"上传文件失败 {status} {body}")

        status, body = json_request("POST", f"{base}/api/v1/rag/client-errors", {
            "errorType": "unknown",
            "errorMessage": CANARY["client_error"],
            "pageUrl": "/webui/search?q=" + CANARY["query_get"],
            "sessionId": session_id,
            "userAgent": "log-secrecy-probe",
        })
        if status not in (200, 202):
            raise RuntimeError(f"上报客户端错误失败 {status} {body}")

        # 凭据走错误路径：401/403 都不应把凭据回显到响应体或日志。
        error_bodies: list[tuple[str, str]] = []
        status, body = http_request(
            "GET", f"{base}/api/v1/rag/collections",
            headers={"X-API-Key": CANARY["api_key"]})
        error_bodies.append(("非法 API Key 错误体", body))
        status, body = http_request(
            "GET", f"{base}/api/v1/rag/collections",
            headers={"Authorization": "Bearer " + CANARY["bearer"]})
        error_bodies.append(("非法 Bearer 错误体", body))
        status, body = http_request(
            "GET", f"{base}/api/v1/rag/documents/999999999",
            headers={"X-API-Key": ROOT_API_KEY})
        error_bodies.append(("文档 404 错误体", body))
        status, body = http_request(
            "GET", f"{base}/api/v1/rag/files/raw?path=../../etc/passwd",
            headers={"X-API-Key": ROOT_API_KEY})
        error_bodies.append(("路径穿越错误体", body))

        # 导出（数据面）：正文/标题/collection 名称允许出现，凭据不允许。
        status, collection_export = http_request(
            "GET", f"{base}/api/v1/rag/collections/by-key/export?"
                   f"collectionKey={urllib.parse.quote(collection_key)}",
            headers={"X-API-Key": ROOT_API_KEY})
        if status != 200:
            raise RuntimeError(f"导出 collection 失败 {status} {collection_export}")
        for name in ("doc_title", "doc_content", "upload_filename",
                     "upload_content", "collection_name"):
            if CANARY[name] not in collection_export:
                fail(f"collection 导出缺少数据面 canary {name}（导出可能被过度脱敏）")
        scan("collection 导出", collection_export,
             allowed={"doc_title", "doc_content", "upload_filename",
                      "upload_content", "collection_name"},
             forbidden_markers=INTERNAL_PATH_MARKERS)

        status, chat_export = http_request(
            "GET", f"{base}/api/v1/rag/chat/export/{session_id}?format=json",
            headers={"X-API-Key": ROOT_API_KEY})
        if status != 200:
            raise RuntimeError(f"导出 chat 失败 {status} {chat_export}")
        if CANARY["prompt"] not in chat_export:
            fail("chat 导出缺少历史 prompt（导出可能被过度脱敏）")
        scan("chat 导出", chat_export,
             allowed={"prompt", "prompt_stream", "history", "query_get",
                      "query_post", "doc_content", "doc_title"},
             forbidden_markers=INTERNAL_PATH_MARKERS)

        # 错误体：不出现凭据 canary，也不出现内部路径。
        merged_errors = "\n".join(body_text(b) for _, b in error_bodies)
        scan("错误体", merged_errors,
             allowed=DATA_PLANE, forbidden_markers=INTERNAL_PATH_MARKERS)

        # actuator：只允许状态/指标，不允许任何 canary 或内部路径。
        for path in (
            "/actuator/health",
            "/actuator/health/readiness",
            "/actuator/metrics",
            "/actuator/metrics/http.server.requests",
            "/actuator/prometheus",
            "/actuator/info",
        ):
            status, payload = http_request("GET", base + path, timeout=60)
            scan(f"actuator {path}", f"status={status}\n{payload}",
                 allowed=set(), forbidden_markers=INTERNAL_PATH_MARKERS)
            if path in ("/actuator/health", "/actuator/metrics"):
                if status != 200:
                    fail(f"actuator {path} 不可用（status={status}）")

        for path in ("/actuator/env", "/actuator/beans",
                     "/actuator/loggers", "/actuator/configprops"):
            status, payload = http_request("GET", base + path)
            if status != 404:
                fail(f"诊断端点 {path} 未关闭（status={status}）")
                scan(f"诊断端点 {path}", payload, allowed=set(),
                     forbidden_markers=INTERNAL_PATH_MARKERS)
            else:
                pass_check(f"诊断端点 {path} 已关闭（404）")
    finally:
        stack.stop_app()
        log_file = stack.log_dir / f"app-{APP_PORT}.log"
        if log_file.exists():
            log_text = log_file.read_text(errors="replace")
            scan("应用日志（demo 对 com.springairag 开到 DEBUG，覆盖 INFO/DEBUG）",
                 log_text, allowed=set())
        else:
            fail("找不到应用日志文件")
        stack.cleanup()

    print(f"\n[SUMMARY] {len(PASSED)} passed, {len(FAILED)} failed")
    if FAILED:
        print("失败项：", file=sys.stderr)
        for item in FAILED:
            print(f"  - {item}", file=sys.stderr)
        return 1
    print("结论：敏感 canary 未出现在日志/指标/错误体/导出的非授权位置。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
