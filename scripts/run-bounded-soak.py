#!/usr/bin/env python3
"""有界固定并发 soak：压 /search 与 /chat，输出分位数、错误率与前后半漂移。

用法：
  ./scripts/run-bounded-soak.py --base-url http://127.0.0.1:18082 --minutes 10

设计约束（与 docs/testing-guide 一致）：
- 固定时长，不无限跑；默认 10 分钟，可用 --minutes 覆盖。
- 固定并发；search 线程数 = concurrency - 1，chat 恒为 1 个线程并限速。
- 只压专用 fixture Collection，结束后尽力删除；不触碰其他数据。
- 时间戳统一用 time.monotonic()，避免前后半漂移算错。
"""
import argparse
import json
import math
import os
import pathlib
import re
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ENV_FILE = pathlib.Path(__file__).resolve().parent.parent / ".env"

# 资源趋势门禁：允许锯齿与堆扩容，不允许持续爬升。
# (相对首段中位数的倍数上限, 绝对余量)
RESOURCE_LIMITS = {
    "rss_kb": (2.0, 131_072),
    "heap_used_kb": (1.5, 65_536),
    "threads": (1.5, 8),
    "fd_count": (1.5, 20),
    "db_connections": (1.5, 4),
}


def _run(command, timeout=15):
    try:
        return subprocess.run(command, capture_output=True, text=True,
                              timeout=timeout)
    except Exception:  # noqa: BLE001 - 采样失败按 None 处理，不中断压测
        return None


def sample_threads(pid):
    result = _run(["jcmd", str(pid), "Thread.print"])
    if result is None or result.returncode != 0:
        fallback = _run(["ps", "-M", "-p", str(pid)])
        if fallback is None or fallback.returncode != 0:
            return None
        return max(0, len(fallback.stdout.splitlines()) - 1)
    return sum(1 for line in result.stdout.splitlines()
               if line.lstrip().startswith('"'))


def sample_heap_used_kb(pid):
    result = _run(["jcmd", str(pid), "GC.heap_info"])
    if result is None or result.returncode != 0:
        return None
    match = re.search(r"used\s+(\d+)K", result.stdout)
    return int(match.group(1)) if match else None


def sample_fd_count(pid):
    result = _run(["lsof", "-p", str(pid)])
    if result is None or result.returncode not in (0, 1):
        return None
    return max(0, len(result.stdout.splitlines()) - 1)


def sample_gc(pid):
    result = _run(["jstat", "-gc", str(pid), "1000", "1"])
    if result is None or result.returncode != 0:
        return None
    lines = [line.split() for line in result.stdout.splitlines() if line.strip()]
    header = next((row for row in lines if row and row[0] == "S0C"), None)
    if header is None:
        return None
    values = next((row for row in lines
                   if len(row) > 4 and row[1].replace(".", "").isdigit()), None)
    if values is None:
        return None
    data = dict(zip(header, values))
    return {
        "young_count": int(float(data.get("YGC", 0))),
        "young_pause_s": float(data.get("YGCT", 0)),
        "old_count": int(float(data.get("FGC", 0))),
        "old_pause_s": float(data.get("FGCT", 0)),
        "total_pause_s": float(data.get("GCT", 0)),
    }


def sample_db_connections(dsn):
    try:
        import psycopg2
    except ImportError:
        return None
    try:
        connection = psycopg2.connect(dsn, connect_timeout=5)
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT COUNT(*), COUNT(*) FILTER "
                "(WHERE state = 'idle in transaction') "
                "FROM pg_stat_activity WHERE datname = current_database()")
            total, idle_in_tx = cursor.fetchone()
        connection.close()
        return {"total": int(total), "idle_in_transaction": int(idle_in_tx)}
    except Exception:  # noqa: BLE001 - 采样库不可达时忽略连接指标
        return None


def analyze_trend(key, values):
    """首尾各 1/4 样本中位数对比；锯齿允许，单调爬升判失败。"""
    if len(values) < 6:
        return None
    window = max(1, len(values) // 4)
    first = statistics.median(values[:window])
    last = statistics.median(values[-window:])
    relative, absolute = RESOURCE_LIMITS.get(key, (1.5, 0))
    ratio = round(last / first, 3) if first else None
    return {
        "first_quarter_median": round(first, 2),
        "last_quarter_median": round(last, 2),
        "ratio": ratio,
        "max": round(max(values), 2),
        "min": round(min(values), 2),
        "ok": last <= first * relative + absolute,
        "limit": {"relative": relative, "absolute": absolute},
    }


def collect_resources(pid, dsn, seconds, interval):
    """固定间隔采样堆/线程/FD/DB 连接/GC，返回样本与趋势判定。"""
    samples = []
    stop = threading.Event()

    def loop():
        while not stop.is_set():
            sample = {"ts": round(time.monotonic(), 2)}
            sample["rss_kb"] = _rss(pid)
            sample["heap_used_kb"] = sample_heap_used_kb(pid)
            sample["threads"] = sample_threads(pid)
            sample["fd_count"] = sample_fd_count(pid)
            if dsn:
                connections = sample_db_connections(dsn)
                if connections:
                    sample["db_connections"] = connections["total"]
                    sample["db_idle_in_transaction"] = \
                        connections["idle_in_transaction"]
            gc = sample_gc(pid)
            if gc:
                sample["gc"] = gc
            samples.append(sample)
            stop.wait(interval)

    thread = threading.Thread(target=loop, daemon=True)
    thread.start()
    started = time.monotonic()
    while time.monotonic() - started < seconds:
        time.sleep(min(interval, 1))
    stop.set()
    thread.join(timeout=interval + 5)

    resource_keys = [key for key in RESOURCE_LIMITS
                     if any(sample.get(key) is not None for sample in samples)]
    trends = {}
    for key in resource_keys:
        values = [sample[key] for sample in samples
                  if sample.get(key) is not None]
        trend = analyze_trend(key, values)
        if trend is not None:
            trends[key] = trend

    gc_samples = [sample["gc"] for sample in samples if sample.get("gc")]
    gc = None
    if gc_samples:
        last = gc_samples[-1]
        gc = {
            "young_count": last["young_count"],
            "old_count": last["old_count"],
            "total_pause_s": round(last["total_pause_s"], 3),
            "old_pause_s": round(last["old_pause_s"], 3),
            "pause_fraction": round(last["total_pause_s"] / seconds, 4),
        }
    max_idle_in_tx = max((sample.get("db_idle_in_transaction", 0)
                          for sample in samples), default=0)
    failures = [key for key, trend in trends.items() if not trend["ok"]]
    if max_idle_in_tx > 2:
        failures.append("db_idle_in_transaction")
    return {
        "samples": samples,
        "sample_count": len(samples),
        "trends": trends,
        "gc": gc,
        "max_idle_in_transaction": max_idle_in_tx,
        "failures": failures,
        "ok": not failures,
    }


def _rss(pid):
    result = _run(["ps", "-o", "rss=", "-p", str(pid)])
    if result is None or result.returncode != 0:
        return None
    text = result.stdout.strip()
    return int(text) if text.isdigit() else None


def load_env_file(path):
    values = {}
    if not path.exists():
        return values
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key.strip()] = value
    return values


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(p / 100 * len(ordered)) - 1))
    return ordered[index]


def summarize_endpoint(kind, kind_rows, started, deadline, max_error_rate,
                       max_p95_drift):
    """把一类请求收敛为分位数、错误率与前后半漂移，并给出退化标记。"""
    ok = [row for row in kind_rows if 200 <= row["status"] < 300]
    errors = len(kind_rows) - len(ok)
    error_rate = errors / len(kind_rows) if kind_rows else None
    latencies = [row["latency_ms"] for row in ok]
    midpoint = started + (deadline - started) / 2
    early = [row["latency_ms"] for row in ok if row["ts"] < midpoint]
    late = [row["latency_ms"] for row in ok if row["ts"] >= midpoint]
    first_p95, second_p95 = percentile(early, 95), percentile(late, 95)
    drift = (round(second_p95 / first_p95, 3)
             if first_p95 and second_p95 else None)
    entry = {
        "requests": len(kind_rows),
        "ok": len(ok),
        "errors": errors,
        "error_rate": round(error_rate, 4) if error_rate is not None else None,
        "p50": percentile(latencies, 50),
        "p95": percentile(latencies, 95),
        "p99": percentile(latencies, 99),
        "avg": round(statistics.fmean(latencies), 2) if latencies else None,
        "first_half_p95": first_p95,
        "second_half_p95": second_p95,
        "p95_drift": drift,
    }
    degraded = False
    if error_rate is not None and error_rate > max_error_rate:
        degraded = True
        entry["degraded"] = "error_rate"
    if drift is not None and drift > max_p95_drift:
        degraded = True
        entry["degraded"] = "p95_drift"
    return entry, degraded


def self_test():
    """在不启动服务的情况下验证分位数、漂移与退化判定。"""
    failures = []
    base = [{"ts": float(i), "latency_ms": 10.0, "status": 200}
            for i in range(1, 101)]
    if percentile([1, 2, 3, 4], 50) != 2:
        failures.append("p50 of [1,2,3,4] should be 2")
    if percentile([], 95) is not None:
        failures.append("empty percentile should be None")
    entry, degraded = summarize_endpoint(
        "search", base, 0.0, 100.0, 0.0, 1.5)
    if (entry["p95_drift"], degraded) != (1.0, False):
        failures.append("stable latency should have drift 1.0 and no degradation")
    late = [{"ts": float(i), "latency_ms": 10.0 if i < 50 else 20.0,
             "status": 200} for i in range(1, 101)]
    entry, degraded = summarize_endpoint(
        "search", late, 0.0, 100.0, 0.0, 1.5)
    if entry["p95_drift"] != 2.0 or degraded is not True:
        failures.append("late latency should double P95 and degrade")
    mixed = base[:99] + [{"ts": 100.0, "latency_ms": 10.0, "status": 500}]
    entry, degraded = summarize_endpoint(
        "chat", mixed, 0.0, 101.0, 0.0, 1.5)
    if entry["errors"] != 1 or entry["error_rate"] != 0.01 or degraded is not True:
        failures.append("one error in 100 requests should degrade")
    if load_env_file(pathlib.Path("/nonexistent.env")):
        failures.append("missing env file should yield an empty mapping")
    if not analyze_trend("threads", [10] * 20)["ok"]:
        failures.append("flat threads should not be a growth trend")
    if analyze_trend("threads", [10] * 10 + [30] * 10)["ok"]:
        failures.append("monotonically grown threads should degrade")
    if not analyze_trend("threads", [10, 20, 9, 21, 10, 20, 11, 19] * 2)["ok"]:
        failures.append("sawtooth threads should pass the trend gate")
    if failures:
        raise SystemExit(
            "soak self-test failed: " + "; ".join(failures))
    print("Soak self-test passed.")


def main():
    parser = argparse.ArgumentParser(description="Bounded search/chat soak")
    parser.add_argument("--base-url", default=os.environ.get(
        "SOAK_BASE_URL", "http://127.0.0.1:18082"))
    parser.add_argument("--minutes", type=float, default=10)
    parser.add_argument("--concurrency", type=int, default=3)
    parser.add_argument("--max-error-rate", type=float, default=0.0,
                        help="允许的错误率上限；超过则退出码为 1")
    parser.add_argument("--max-p95-drift", type=float, default=1.5,
                        help="后半/前半 P95 比值上限；超过则退出码为 1")
    parser.add_argument("--output-dir", default="")
    parser.add_argument("--api-key", default="")
    parser.add_argument("--self-test", action="store_true",
                        help="Validate percentile/drift logic without HTTP")
    parser.add_argument("--pid", type=int, default=int(os.environ.get(
        "SOAK_APP_PID", "0") or 0),
                        help="被测 Java 进程 PID；用于堆/线程/FD 采样")
    parser.add_argument("--dsn", default=os.environ.get("SOAK_DSN", ""),
                        help="应用数据库 DSN；用于连接数/idle-in-transaction 采样")
    parser.add_argument("--sample-seconds", type=float, default=10,
                        help="资源采样间隔（默认 10s）")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0

    if args.minutes < 1:
        parser.error("--minutes 至少为 1")
    if args.concurrency < 2:
        parser.error("--concurrency 至少为 2（search + chat）")
    if args.sample_seconds < 1:
        parser.error("--sample-seconds 至少为 1")

    api_key = args.api_key or os.environ.get("RAG_API_KEY") \
        or os.environ.get("RAG_ROOT_API_KEY") or ""
    if not api_key:
        api_key = (load_env_file(ENV_FILE).get("RAG_API_KEY")
                   or load_env_file(ENV_FILE).get("RAG_ROOT_API_KEY") or "")
    if not api_key:
        print("缺少 API key：设置 RAG_API_KEY/RAG_ROOT_API_KEY 或写入 .env",
              file=sys.stderr)
        return 2

    run_id = time.strftime("%Y%m%d-%H%M%S")
    output = pathlib.Path(args.output_dir) if args.output_dir else (
        pathlib.Path(__file__).resolve().parent.parent
        / ".verification" / "soak" / run_id)
    output.mkdir(parents=True, exist_ok=True)
    rows_path = output / "requests.jsonl"
    summary_path = output / "summary.json"
    rows_path.write_text("", encoding="utf-8")

    api = args.base_url.rstrip("/") + "/api/v1/rag"
    headers = {
        "Accept": "application/json",
        "X-API-Key": api_key,
        "Content-Type": "application/json",
    }
    collection_key = f"soak-{run_id}-{os.getpid()}"
    query = "spring ai retrieval stability probe"
    lock = threading.Lock()

    def request(method, path, body=None, timeout=90):
        payload = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(api + path, data=payload,
                                     headers=headers, method=method)
        started = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as response:
                raw = response.read().decode("utf-8")
                parsed = json.loads(raw) if raw else None
                return response.status, parsed, time.monotonic() - started, None
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", errors="replace")
            return error.code, None, time.monotonic() - started, raw[:200]
        except Exception as error:  # noqa: BLE001 - soak 记录所有失败类别
            return 0, None, time.monotonic() - started, repr(error)[:200]

    status, collection, _, error = request("POST", "/collections", {
        "collectionKey": collection_key,
        "name": f"Soak {run_id}",
        "description": "Bounded soak fixture",
        "domainId": "default",
    })
    if not 200 <= status < 300:
        print(f"seed Collection 失败: HTTP {status} {error}", file=sys.stderr)
        return 1
    status, document, _, error = request("POST", "/documents", {
        "title": f"Soak document {run_id}",
        "content": ("Spring AI RAG soak document. Hybrid retrieval, rerank and "
                    "citation stability are measured under fixed concurrency. " * 40),
        "collectionKey": collection_key,
        "source": "soak",
    })
    if not 200 <= status < 300:
        print(f"seed Document 失败: HTTP {status} {error}", file=sys.stderr)
        request("DELETE", f"/collections/by-key?collectionKey={urllib.parse.quote(collection_key)}")
        return 1
    document_id = int(document["id"])
    status, embedded, _, error = request(
        "POST", f"/documents/{document_id}/embed?force=true", timeout=180)
    if not 200 <= status < 300 or str((embedded or {}).get("status", "")).upper() \
            not in {"COMPLETED", "CACHED"}:
        print(f"seed embedding 失败: HTTP {status} {error or embedded}", file=sys.stderr)
        request("DELETE", f"/collections/by-key?collectionKey={urllib.parse.quote(collection_key)}")
        return 1
    print(f"SOAK_SEED_OK collection={collection_key} document={document_id}", flush=True)

    started = time.monotonic()
    deadline = started + args.minutes * 60

    def record(kind, status, latency_s, error):
        row = {
            "ts": time.monotonic(),
            "kind": kind,
            "status": status,
            "latency_ms": round(latency_s * 1000, 2),
            "error": error,
        }
        with lock:
            with rows_path.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(row) + "\n")

    def search_worker():
        while time.monotonic() < deadline:
            params = urllib.parse.urlencode({
                "query": query, "maxResults": 3,
                "collectionKeys": collection_key,
                "useHybridSearch": "true", "useRerank": "true",
            })
            status, _, latency, error = request("GET", f"/search?{params}")
            record("search", status, latency, error)

    def chat_worker():
        while time.monotonic() < deadline:
            status, _, latency, error = request("POST", "/chat/ask", {
                "message": "Reply with exactly: OK",
                "maxResults": 2,
                "useHybridSearch": True,
                "useRerank": True,
                "collectionKeys": [collection_key],
            }, timeout=120)
            record("chat", status, latency, error)
            time.sleep(2)

    workers = [threading.Thread(target=search_worker)
               for _ in range(max(args.concurrency - 1, 1))]
    workers.append(threading.Thread(target=chat_worker))

    resource_holder = {}
    resource_thread = None
    if args.pid > 0:
        def sample_resources():
            resource_holder["resources"] = collect_resources(
                args.pid, args.dsn, args.minutes * 60 + args.sample_seconds,
                args.sample_seconds)

        resource_thread = threading.Thread(target=sample_resources, daemon=True)
        resource_thread.start()
    else:
        print("WARN: 未提供 --pid/SOAK_APP_PID，跳过堆/线程/FD 增长断言",
              file=sys.stderr)

    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join()
    elapsed = time.monotonic() - started
    if resource_thread is not None:
        resource_thread.join(timeout=args.sample_seconds + 15)

    rows = [json.loads(line) for line in
            rows_path.read_text(encoding="utf-8").splitlines() if line]
    summary = {
        "run_id": run_id,
        "elapsed_s": round(elapsed, 1),
        "minutes": args.minutes,
        "concurrency": args.concurrency,
        "collection_key": collection_key,
        "endpoints": {},
        "thresholds": {
            "max_error_rate": args.max_error_rate,
            "max_p95_drift": args.max_p95_drift,
        },
    }
    degraded = False
    for kind in ("search", "chat"):
        kind_rows = [row for row in rows if row["kind"] == kind]
        entry, entry_degraded = summarize_endpoint(
            kind, kind_rows, started, deadline,
            args.max_error_rate, args.max_p95_drift)
        summary["endpoints"][kind] = entry
        degraded = degraded or entry_degraded
    resources = resource_holder.get("resources")
    if resources is not None:
        (output / "resources.json").write_text(
            json.dumps(resources, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8")
        summary["resources"] = {
            key: value for key, value in resources.items() if key != "samples"}
        summary["resources"]["samples_path"] = "resources.json"
        if not resources["ok"]:
            degraded = True
    else:
        summary["resources"] = {"ok": None,
                                "skipped": "未提供 --pid/SOAK_APP_PID"}
    summary["degraded"] = degraded
    summary_path.write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("SOAK_DONE " + json.dumps(summary, ensure_ascii=False), flush=True)

    request("DELETE", f"/collections/by-key?collectionKey={urllib.parse.quote(collection_key)}")
    print(f"SOAK_SUMMARY {summary_path}")
    return 1 if degraded else 0


if __name__ == "__main__":
    sys.exit(main())
