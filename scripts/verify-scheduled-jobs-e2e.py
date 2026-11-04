#!/usr/bin/env python3
"""真实进程定时任务验收：证明调度在真实时间轴上会跑，而不是只存在 bean。

背景：仓库里已有"多实例下不重复破坏数据"的断言，却没有一条证明**任务真的被
触发过**。`verify-scheduled-jobs-e2e.py` 用短周期配置把调度压到秒级，观察真实
应用进程的行为：

  1. 触发频率：`rag.data-governance.cleanup-cron` 设成每 2 秒，统计
     `ChatHistory TTL cleanup` 日志行数，12 秒内至少 4 次。
  2. 真实效果：共享配额 bucket 清理周期 1 秒、保留 1 分钟；插入一条过期行，
     轮询数据库直到删除，记录耗时；再插入一条，确认第二轮也发生（不只跑一次）。
  3. 单实例/双实例：两个进程共享同一数据库时，过期行都被清理，而未过期行
     保持不动；日志里没有 "Central data retention cleanup failed"。
  4. 重启恢复：停掉进程再启动，重新插入过期行，仍会在周期内被清理。
  5. 失败可见性：由 `DataGovernanceRetentionCoordinatorTest` 的日志断言覆盖
     （e2e 里无法在不破坏库的前提下可靠制造清理异常）。

用法：
  python3 scripts/verify-scheduled-jobs-e2e.py
  python3 scripts/verify-scheduled-jobs-e2e.py --keep
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))
from rag_local_stack import LocalStack  # noqa: E402

APP_PORT = 18093
SECOND_PORT = 18094
MOCK_PORT = 18095

APP_ARGS = [
    "--rag.data-governance.cleanup-cron=*/2 * * * * *",
    "--rag.memory.message-ttl-days=1",
    "--rag.rate-limit.enabled=true",
    "--rag.rate-limit.backend=postgresql",
    "--rag.rate-limit.strategy=principal",
    "--rag.rate-limit.cleanup-interval-seconds=1",
    "--rag.rate-limit.bucket-retention-minutes=1",
]

failures: list[str] = []


def check(condition: bool, message: str) -> None:
    if condition:
        print(f"  ok   {message}")
    else:
        failures.append(message)
        print(f"  FAIL {message}")


def seed_bucket(stack: LocalStack, principal: str, expired: bool) -> None:
    age = "30 minutes" if expired else "0 seconds"
    stack.sql(
        "INSERT INTO rag_api_rate_limit_bucket"
        "(principal_id, window_start, request_count, updated_at) VALUES "
        f"('{principal}', now() - interval '30 minutes', 5, now() - interval '{age}') "
        "ON CONFLICT DO NOTHING")

def bucket_exists(stack: LocalStack, principal: str) -> bool:
    return int(stack.sql(
        "SELECT count(*) FROM rag_api_rate_limit_bucket "
        f"WHERE principal_id = '{principal}'")) > 0


def wait_until_gone(stack: LocalStack, principal: str, timeout: float) -> float:
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        if not bucket_exists(stack, principal):
            return time.monotonic() - started
        time.sleep(0.25)
    return -1.0


def count_log(app_log: Path, needle: str) -> int:
    if not app_log.exists():
        return 0
    return app_log.read_text(errors="replace").count(needle)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true",
                        help="保留临时库与日志用于排查")
    args = parser.parse_args()

    stack = LocalStack("schedjobs", APP_PORT, MOCK_PORT, keep=args.keep)
    try:
        stack.create_database()
        stack.start_mock()
        stack.build_demo_if_needed()
        base = stack.start_app(extra_args=APP_ARGS)
        app_log = stack.log_dir / f"app-{APP_PORT}.log"

        print("\n== 1. cron actually fires at the configured period ==")
        before = count_log(app_log, "Starting chat history TTL cleanup")
        time.sleep(12)
        after = count_log(app_log, "Starting chat history TTL cleanup")
        runs = after - before
        check(runs >= 4, f"chat-history TTL cleanup ran {runs} time(s) in 12s "
                         f"(cron */2 -> expected >= 4)")
        check(count_log(app_log, "Chat history TTL cleanup completed") >= 4,
              "cleanup runs reach completion, not just the log entry")

        print("\n== 2. cleanup has a real effect, and fires more than once ==")
        stack.sql("DELETE FROM rag_api_rate_limit_bucket WHERE principal_id = 'sched-probe-a'")
        seed_bucket(stack, "sched-probe-a", expired=True)
        elapsed = wait_until_gone(stack, "sched-probe-a", 10)
        check(elapsed >= 0, f"expired bucket row deleted (took {elapsed:.2f}s)")

        seed_bucket(stack, "sched-probe-b", expired=True)
        elapsed_b = wait_until_gone(stack, "sched-probe-b", 10)
        check(elapsed_b >= 0,
              f"a second round also ran (row deleted after {elapsed_b:.2f}s), "
              "so the scheduler is not a one-shot")

        print("\n== 3. two instances share one database without corrupting it ==")
        stack.start_app(extra_args=APP_ARGS, port=SECOND_PORT, keep_existing=True)
        seed_bucket(stack, "sched-probe-c", expired=True)
        stack.sql(
            "INSERT INTO rag_api_rate_limit_bucket"
            "(principal_id, window_start, request_count, updated_at) VALUES "
            "('sched-probe-fresh', now(), 5, now()) ON CONFLICT DO NOTHING")
        check(wait_until_gone(stack, "sched-probe-c", 10) >= 0,
              "expired row deleted with two instances running")
        time.sleep(4)
        check(bucket_exists(stack, "sched-probe-fresh"),
              "unexpired row survives both instances (retention not applied blindly)")
        for log in (app_log, stack.log_dir / f"app-{SECOND_PORT}.log"):
            check(count_log(log, "Central data retention cleanup failed") == 0,
                  f"no cleanup failure logged in {log.name}")

        print("\n== 4. scheduling resumes after a restart ==")
        stack.stop_app()
        base = stack.start_app(extra_args=APP_ARGS)
        seed_bucket(stack, "sched-probe-d", expired=True)
        check(wait_until_gone(stack, "sched-probe-d", 12) >= 0,
              "cleanup runs again after a restart")
        before = count_log(app_log, "Starting chat history TTL cleanup")
        time.sleep(6)
        after = count_log(app_log, "Starting chat history TTL cleanup")
        check(after - before >= 2,
              f"cron resumed after restart ({after - before} run(s) in 6s)")
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
    print("\nPASS: scheduled jobs fire, repeat, share databases safely and survive restart")
    return 0


if __name__ == "__main__":
    sys.exit(main())
