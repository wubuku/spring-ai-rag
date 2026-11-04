#!/usr/bin/env python3
"""SSE 并发连接资源边界验证（真实进程 + 有界并发）。

回答四个问题，全部用运行中的进程测量，不靠读源码推断：

  1. 单实例稳定维系的并发 SSE 连接数上限是多少？超限时是排队还是拒绝？
  2. 每连接是否占用独立线程（或虚拟线程）？内存增量多大？
  3. 上游空闲时连接能活多久，由哪个配置项兜底？
  4. 客户端断开后，连接、admission lease 与线程是否真正释放？

并发数有硬上限：`--burst` 不得超过 64（与 `rag.admission.max-requests`
默认值一致），脚本拒绝无限加压。

真实 PostgreSQL + mock provider + demo-basic-rag 由 `scripts/lib/rag_local_stack.py`
启动；默认端口 18088/18089，不占用日常 8081。
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from lib.rag_local_stack import LocalStack, ROOT_API_KEY  # noqa: E402

APP_PORT = 18088
MOCK_PORT = 18089
MAX_CONFIGURED_CONNECTIONS = 64


def now_ms() -> int:
    return int(time.time() * 1000)


def measure(base_url: str, kind: str) -> float:
    tag = urllib.parse.quote(f"kind:{kind}")
    status, body = http_get(
        f"{base_url}/actuator/metrics/rag.admission.active?tag={tag}")
    if status != 200:
        raise RuntimeError(f"读取 admission 指标失败：HTTP {status} {body[:200]}")
    payload = json.loads(body)
    return float(payload["measurements"][0]["value"])


def http_get(url: str, timeout: float = 5.0) -> tuple[int, str]:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def process_rss_kb(pid: int) -> int:
    result = subprocess.run(
        ["ps", "-o", "rss=", "-p", str(pid)],
        capture_output=True, text=True)
    if result.returncode != 0 or not result.stdout.strip():
        return 0
    return int(result.stdout.strip().splitlines()[0])


def process_thread_count(pid: int) -> int:
    """用 jcmd Thread.print 数真实线程；不可用时退回 ps -M。"""
    result = subprocess.run(
        ["jcmd", str(pid), "Thread.print"],
        capture_output=True, text=True)
    if result.returncode == 0:
        return sum(1 for line in result.stdout.splitlines()
                   if line.startswith('"'))
    result = subprocess.run(
        ["ps", "-M", "-p", str(pid)], capture_output=True, text=True)
    return max(0, len(result.stdout.splitlines()) - 1)


def heartbeat_threads(pid: int) -> list[str]:
    result = subprocess.run(
        ["jcmd", str(pid), "Thread.print"],
        capture_output=True, text=True)
    if result.returncode != 0:
        return []
    names = [line.strip() for line in result.stdout.splitlines()
             if line.startswith('"') and "sse-heartbeat" in line]
    return names


def stream_once(base_url: str, api_key: str, session_id: str,
                *, disconnect_after_content: bool = False,
                timeout: float = 90.0) -> dict:
    payload = json.dumps({
        "message": "Reply READY.",
        "mode": "PLAIN",
        "sessionId": session_id,
    }).encode()
    request = urllib.request.Request(
        f"{base_url}/api/v1/rag/chat/stream", data=payload, method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            "X-API-Key": api_key,
        })
    started = time.time()
    try:
        response = urllib.request.urlopen(request, timeout=timeout)
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", "replace")
        return {
            "status": error.code,
            "body": body,
            "error_code": extract_error_code(body),
            "elapsed_ms": int((time.time() - started) * 1000),
        }
    except Exception as error:  # noqa: BLE001 - 记录任何网络失败
        return {"status": 0, "body": str(error), "elapsed_ms": int((time.time() - started) * 1000)}

    events: list[str] = []
    done = False
    body_tail: list[str] = []
    status = response.status
    try:
        current = None
        for raw in response:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if line.startswith(":"):
                continue
            if line.startswith("event:"):
                current = line[len("event:"):].strip()
                events.append(current)
                if current == "done":
                    done = True
                    break
                if current == "error":
                    done = True
                    break
                if disconnect_after_content and current == "content":
                    response.close()
                    break
            if line.startswith("data:"):
                body_tail.append(line)
                if len(body_tail) > 8:
                    body_tail.pop(0)
    finally:
        try:
            response.close()
        except Exception:  # noqa: BLE001 - 关闭失败不改变结论
            pass

    body = "\n".join(body_tail)
    return {
        "status": status,
        "events": events,
        "done": done,
        "body": body,
        "error_code": extract_error_code(body),
        "elapsed_ms": int((time.time() - started) * 1000),
        "disconnected": disconnect_after_content,
    }


def extract_error_code(body: str) -> str:
    if not body:
        return ""
    try:
        payload = json.loads(body)
    except json.JSONDecodeError:
        return ""
    for key in ("code", "errorCode", "error", "message"):
        value = payload.get(key)
        if isinstance(value, str) and value:
            return value
    return ""


def wait_until(predicate, timeout: float, label: str) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(0.25)
    print(f"[WARN] 等待超时：{label}", file=sys.stderr)
    return False


def burst(base_url: str, count: int) -> dict:
    barrier = threading.Barrier(count)
    results: list[dict] = [None] * count  # type: ignore[list-item]

    def worker(index: int) -> None:
        barrier.wait()
        results[index] = stream_once(
            base_url, ROOT_API_KEY, f"sse-concurrency-{index}-{now_ms()}")

    threads = [threading.Thread(target=worker, args=(i,), daemon=True)
               for i in range(count)]
    for thread in threads:
        thread.start()
    return {"threads": threads, "results": results}


def production_env(stack: LocalStack, *, read_timeout_ms: int,
                   heartbeat_seconds: int) -> dict:
    return {
        "extra_env": {
            # 让 16 个流都能进入模型阶段，从而把连接维持到压测采样点；
            # 请求 admission 的上限保持默认 64/16，才是本次要测的边界。
            "RAG_ADMISSION_MAX_MODEL_CALLS": "64",
            "RAG_ADMISSION_MAX_MODEL_CALLS_PER_PRINCIPAL": "64",
            "RAG_ADMISSION_MAX_MODEL_CALLS_PER_PROVIDER": "64",
            "RAG_ADMISSION_MAX_EMBEDDING_CALLS": "64",
        },
        "extra_args": [
            f"--rag.timeout.read-timeout-ms={read_timeout_ms}",
            # 流式响应的硬上限与 chat 执行预算一致；idle 场景用它验证
            # 上游长期不吐 token 时连接会被回收。
            f"--rag.timeout.chat-stream-ms={read_timeout_ms}",
            f"--rag.sse.heartbeat-interval-seconds={heartbeat_seconds}",
            "--management.endpoints.web.exposure.include=health,metrics",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--burst", type=int, default=20,
                        help="并发流数量（默认 20；不得超过 64）")
    parser.add_argument("--keep", action="store_true", help="保留临时目录")
    parser.add_argument("--skip-idle", action="store_true",
                        help="跳过上游空闲超时场景")
    parser.add_argument("--output", type=Path, default=None,
                        help="把结果 JSON 写到指定文件")
    args = parser.parse_args()
    if not 1 <= args.burst <= MAX_CONFIGURED_CONNECTIONS:
        parser.error(f"--burst 必须在 1..{MAX_CONFIGURED_CONNECTIONS} 之间；"
                     "本脚本不做无限加压")

    stack = LocalStack("sse_conc", APP_PORT, MOCK_PORT, keep=args.keep)
    report: dict = {"burst": args.burst}
    try:
        stack.build_demo_if_needed()
        stack.create_database()
        # 每个流约 1.5s 上游延迟 + 24*200ms 输出 ≈ 6.3s，足够覆盖采样与
        # 心跳；这是"长连接"而不是"永远不结束"。
        stack.start_mock(delay_ms=1500, stream_delay_ms=200, stream_chunks=24)
        env = production_env(stack, read_timeout_ms=6000, heartbeat_seconds=1)
        base_url = stack.start_app(extra_env=env["extra_env"],
                                   extra_args=env["extra_args"])
        pid = stack.app_process.pid

        # 预热一次，避免首个流的类加载/JIT 被计入连接成本。
        stream_once(base_url, ROOT_API_KEY, f"warmup-{now_ms()}")

        baseline_rss = process_rss_kb(pid)
        baseline_threads = process_thread_count(pid)
        baseline_active = measure(base_url, "request")
        report["baseline"] = {
            "rss_kb": baseline_rss,
            "threads": baseline_threads,
            "active_requests": baseline_active,
            "heartbeat_threads": heartbeat_threads(pid),
        }

        print(f"基线：线程 {baseline_threads}，RSS {baseline_rss} KB，active={baseline_active}")
        run = burst(base_url, args.burst)
        # 16 个被接纳的流会在 ~1.5s 内陆续进入模型调用；2.5s 时采样
        # 既能避开建连峰值，又能保证它们仍在流式输出。
        time.sleep(2.5)
        peak_rss = process_rss_kb(pid)
        peak_threads = process_thread_count(pid)
        peak_active = measure(base_url, "request")
        peak_heartbeats = heartbeat_threads(pid)
        print(f"并发峰值：线程 {peak_threads}，RSS {peak_rss} KB，"
              f"active={peak_active}，heartbeat 线程 {len(peak_heartbeats)}")

        for thread in run["threads"]:
            thread.join(timeout=90)
        results = run["results"]
        accepted = [r for r in results if r["status"] == 200]
        rejected = [r for r in results if r["status"] == 429]
        other = [r for r in results if r["status"] not in (200, 429)]
        released = wait_until(
            lambda: measure(base_url, "request") <= baseline_active,
            20, "并发结束后 active_request 回落到基线")
        after_rss = process_rss_kb(pid)
        after_threads = process_thread_count(pid)
        after_heartbeats = heartbeat_threads(pid)

        report["burst_result"] = {
            "accepted": len(accepted),
            "rejected_429": len(rejected),
            "other_status": [{"status": r["status"], "body": r["body"][:200]}
                             for r in other],
            "rejection_codes": sorted({r["error_code"] for r in rejected}),
            "peak": {"rss_kb": peak_rss, "threads": peak_threads,
                     "active_requests": peak_active,
                     "heartbeat_threads": len(peak_heartbeats)},
            "after": {"rss_kb": after_rss, "threads": after_threads,
                      "active_requests": measure(base_url, "request"),
                      "heartbeat_threads": len(after_heartbeats)},
            "released_to_baseline": released,
        }

        # 客户端断开：服务端应在下一次写入失败时释放连接与 admission lease。
        disconnect = stream_once(
            base_url, ROOT_API_KEY, f"disconnect-{now_ms()}",
            disconnect_after_content=True)
        disconnect_released = wait_until(
            lambda: measure(base_url, "request") <= baseline_active,
            15, "客户端断开后 active_request 回落到基线")
        time.sleep(1)
        disconnect_threads = process_thread_count(pid)
        report["disconnect_result"] = {
            "status": disconnect["status"],
            "events_before_disconnect": disconnect.get("events", []),
            "elapsed_ms": disconnect.get("elapsed_ms"),
            "released": disconnect_released,
            "threads_after": disconnect_threads,
            "threads_delta": disconnect_threads - baseline_threads,
        }

        if not args.skip_idle:
            # 上游在发完响应头后长时间不吐 token：真实连接由 read-timeout
            # 兜底；心跳仍会让客户端看到连接"活着"，但不会无限保留上游。
            stack.mock_process.terminate()
            stack.mock_process.wait(timeout=10)
            stack.start_mock(delay_ms=1500, stream_delay_ms=200,
                             stream_chunks=24, stream_first_delay_ms=9000)
            started = time.time()
            idle = stream_once(base_url, ROOT_API_KEY, f"idle-{now_ms()}",
                               timeout=30)
            duration = time.time() - started
            idle_released = wait_until(
                lambda: measure(base_url, "request") <= baseline_active,
                15, "上游空闲超时后 active_request 回落到基线")
            report["idle_result"] = {
                "status": idle["status"],
                "events": idle.get("events", []),
                "elapsed_ms": round(duration * 1000),
                "released": idle_released,
            }

        failures = []
        if len(accepted) != min(args.burst, 16):
            failures.append(
                f"预期单 API Key 接纳 16 条（per-principal 上限），实际 {len(accepted)}")
        if len(rejected) != max(0, args.burst - 16):
            failures.append(
                f"预期 {max(0, args.burst - 16)} 条 429，实际 {len(rejected)}")
        if other:
            failures.append(f"出现既非 200 也非 429 的结果：{other}")
        if peak_heartbeats and len(peak_heartbeats) > 2:
            failures.append(
                f"心跳线程数 {len(peak_heartbeats)} > 2：每个连接一个调度线程的泄漏")
        if peak_threads - baseline_threads > len(accepted) + 4:
            failures.append(
                f"并发期间线程增量 {peak_threads - baseline_threads} 过大"
                f"（{len(accepted)} 条连接；Tomcat 线程池扩展之外还应有明显余量）")
        if not released:
            failures.append("并发结束后 admission lease 没有回落")
        if not disconnect_released:
            failures.append("客户端断开后 admission lease 没有释放")
        if not args.skip_idle and not report["idle_result"]["released"]:
            failures.append("上游空闲超时后 admission lease 没有释放")

        report["failures"] = failures
        print(json.dumps(report, ensure_ascii=False, indent=2))
        if args.output:
            args.output.write_text(
                json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        if failures:
            print("SSE 并发资源边界验证失败：", file=sys.stderr)
            for failure in failures:
                print(f"  - {failure}", file=sys.stderr)
            return 1
        print("SSE 并发资源边界验证通过。")
        return 0
    finally:
        stack.cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
