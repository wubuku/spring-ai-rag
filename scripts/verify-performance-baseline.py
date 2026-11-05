#!/usr/bin/env python3
"""性能退化门禁：相对基线 + 宽松绝对上限，避免机器噪声造成 flaky。

默认读取 `scripts/testdata/performance-baseline.json` 作为基线：

  python3 scripts/verify-performance-baseline.py --run

`--run` 会用 `run-concurrency-benchmark.py --profile quick` 重新采样并比较。
`--update-baseline` 用于在受控机器上重新生成基线（CI 不调用）。

判定规则（全部满足才算通过）：

  - p95 <= 基线 p95 × 2.5，p99 <= 基线 p99 × 3.0；
  - 吞吐 >= 基线吞吐 × 0.3（共享 CI 机器允许明显降速）；
  - p95 <= 绝对上限（每个工作负载单独配置，留足余量）；
  - 错误率 <= 5%（正常压测只允许零星的连接重试）。
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
import tempfile
import time
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_BASELINE = SCRIPT_DIR / "testdata" / "performance-baseline.json"
BENCHMARK = SCRIPT_DIR / "run-concurrency-benchmark.py"

DEFAULT_TOLERANCES = {
    # 共享 CI runner 的绝对延迟不可比，用宽松倍率 + 宽松吞吐下限，只拦“数量级退化”。
    "p95Multiplier": 2.5,
    "p99Multiplier": 3.0,
    "throughputFloorRatio": 0.3,
    "maxErrorRate": 0.05,
}

DEFAULT_CAPS_MS = {
    # 绝对上限按本机基线留 2.5~3 倍余量；upload 受解析/embedding 管线限制，
    # 32 并发基线 p95 约 6.9s，因此上限放到 20s。
    "search_vector": 3000,
    "search_hybrid": 4000,
    "export_collection": 3000,
    "document_create": 4000,
    "upload_parse": 20000,
    "embedding_pipeline": 6000,
}


def load_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def compare(current, baseline):
    """返回 (violations, checked)；violations 为空即通过。"""
    tolerances = {**DEFAULT_TOLERANCES, **baseline.get("tolerances", {})}
    caps = {**DEFAULT_CAPS_MS, **baseline.get("absoluteP95CapsMs", {})}
    violations = []
    checked = 0

    current_workloads = current.get("workloads") or {}
    baseline_workloads = baseline.get("workloads") or {}
    for workload in sorted(baseline_workloads):
        for concurrency, base in sorted(baseline_workloads[workload].items()):
            cur = (current_workloads.get(workload) or {}).get(concurrency)
            if cur is None:
                violations.append(
                    f"{workload}@{concurrency}: 当前结果缺失（基线存在）")
                continue
            checked += 1
            label = f"{workload}@{concurrency}"
            if cur.get("p95") is None:
                violations.append(f"{label}: 无成功请求，p95 为空")
                continue
            base_p95 = base.get("p95")
            base_p99 = base.get("p99")
            base_rps = base.get("throughput_rps")
            if base_p95:
                limit = base_p95 * tolerances["p95Multiplier"]
                if cur["p95"] > limit:
                    violations.append(
                        f"{label}: p95 {cur['p95']}ms > 基线×{tolerances['p95Multiplier']}"
                        f" = {round(limit, 2)}ms")
            if base_p99 and cur.get("p99") is not None:
                limit = base_p99 * tolerances["p99Multiplier"]
                if cur["p99"] > limit:
                    violations.append(
                        f"{label}: p99 {cur['p99']}ms > 基线×{tolerances['p99Multiplier']}"
                        f" = {round(limit, 2)}ms")
            if base_rps and cur.get("throughput_rps") is not None:
                floor = base_rps * tolerances["throughputFloorRatio"]
                if cur["throughput_rps"] < floor:
                    violations.append(
                        f"{label}: 吞吐 {cur['throughput_rps']}rps < 基线×"
                        f"{tolerances['throughputFloorRatio']} = {round(floor, 2)}rps")
            cap = caps.get(workload)
            if cap is not None and cur["p95"] > cap:
                violations.append(
                    f"{label}: p95 {cur['p95']}ms > 绝对上限 {cap}ms")
            error_rate = cur.get("error_rate")
            if error_rate is not None and error_rate > tolerances["maxErrorRate"]:
                violations.append(
                    f"{label}: 错误率 {error_rate} > {tolerances['maxErrorRate']}")

    return violations, checked


def self_test():
    baseline = {
        "tolerances": DEFAULT_TOLERANCES,
        "absoluteP95CapsMs": DEFAULT_CAPS_MS,
        "workloads": {
            "search_vector": {
                "1": {"p95": 10, "p99": 12, "throughput_rps": 100.0},
                "32": {"p95": 50, "p99": 60, "throughput_rps": 200.0},
            }
        },
    }
    current_ok = {
        "workloads": {
            "search_vector": {
                "1": {"p95": 15, "p99": 20, "throughput_rps": 60.0,
                      "error_rate": 0.0},
                "32": {"p95": 100, "p99": 150, "throughput_rps": 90.0,
                       "error_rate": 0.0},
            }
        }
    }
    violations, checked = compare(current_ok, baseline)
    assert not violations and checked == 2, (violations, checked)

    current_bad = {
        "workloads": {
            "search_vector": {
                "1": {"p95": 1000, "p99": 2000, "throughput_rps": 1.0,
                      "error_rate": 0.5},
                "32": {"p95": 5000, "p99": 9000, "throughput_rps": 10.0,
                       "error_rate": 0.0},
            }
        }
    }
    violations, checked = compare(current_bad, baseline)
    assert checked == 2 and len(violations) >= 4, violations

    current_missing = {"workloads": {}}
    violations, checked = compare(current_missing, baseline)
    assert checked == 0 and len(violations) == 2, violations
    print("performance baseline self-test ok")


def run_benchmark(profile, output):
    command = [sys.executable, str(BENCHMARK), "--profile", profile,
               "--output", output]
    print("[gate] 运行基准：" + " ".join(command), flush=True)
    result = subprocess.run(command, cwd=str(SCRIPT_DIR.parent))
    if result.returncode != 0:
        raise SystemExit(f"基准运行失败（退出码 {result.returncode}）")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", default=str(DEFAULT_BASELINE))
    parser.add_argument("--current", default="",
                        help="已有基准 JSON；不传则用 --run 重新采样")
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--profile", choices=("quick", "standard"), default="quick")
    parser.add_argument("--update-baseline", action="store_true")
    parser.add_argument("--current-out", default="",
                        help="--run 时把本次采样 JSON 另存到该路径（CI 取证）")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0

    baseline_path = Path(args.baseline)
    if args.update_baseline:
        if not args.run:
            parser.error("--update-baseline 需要同时 --run")
        with tempfile.NamedTemporaryFile(
                mode="w", suffix=".json", delete=False) as handle:
            temp_path = handle.name
        run_benchmark(args.profile, temp_path)
        current = load_json(temp_path)
        baseline = {
            "schemaVersion": 1,
            "profile": args.profile,
            "capturedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "commit": current.get("commit"),
            "tolerances": DEFAULT_TOLERANCES,
            "absoluteP95CapsMs": DEFAULT_CAPS_MS,
            "environment": current.get("environment"),
            "fixture": current.get("fixture"),
            "workloads": current.get("workloads"),
        }
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        baseline_path.write_text(
            json.dumps(baseline, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8")
        print(f"baseline 已更新：{baseline_path}")
        return 0

    if args.current:
        current = load_json(args.current)
    elif args.run:
        with tempfile.NamedTemporaryFile(
                mode="w", suffix=".json", delete=False) as handle:
            temp_path = handle.name
        run_benchmark(args.profile, temp_path)
        current = load_json(temp_path)
    else:
        parser.error("需要 --run 或 --current")

    if args.current_out:
        output_path = Path(args.current_out)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(
            json.dumps(current, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8")

    if not baseline_path.exists():
        raise SystemExit(f"基线文件不存在：{baseline_path}")

    baseline = load_json(baseline_path)
    if current.get("profile") and baseline.get("profile") \
            and current["profile"] != baseline["profile"]:
        raise SystemExit(
            f"profile 不一致：current={current['profile']} "
            f"baseline={baseline['profile']}（请用同一 profile 比较）")

    violations, checked = compare(current, baseline)
    print(f"[gate] 已比较 {checked} 个基线条目")
    if violations:
        for line in violations:
            print("[gate][FAIL] " + line)
        return 1
    print("[gate][PASS] 未发现性能退化")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
