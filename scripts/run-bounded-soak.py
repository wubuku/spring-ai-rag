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
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ENV_FILE = pathlib.Path(__file__).resolve().parent.parent / ".env"


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
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0

    if args.minutes < 1:
        parser.error("--minutes 至少为 1")
    if args.concurrency < 2:
        parser.error("--concurrency 至少为 2（search + chat）")

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
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join()
    elapsed = time.monotonic() - started

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
    summary["degraded"] = degraded
    summary_path.write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("SOAK_DONE " + json.dumps(summary, ensure_ascii=False), flush=True)

    request("DELETE", f"/collections/by-key?collectionKey={urllib.parse.quote(collection_key)}")
    print(f"SOAK_SUMMARY {summary_path}")
    return 1 if degraded else 0


if __name__ == "__main__":
    sys.exit(main())
