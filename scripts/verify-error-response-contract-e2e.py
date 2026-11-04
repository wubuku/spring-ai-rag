#!/usr/bin/env python3
"""真实进程错误契约验收：逐一构造失败路径，断言状态码、体字段与分类头。

`scripts/verify-error-response-contract.mjs` 是静态普查，回答"代码里有没有偏离"。
这个脚本回答另一个问题：把请求打到真实运行的应用上，客户端实际收到什么。

覆盖的出口（每一类都对应一种"以前会静默少字段"的路径）：

  401  过滤器短路（ApiKeyAuthFilter，DispatcherServlet 之前）
  400  参数/请求体校验（GlobalExceptionHandler）
  404  不存在的资源
  409  幂等键指纹冲突（业务异常处理器）
  429  限流短路（RateLimitFilter，另一个过滤器出口）
  504  chat 上游超时（超时异常处理器）
  500  上游 500（未分类异常处理器；断言不泄漏堆栈/内部类名）
  500  容器级 /error（RagErrorController，不经过任何 advice）
  OpenAI 401 信封与原生信封的分类头一致
  OpenAI 5xx 信封仍带分类头且不泄漏内部信息

用法：
  python3 scripts/verify-error-response-contract-e2e.py
  python3 scripts/verify-error-response-contract-e2e.py --keep
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))
from rag_local_stack import ROOT_API_KEY, LocalStack, http_request_full  # noqa: E402

APP_PORT = 18091
MOCK_PORT = 18092

NATIVE_FIELDS = (
    "type", "title", "status", "detail", "instance",
    "error", "message", "timestamp", "path", "traceId", "failureClass",
)

APP_ARGS = [
    "--rag.rate-limit.enabled=true",
    "--rag.rate-limit.requests-per-minute=20",
    "--rag.timeout.chat-ask-ms=800",
    # OpenAI 兼容入口默认关闭（matchIfMissing=false）；本脚本要验证它的
    # 信封与原生信封不会混用，所以显式打开。
    "--rag.openai-compatibility.enabled=true",
    # 打开兼容入口后注册表要求至少一个 alias，否则应用启动即失败；
    # candidates 留空表示复用全局 primary/fallback 模型。
    "--rag.openai-compatibility.models.mock.mode=KNOWLEDGE",
]

# 第二阶段（上游 500）不能沿用 800ms 的 ask 超时：mock 的 500 会触发重试与退避，
# 请求会先撞上超时，测到 504 而不是 500。其余配置保持一致。
PHASE_TWO_ARGS = [arg for arg in APP_ARGS
                  if not arg.startswith("--rag.timeout.chat-ask-ms")]

failures: list[str] = []


def check(condition: bool, message: str) -> None:
    if condition:
        print(f"  ok   {message}")
    else:
        failures.append(message)
        print(f"  FAIL {message}")


def assert_native(label: str, response: tuple[int, dict[str, str], str],
                  expected_status: int, expected_error: str,
                  *, expect_path: str | None = None) -> dict:
    status, headers, text = response
    print(f"[{label}] HTTP {status}")
    check(status == expected_status, f"{label}: status {status} == {expected_status}")
    check("application/problem+json" in headers.get("content-type", ""),
          f"{label}: content-type is problem+json ({headers.get('content-type')})")

    try:
        body = json.loads(text)
    except json.JSONDecodeError:
        check(False, f"{label}: body is JSON ({text[:120]!r})")
        return {}

    for field in NATIVE_FIELDS:
        check(field in body, f"{label}: body has {field}")
    check(body.get("error") == expected_error,
          f"{label}: error is {expected_error} ({body.get('error')})")
    check(body.get("status") == expected_status,
          f"{label}: body status matches HTTP status")
    check(bool(body.get("failureClass")), f"{label}: body has failureClass")
    check(headers.get("x-rag-failure-class") == body.get("failureClass"),
          f"{label}: X-RAG-Failure-Class agrees with body "
          f"({headers.get('x-rag-failure-class')} vs {body.get('failureClass')})")
    check(headers.get("x-trace-id") == body.get("traceId"),
          f"{label}: body traceId matches X-Trace-Id header "
          f"({body.get('traceId')} vs {headers.get('x-trace-id')})")
    if expect_path is not None:
        check(body.get("path") == expect_path,
              f"{label}: path is {expect_path} ({body.get('path')})")
    return body


def assert_no_internal_leak(label: str, body: dict) -> None:
    blob = json.dumps(body)
    for needle in ("java.lang.", "java.util.", "com.springairag.", "org.springframework.",
                   "com.example.", "\tat ", "StackTraceElement"):
        check(needle not in blob,
              f"{label}: body does not contain {needle!r}")


def post_json(url: str, payload: dict, *, api_key: str | None = ROOT_API_KEY,
              extra_headers: dict[str, str] | None = None) -> tuple[int, dict[str, str], str]:
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["X-API-Key"] = api_key
    headers.update(extra_headers or {})
    return http_request_full("POST", url, headers=headers, body=json.dumps(payload).encode())


def get(url: str, *, api_key: str | None = ROOT_API_KEY) -> tuple[int, dict[str, str], str]:
    headers = {"X-API-Key": api_key} if api_key else {}
    return http_request_full("GET", url, headers=headers)


def phase_one(stack: LocalStack, base: str) -> None:
    print("\n== phase 1: client errors, conflict, rate limit, timeout, container exit ==")

    missing_key = get(f"{base}/api/v1/rag/documents", api_key=None)
    assert_native("401 missing key", missing_key, 401, "UNAUTHORIZED")

    malformed = http_request_full(
        "POST", f"{base}/api/v1/rag/search",
        headers={"Content-Type": "application/json", "X-API-Key": ROOT_API_KEY},
        body=b"{not-json")
    assert_native("400 malformed body", malformed, 400, "INVALID_REQUEST_BODY")

    # 用未声明的路由做 404：拿一个"类型不匹配"的 id 去撞带类型参数的端点会先得到
    # 400 TYPE_MISMATCH，那是参数错误，不是"资源不存在"。
    not_found = get(f"{base}/api/v1/rag/definitely-not-a-route")
    assert_native("404 unknown route", not_found, 404, "NOT_FOUND", expect_path="/api/v1/rag/definitely-not-a-route")

    key = "err-contract-952"
    first = post_json(f"{base}/api/v1/rag/collections",
                      {"collectionKey": key, "name": "Error Contract A"},
                      extra_headers={"Idempotency-Key": "err-contract-idem-952"})
    check(first[0] in (200, 201), f"409 setup: first create succeeded ({first[0]})")
    conflict = post_json(f"{base}/api/v1/rag/collections",
                         {"collectionKey": key, "name": "Error Contract B"},
                         extra_headers={"Idempotency-Key": "err-contract-idem-952"})
    assert_native("409 idempotency conflict", conflict, 409, "IDEMPOTENCY_KEY_REUSED")

    timed_out = post_json(f"{base}/api/v1/rag/chat/ask",
                          {"message": "explain the timeout path"})
    assert_native("504 chat timeout", timed_out, 504, "CHAT_TIMEOUT")

    container = get(f"{base}/error", api_key=None)
    assert_native("500 container /error", container, 500, "INTERNAL_ERROR")

    # 限流放在最后：前面的已认证请求各占一个名额。
    limited = None
    for attempt in range(30):
        limited = get(f"{base}/api/v1/rag/documents")
        if limited[0] == 429:
            print(f"  rate limited after {attempt + 1} request(s)")
            break
    assert_native("429 rate limit", limited, 429, "TOO_MANY_REQUESTS")

    openai_401 = get(f"{base}/v1/models", api_key=None)
    status, headers, text = openai_401
    check(status == 401, f"OpenAI 401: status {status}")
    check(headers.get("x-rag-failure-class") == "AUTHENTICATION",
          f"OpenAI 401: header AUTHENTICATION ({headers.get('x-rag-failure-class')})")
    envelope = json.loads(text)
    check("error" in envelope and envelope["error"].get("type") == "authentication_error",
          "OpenAI 401: OpenAI envelope shape")
    # `error` 是两个信封的公共键，不能拿它判断混用；看原生独有的字段。
    native_only = ("type", "title", "status", "detail", "instance",
                   "timestamp", "path", "traceId", "failureClass")
    check(not any(field in envelope for field in native_only),
          "OpenAI 401: does not mix the native envelope")


def phase_two(stack: LocalStack, base: str) -> None:
    print("\n== phase 2: upstream 500 must not leak internals ==")
    upstream = post_json(f"{base}/api/v1/rag/chat/ask",
                         {"message": "explain the upstream failure path"})
    body = assert_native("500 upstream", upstream, 500, "INTERNAL_ERROR")
    assert_no_internal_leak("500 upstream", body)

    openai_upstream = post_json(f"{base}/v1/chat/completions",
                                {"model": "mock", "messages": [{"role": "user", "content": "hi"}]})
    status, headers, text = openai_upstream
    # OpenAI 专属 handler 对无法归类成 RagException 的异常固定返回
    # 503/service_unavailable（既有设计，见 UPSTREAM_CLIENT_RECOVERY_PLAN），
    # 分类头因此是 DEPENDENCY_UNAVAILABLE；这里锁住"5xx 仍然带分类头且不泄漏"。
    check(status == 503, f"OpenAI 5xx: status {status}")
    check(headers.get("x-rag-failure-class") == "DEPENDENCY_UNAVAILABLE",
          f"OpenAI 5xx: header DEPENDENCY_UNAVAILABLE ({headers.get('x-rag-failure-class')})")
    envelope = json.loads(text)
    blob = json.dumps(envelope)
    for needle in ("java.lang.", "com.springairag.", "\tat "):
        check(needle not in blob, f"OpenAI 500: body does not contain {needle!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true",
                        help="保留临时库与日志用于排查")
    args = parser.parse_args()

    stack = LocalStack("errcontract", APP_PORT, MOCK_PORT, keep=args.keep)
    try:
        stack.create_database()
        stack.start_mock(delay_ms=2000)
        stack.build_demo_if_needed()
        base = stack.start_app(extra_args=APP_ARGS)
        phase_one(stack, base)

        stack.stop_app()
        stack.stop_mock()
        stack.start_mock(delay_ms=0, error_rate=1.0)
        base = stack.start_app(extra_args=PHASE_TWO_ARGS)
        phase_two(stack, base)
    finally:
        if args.keep:
            print(f"临时库 {stack.db_name}，日志 {stack.log_dir}")
        else:
            stack.cleanup()

    if failures:
        print(f"\nFAILED: {len(failures)} assertion(s)")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("\nPASS: every constructed failure path returned the unified body and header")
    return 0


if __name__ == "__main__":
    sys.exit(main())
