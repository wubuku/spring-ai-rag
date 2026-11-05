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


def heap_live_objects(pid: int, class_name: str) -> int | None:
    """用 jcmd 堆直方图取某类的存活实例数；不可用时返回 None。"""
    result = subprocess.run(
        ["jcmd", str(pid), "GC.class_histogram"],
        capture_output=True, text=True)
    if result.returncode != 0:
        return None
    for line in result.stdout.splitlines():
        parts = line.split()
        if parts and parts[-1] == class_name:
            try:
                return int(parts[-3])
            except (IndexError, ValueError):
                return None
    return 0


SSE_EMITTER_CLASS = "org.springframework.web.servlet.mvc.method.annotation.SseEmitter"


def stream_once(base_url: str, api_key: str, session_id: str,
                *, disconnect_after_content: bool = False,
                idempotency_key: str | None = None,
                last_event_id: str | None = None,
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
    return _consume_stream(request, timeout,
                           disconnect_after_content=disconnect_after_content,
                           idempotency_key=idempotency_key,
                           last_event_id=last_event_id)


def _consume_stream(request, timeout: float, *,
                    disconnect_after_content: bool = False,
                    idempotency_key: str | None = None,
                    last_event_id: str | None = None) -> dict:
    """读取 SSE 流，记录事件名、`id:` 序列、心跳注释与响应头。"""
    if idempotency_key:
        request.add_header("Idempotency-Key", idempotency_key)
    if last_event_id is not None:
        request.add_header("Last-Event-ID", last_event_id)
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
            "headers": {k.lower(): v for k, v in error.headers.items()},
            "events": [],
            "ids": [],
            "heartbeats": 0,
        }
    except Exception as error:  # noqa: BLE001 - 记录任何网络失败
        return {"status": 0, "body": str(error), "headers": {},
                "events": [], "ids": [], "heartbeats": 0,
                "elapsed_ms": int((time.time() - started) * 1000)}

    events: list[str] = []
    ids: list[str] = []
    heartbeats = 0
    done = False
    body_tail: list[str] = []
    status = response.status
    headers = {k.lower(): v for k, v in response.headers.items()}
    try:
        current = None
        for raw in response:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if line.startswith(":"):
                heartbeats += 1
                continue
            if line.startswith("id:"):
                ids.append(line[len("id:"):].strip())
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
        "headers": headers,
        "events": events,
        "ids": ids,
        "heartbeats": heartbeats,
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


def replay_check(base_url: str) -> dict:
    """验证 `Last-Event-ID` 断线续传：只补未消费事件，非法游标整轮重放。"""
    key = f"sse-replay-{now_ms()}"
    session = f"sse-replay-session-{now_ms()}"
    first = stream_once(base_url, ROOT_API_KEY, session,
                        idempotency_key=key, timeout=90)
    report: dict = {"first": {
        "status": first["status"],
        "events": first["events"],
        "ids": first["ids"],
        "headers": {k: v for k, v in first["headers"].items()
                    if k.startswith("x-rag-")},
        "elapsed_ms": first["elapsed_ms"],
    }}
    if first["status"] != 200:
        report["ok"] = False
        report["error"] = first.get("body", "")[:300]
        return report

    content_id = first["ids"][0] if first["ids"] else "0"
    resumed = stream_once(base_url, ROOT_API_KEY, session,
                          idempotency_key=key, last_event_id=content_id,
                          timeout=90)
    full = stream_once(base_url, ROOT_API_KEY, session,
                       idempotency_key=key, last_event_id="not-a-number",
                       timeout=90)
    report["resumed"] = {
        "status": resumed["status"],
        "events": resumed["events"],
        "ids": resumed["ids"],
        "replay_header": resumed["headers"].get("x-rag-idempotent-replay"),
        "turn_id": resumed["headers"].get("x-rag-turn-id"),
    }
    report["malformed_id"] = {
        "status": full["status"],
        "events": full["events"],
        "ids": full["ids"],
        "replay_header": full["headers"].get("x-rag-idempotent-replay"),
    }
    failures = []
    if first["headers"].get("x-rag-idempotent-replay") not in (None, "false"):
        failures.append("首次 keyed 请求不应标记为重放")
    if resumed["status"] != 200 or resumed["headers"].get(
            "x-rag-idempotent-replay") != "true":
        failures.append("续传请求未命中幂等重放路径")
    if "content" in resumed["events"]:
        failures.append("续传请求重放了已消费的 content 事件")
    if "done" not in resumed["events"]:
        failures.append("续传请求没有补发 done 事件")
    if resumed["ids"] and any(int(i) <= int(content_id) for i in resumed["ids"]):
        failures.append("续传请求的 id 未推进到 Last-Event-ID 之后")
    if full["status"] != 200 or "content" not in full["events"]:
        failures.append("非法 Last-Event-ID 应降级为整轮重放（含 content）")
    report["failures"] = failures
    report["ok"] = not failures
    return report


def thread_breakdown(pid: int) -> dict | None:
    """按线程名前缀统计线程数，用于区分业务线程与 Tomcat 线程池扩展。"""
    result = subprocess.run(["jcmd", str(pid), "Thread.print"],
                            capture_output=True, text=True)
    if result.returncode != 0:
        return None
    counts: dict[str, int] = {}
    for line in result.stdout.splitlines():
        if not line.startswith('"'):
            continue
        name = line.split('"')[1]
        prefix = name.split()[0] if name.split() else name
        counts[prefix] = counts.get(prefix, 0) + 1
    return dict(sorted(counts.items()))


def stop_mock(stack):
    """停止 mock provider：它按 SIGTERM 优雅关闭，但会等待存量长连接，
    因此必须有界等待后强杀，否则同一端口无法重启下一阶段。"""
    stack.mock_process.terminate()
    try:
        stack.mock_process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        stack.mock_process.kill()
        stack.mock_process.wait(timeout=5)
    deadline = time.time() + 10
    while time.time() < deadline:
        if not port_in_use(MOCK_PORT):
            return
        time.sleep(0.2)


def port_in_use(port):
    result = subprocess.run(["lsof", "-ti", f":{port}", "-sTCP:LISTEN"],
                            capture_output=True, text=True)
    return bool(result.stdout.strip())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--burst", type=int, default=20,
                        help="并发流数量（默认 20；不得超过 64）")
    parser.add_argument("--keep", action="store_true", help="保留临时目录")
    parser.add_argument("--skip-idle", action="store_true",
                        help="跳过上游空闲超时场景")
    parser.add_argument("--output", type=Path, default=None,
                        help="把结果 JSON 写到指定文件")
    parser.add_argument("--stream-seconds", type=float, default=20,
                        help="并发长连接保持时长（默认 20s，覆盖多个心跳周期）")
    args = parser.parse_args()
    if not 1 <= args.burst <= MAX_CONFIGURED_CONNECTIONS:
        parser.error(f"--burst 必须在 1..{MAX_CONFIGURED_CONNECTIONS} 之间；"
                     "本脚本不做无限加压")
    if args.stream_seconds < 6:
        parser.error("--stream-seconds 至少为 6（否则覆盖不到心跳与采样点）")

    stack = LocalStack("sse_conc", APP_PORT, MOCK_PORT, keep=args.keep)
    report: dict = {"burst": args.burst}
    try:
        stack.build_demo_if_needed()
        stack.create_database()
        # 每个流约 1.5s 上游延迟 + N*200ms 输出；N 由 --stream-seconds 决定，
        # 让连接在多个心跳周期里保持，而不是只活 6 秒。
        chunks = max(24, int(args.stream_seconds * 1000 / 200))
        stack.start_mock(delay_ms=1500, stream_delay_ms=200, stream_chunks=chunks)
        timeout_ms = max(6000, int(args.stream_seconds * 1000) + 5000)
        env = production_env(stack, read_timeout_ms=timeout_ms,
                             heartbeat_seconds=1)
        base_url = stack.start_app(extra_env=env["extra_env"],
                                   extra_args=env["extra_args"])
        pid = stack.app_process.pid

        # 预热一次，避免首个流的类加载/JIT 被计入连接成本。
        stream_once(base_url, ROOT_API_KEY, f"warmup-{now_ms()}")

        baseline_rss = process_rss_kb(pid)
        baseline_threads = process_thread_count(pid)
        baseline_active = measure(base_url, "request")
        baseline_emitters = heap_live_objects(pid, SSE_EMITTER_CLASS)
        baseline_breakdown = thread_breakdown(pid)
        report["baseline"] = {
            "rss_kb": baseline_rss,
            "threads": baseline_threads,
            "active_requests": baseline_active,
            "heartbeat_threads": heartbeat_threads(pid),
            "sse_emitters": baseline_emitters,
            "thread_breakdown": baseline_breakdown,
        }

        print(f"基线：线程 {baseline_threads}，RSS {baseline_rss} KB，"
              f"active={baseline_active}，SseEmitter={baseline_emitters}")
        run = burst(base_url, args.burst)
        # 采样点放在保持期中段：既避开建连峰值，又能保证流仍在持续输出。
        sample_delay = min(max(2.5, args.stream_seconds * 0.4),
                           args.stream_seconds - 1)
        time.sleep(sample_delay)
        peak_rss = process_rss_kb(pid)
        peak_threads = process_thread_count(pid)
        peak_active = measure(base_url, "request")
        peak_heartbeats = heartbeat_threads(pid)
        print(f"保持期采样（{sample_delay:.1f}s）：线程 {peak_threads}，"
              f"RSS {peak_rss} KB，active={peak_active}，"
              f"heartbeat 线程 {len(peak_heartbeats)}")

        for thread in run["threads"]:
            thread.join(timeout=args.stream_seconds + 60)
        results = run["results"]
        accepted = [r for r in results if r["status"] == 200]
        rejected = [r for r in results if r["status"] == 429]
        other = [r for r in results if r["status"] not in (200, 429)]
        released = wait_until(
            lambda: measure(base_url, "request") <= baseline_active,
            20, "并发结束后 active_request 回落到基线")
        # 给 SseEmitter 完成回调与 Tomcat 收尾留出时间，再看资源是否回收。
        time.sleep(2)
        after_rss = process_rss_kb(pid)
        after_threads = process_thread_count(pid)
        after_heartbeats = heartbeat_threads(pid)
        after_emitters = heap_live_objects(pid, SSE_EMITTER_CLASS)
        after_breakdown = thread_breakdown(pid)

        report["burst_result"] = {
            "accepted": len(accepted),
            "rejected_429": len(rejected),
            "other_status": [{"status": r["status"], "body": r["body"][:200]}
                             for r in other],
            "rejection_codes": sorted({r["error_code"] for r in rejected}),
            "stream_seconds": args.stream_seconds,
            "heartbeats": {
                "min": min((r.get("heartbeats", 0) for r in accepted), default=0),
                "max": max((r.get("heartbeats", 0) for r in accepted), default=0),
            },
            "completed": sum(1 for r in accepted if r.get("done")),
            "peak": {"rss_kb": peak_rss, "threads": peak_threads,
                     "active_requests": peak_active,
                     "heartbeat_threads": len(peak_heartbeats)},
            "after": {"rss_kb": after_rss, "threads": after_threads,
                      "active_requests": measure(base_url, "request"),
                      "heartbeat_threads": len(after_heartbeats),
                      "sse_emitters": after_emitters,
                      "thread_breakdown": after_breakdown},
            "released_to_baseline": released,
        }

        # 第二轮同样规模的长连接：单轮之后线程可能仍停在 Tomcat keep-alive
        # 池里（默认 60s 才回收），真正要证明的是**不累积**。
        second = burst(base_url, args.burst)
        for thread in second["threads"]:
            thread.join(timeout=args.stream_seconds + 60)
        second_accepted = [r for r in second["results"] if r["status"] == 200]
        second_released = wait_until(
            lambda: measure(base_url, "request") <= baseline_active,
            20, "第二轮长连接结束后 active_request 回落到基线")
        time.sleep(2)
        after2_rss = process_rss_kb(pid)
        after2_threads = process_thread_count(pid)
        after2_emitters = heap_live_objects(pid, SSE_EMITTER_CLASS)
        after2_breakdown = thread_breakdown(pid)
        report["second_burst_result"] = {
            "accepted": len(second_accepted),
            "completed": sum(1 for r in second_accepted if r.get("done")),
            "heartbeats_min": min((r.get("heartbeats", 0)
                                   for r in second_accepted), default=0),
            "after": {"rss_kb": after2_rss, "threads": after2_threads,
                      "sse_emitters": after2_emitters,
                      "thread_breakdown": after2_breakdown},
            "released_to_baseline": second_released,
        }
        accepted = accepted + second_accepted

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

        report["last_event_id"] = replay_check(base_url)

        if not args.skip_idle:
            # 上游在发完响应头后长时间不吐 token：真实连接由 read-timeout
            # 兜底；心跳仍会让客户端看到连接"活着"，但不会无限保留上游。
            stop_mock(stack)
            idle_timeout_s = timeout_ms / 1000
            stack.start_mock(
                delay_ms=1500, stream_delay_ms=200, stream_chunks=24,
                stream_first_delay_ms=int(idle_timeout_s * 1000) + 4000)
            started = time.time()
            idle = stream_once(base_url, ROOT_API_KEY, f"idle-{now_ms()}",
                               timeout=idle_timeout_s + 20)
            duration = time.time() - started
            idle_released = wait_until(
                lambda: measure(base_url, "request") <= baseline_active,
                15, "上游空闲超时后 active_request 回落到基线")
            report["idle_result"] = {
                "status": idle["status"],
                "events": idle.get("events", []),
                "elapsed_ms": round(duration * 1000),
                "expected_timeout_s": idle_timeout_s,
                "released": idle_released,
            }

        failures = []
        first = report["burst_result"]
        second = report["second_burst_result"]
        if first["accepted"] != min(args.burst, 16):
            failures.append(
                f"预期单 API Key 接纳 16 条（per-principal 上限），"
                f"实际 {first['accepted']}")
        if len(rejected) != max(0, args.burst - 16):
            failures.append(
                f"预期 {max(0, args.burst - 16)} 条 429，实际 {len(rejected)}")
        if other:
            failures.append(f"出现既非 200 也非 429 的结果：{other}")
        if peak_heartbeats and len(peak_heartbeats) > 2:
            failures.append(
                f"心跳线程数 {len(peak_heartbeats)} > 2：每个连接一个调度线程的泄漏")
        if peak_threads - baseline_threads > len(accepted) * 3 + 12:
            failures.append(
                f"并发期间线程增量 {peak_threads - baseline_threads} 过大"
                f"（{len(accepted)} 条连接；含 Tomcat/上游异步线程）")
        if not released:
            failures.append("并发结束后 admission lease 没有回落")
        if not second["released_to_baseline"]:
            failures.append("第二轮长连接结束后 admission lease 没有回落")
        if not disconnect_released:
            failures.append("客户端断开后 admission lease 没有释放")
        if not args.skip_idle and not report["idle_result"]["released"]:
            failures.append("上游空闲超时后 admission lease 没有释放")
        if (not args.skip_idle and "error" not in report["idle_result"]["events"]):
            failures.append("上游空闲超时没有以 error 事件结束")
        if (not args.skip_idle
                and abs(report["idle_result"]["elapsed_ms"] / 1000
                        - report["idle_result"]["expected_timeout_s"]) > 6):
            failures.append("上游空闲未被 read-timeout 有界收口")
        expected_heartbeats = max(1, int(args.stream_seconds) - 3)
        if first["accepted"] and first["heartbeats"]["min"] < expected_heartbeats:
            failures.append(
                f"长连接心跳不足：最少 {first['heartbeats']['min']} 次，"
                f"期望 ≥ {expected_heartbeats}")
        if second["heartbeats_min"] < expected_heartbeats:
            failures.append(
                f"第二轮长连接心跳不足：最少 {second['heartbeats_min']} 次")
        if first["completed"] != first["accepted"]:
            failures.append("存在被接纳但未正常收到 done 的流")
        if second["completed"] != second["accepted"]:
            failures.append("第二轮存在被接纳但未正常收到 done 的流")
        if after2_threads - after_threads > 6:
            failures.append(
                f"第二轮长连接后线程继续增长：{after2_threads} vs 第一轮 {after_threads}")
        if (baseline_emitters is not None and after2_emitters is not None
                and after2_emitters > baseline_emitters + 2):
            failures.append(
                f"SseEmitter 未回收：第二轮后存活 {after2_emitters} vs 基线 "
                f"{baseline_emitters}")
        if after2_rss > baseline_rss * 1.6 + 65_536:
            failures.append(
                f"两轮长连接后 RSS 未回落：{after2_rss} KB vs 基线 {baseline_rss} KB")
        if after_breakdown and after2_breakdown:
            for prefix, count in after2_breakdown.items():
                if count > after_breakdown.get(prefix, 0) + 4:
                    failures.append(
                        f"线程前缀 {prefix} 在两轮之间累积："
                        f"{after_breakdown.get(prefix, 0)} -> {count}")
        if not report["last_event_id"].get("ok"):
            failures.extend(report["last_event_id"].get("failures", []))

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
