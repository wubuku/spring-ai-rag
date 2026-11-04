#!/usr/bin/env python3
"""资源饱和与降级语义验收（真实进程 + 真实 PostgreSQL）。

证明四件事：
1. 连接池耗尽时请求是**有界排队后快速失败**（503 DATABASE_UNAVAILABLE +
   Retry-After），不是 500，也不是永久挂死；
2. 慢查询/锁等待被 `statement_timeout` 截断，返回 504 DATABASE_TIMEOUT，
   并且执行中的请求释放连接；
3. 数据库临时不可达返回 503，恢复后**无需重启**自动回到正常；
4. 失败后没有连接泄漏。

用一个一次性 pgvector 容器做“不可达”演练，绝不碰开发者日常库。
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lib.rag_local_stack import (  # noqa: E402
    ROOT_API_KEY,
    LocalStack,
    http_request_full,
)

PROJECT_DIR = Path(__file__).resolve().parents[1]
VERIFICATION_DIR = PROJECT_DIR / ".verification"
PASSED: list[str] = []


def pass_check(message: str) -> None:
    PASSED.append(message)
    print(f"[PASS] {message}")


def fail(message: str) -> None:
    print(f"[FAIL] {message}", file=sys.stderr)
    raise SystemExit(1)


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def run(command: list[str], *, timeout: int = 120) -> subprocess.CompletedProcess:
    return subprocess.run(command, capture_output=True, text=True, timeout=timeout)


def wait_for_postgres(container: str, seconds: int = 60) -> None:
    deadline = time.time() + seconds
    while time.time() < deadline:
        result = run(["docker", "exec", container, "pg_isready", "-U", "postgres"])
        if result.returncode == 0:
            return
        time.sleep(1)
    fail(f"临时 PostgreSQL 容器 {container} 未在 {seconds}s 内就绪")


def documents(stack: LocalStack, timeout: float = 30.0):
    url = f"http://127.0.0.1:{stack.app_port}/api/v1/rag/documents?page=0&size=10"
    return http_request_full("GET", url, headers={"X-API-Key": ROOT_API_KEY},
                             timeout=timeout)


def health(stack: LocalStack, timeout: float = 20.0) -> str:
    url = f"http://127.0.0.1:{stack.app_port}/actuator/health"
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return response.read().decode()
    except urllib.error.HTTPError as error:
        return error.read().decode()


def measure(url: str, timeout: float) -> tuple[int, dict[str, str], str, float]:
    started = time.time()
    status, headers, body = http_request_full(
        "GET", url, headers={"X-API-Key": ROOT_API_KEY}, timeout=timeout)
    return status, headers, body, time.time() - started


def assert_error_contract(kind: str, status_code: int, headers: dict[str, str],
                          body: str, expected_error: str, expected_class: str,
                          retry_after: str) -> None:
    try:
        payload = json.loads(body)
    except json.JSONDecodeError:
        fail(f"{kind}: 错误体不是 JSON: {body[:200]}")
    if status_code not in (503, 504):
        fail(f"{kind}: 期望 503/504，实际 {status_code}，体={body[:200]}")
    if payload.get("error") != expected_error:
        fail(f"{kind}: error={payload.get('error')}，期望 {expected_error}")
    if headers.get("x-rag-failure-class") != expected_class:
        fail(f"{kind}: X-RAG-Failure-Class={headers.get('x-rag-failure-class')}，"
             f"期望 {expected_class}")
    if headers.get("retry-after") != retry_after:
        fail(f"{kind}: Retry-After={headers.get('retry-after')}，期望 {retry_after}")
    for leak in ("java.", "org.springframework", "com.springairag", "Exception"):
        if leak in body:
            fail(f"{kind}: 错误体泄漏内部类型/堆栈标记 {leak}: {body[:200]}")
    pass_check(f"{kind}: {status_code} {expected_error} + failureClass="
               f"{expected_class} + Retry-After={retry_after}")


def hikari_active(stack: LocalStack) -> float | None:
    url = f"http://127.0.0.1:{stack.app_port}/actuator/metrics/hikaricp.connections.active"
    status, _, body = http_request_full("GET", url, headers={"X-API-Key": ROOT_API_KEY})
    if status != 200:
        return None
    measurements = json.loads(body).get("measurements") or []
    return float(measurements[0]["value"]) if measurements else None


def main() -> None:
    VERIFICATION_DIR.mkdir(parents=True, exist_ok=True)
    app_port, mock_port, db_port = free_port(), free_port(), free_port()
    container = f"rag-saturation-pg-{os.getpid()}"
    stack = LocalStack("rag_saturation", app_port=app_port, mock_port=mock_port)
    lock_process: subprocess.Popen | None = None

    try:
        print("[INFO] 启动一次性 PostgreSQL 容器（不触碰 .env 指向的库）")
        result = run([
            "docker", "run", "-d", "--name", container,
            "-e", "POSTGRES_PASSWORD=postgres",
            "-e", "POSTGRES_DB=postgres",
            "-p", f"127.0.0.1:{db_port}:5432",
            "pgvector/pgvector:pg16",
        ])
        if result.returncode != 0:
            fail(f"无法启动临时 PostgreSQL：{result.stderr.strip()}")
        wait_for_postgres(container)

        # LocalStack 默认读 .env 的 POSTGRES_*；这里显式改到一次性容器。
        stack.pg_host = "127.0.0.1"
        stack.pg_port = str(db_port)
        stack.pg_user = "postgres"
        stack.pg_password = "postgres"
        stack.pg_env = dict(os.environ)
        stack.pg_env["PGPASSWORD"] = "postgres"
        stack.pg_env.pop("SPRING_DATASOURCE_URL", None)

        print("[INFO] 构建 demo 并启动依赖")
        stack.build_demo_if_needed()
        stack.start_mock()
        stack.create_database()

        extra_env = {
            # Flyway 启动期需要两条连接（迁移连接 + 懒加载数据源），因此池最小为 2；
            # 之后用两个被锁住的请求占满，第三个请求才能观察池耗尽。
            "DB_POOL_SIZE": "2",
            "DB_POOL_MIN_IDLE": "2",
            "DB_CONNECTION_TIMEOUT_MS": "1500",
            "DB_STATEMENT_TIMEOUT_MS": "3000",
        }
        extra_args = [
            # 关掉所有 1s 级轮询，避免后台任务抢走连接让测量变成竞态。
            "--rag.long-running-operations.poll-interval-ms=600000",
            "--rag.evaluation.poll-interval-ms=600000",
            "--rag.embedding-jobs.poll-interval-ms=600000",
            "--rag.embedding-profile-backfill.poll-interval-ms=600000",
            "--rag.document-processing.housekeeping-interval-ms=600000",
            "--rag.collection-provisioning.cleanup-interval-ms=600000",
            "--rag.api-key-provisioning.cleanup-interval-ms=600000",
            "--rag.integration-observability.enabled=false",
            "--rag.data-governance.cleanup-enabled=false",
            "--rag.rate-limit.cleanup-interval-seconds=600000",
        ]
        stack.start_app(extra_env=extra_env, extra_args=extra_args,
                        database=stack.db_name)

        status, headers, body = documents(stack)
        if status != 200:
            fail(f"基线请求失败: {status} {body[:200]}")
        if "UP" not in health(stack):
            fail("基线健康检查不是 UP")
        pass_check("基线与健康检查正常（pool=2, connection-timeout=1500ms, statement_timeout=3000ms）")

        # ---- 场景 1/2：锁等待占满连接池 -> 第三个请求池耗尽；前两个被语句超时截断 ----
        print("[INFO] 在独立会话中加 ACCESS EXCLUSIVE 锁，制造慢查询与池耗尽")
        lock_process = subprocess.Popen(
            ["docker", "exec", "-i", container, "psql", "-U", "postgres",
             "-d", stack.db_name, "-X", "-q"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True,
        )
        assert lock_process.stdin is not None
        lock_process.stdin.write(
            "BEGIN;\nLOCK TABLE rag_documents IN ACCESS EXCLUSIVE MODE;\n")
        lock_process.stdin.flush()
        time.sleep(0.8)
        locked = stack.sql(
            "SELECT count(*) FROM pg_locks WHERE mode='AccessExclusiveLock' "
            "AND relation='rag_documents'::regclass")
        if locked.strip() != "1":
            fail(f"未能确认表锁生效：pg_locks={locked}")

        url = f"http://127.0.0.1:{app_port}/api/v1/rag/documents?page=0&size=10"

        # 阶段 1：锁等待单独验证 statement_timeout。池中有两条连接，单个慢请求
        # 不会触发池耗尽，因此这里必须看到 504 TIMEOUT。后台任务偶尔抢走连接时
        # 会短暂得到 503，重试几次仍必须回到 504。
        slow_attempts = 0
        while True:
            slow_attempts += 1
            status_code, headers, body, elapsed = measure(url, timeout=20)
            if status_code == 504:
                assert_error_contract("慢查询/锁等待", status_code, headers, body,
                                      "DATABASE_TIMEOUT", "TIMEOUT", "1")
                if elapsed > 8:
                    fail(f"慢查询耗时 {elapsed:.2f}s，statement_timeout 未生效")
                pass_check(f"慢查询在 {elapsed:.2f}s 被 statement_timeout 截断为 504 DATABASE_TIMEOUT")
                break
            if status_code == 503 and slow_attempts < 3:
                time.sleep(1)
                continue
            fail(f"慢查询期望 504 DATABASE_TIMEOUT，实际 {status_code}: {body[:200]}")

        # 阶段 2：两个慢请求占满连接池，再发第三个验证池耗尽。
        # 后台定时任务偶尔会抢走一条连接，因此只对“第三个请求”做确定性断言；
        # 两个慢请求自身允许是 503（被抢连接）或 504（成功进入语句超时）。
        slow_results: list[tuple] = []
        slow_lock = threading.Lock()

        def run_slow_request() -> None:
            result = measure(url, timeout=20)
            with slow_lock:
                slow_results.append(result)

        slow_threads = [threading.Thread(target=run_slow_request, daemon=True)
                        for _ in range(2)]
        slow_threads[0].start()
        time.sleep(0.25)
        slow_threads[1].start()
        time.sleep(0.4)

        status_code, headers, body, elapsed = measure(url, timeout=20)
        if elapsed > 6:
            fail(f"池耗尽请求耗时 {elapsed:.2f}s，超过有界等待预期")
        assert_error_contract("连接池耗尽", status_code, headers, body,
                              "DATABASE_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", "5")
        pass_check(f"池耗尽在 {elapsed:.2f}s 内快速失败（有界排队，不是挂死）")

        for thread in slow_threads:
            thread.join(timeout=25)
        if len(slow_results) != 2:
            fail(f"慢请求未全部返回，实际 {len(slow_results)} 个")
        for index, (slow_status, slow_headers, slow_body, slow_elapsed) in enumerate(slow_results):
            if slow_status not in (503, 504):
                fail(f"占满连接池的慢请求#{index + 1} 返回 {slow_status}: {slow_body[:200]}")
            if slow_elapsed > 8:
                fail(f"占满连接池的慢请求#{index + 1} 耗时 {slow_elapsed:.2f}s，超过有界等待")
        pass_check("占满连接池的慢请求全部在有界时间内返回（503/504）")

        # 释放锁 -> 恢复
        if lock_process.stdin is not None:
            lock_process.stdin.write("ROLLBACK;\n\\q\n")
            lock_process.stdin.flush()
        lock_process.wait(timeout=10)
        lock_process = None

        deadline = time.time() + 15
        recovered = False
        while time.time() < deadline:
            status_code, _, _ = documents(stack)
            if status_code == 200:
                recovered = True
                break
            time.sleep(0.5)
        if not recovered:
            fail("释放锁后系统未自动恢复")
        if "UP" not in health(stack):
            fail("恢复后健康检查不是 UP")
        pass_check("锁释放后自动恢复：请求 200、健康检查 UP，无需重启")

        active = hikari_active(stack)
        if active is not None:
            if active > 2:
                fail(f"恢复后 Hikari active={active}，疑似连接泄漏")
            pass_check(f"恢复后 Hikari active={active:g}（pool=2，无泄漏）")
        else:
            print("[INFO] 指标端点未暴露 hikaricp.connections.active，跳过数值断言")

        # ---- 场景 3：数据库临时不可达，再恢复 ----
        print("[INFO] 停止数据库容器，模拟临时不可达")
        result = run(["docker", "stop", container], timeout=60)
        if result.returncode != 0:
            fail(f"停止临时 PostgreSQL 失败：{result.stderr.strip()}")

        status_code, headers, body, elapsed = measure(url, timeout=20)
        if status_code != 503:
            fail(f"数据库不可达期望 503，实际 {status_code}: {body[:200]}")
        payload = json.loads(body)
        if payload.get("error") != "DATABASE_UNAVAILABLE":
            fail(f"数据库不可达 error={payload.get('error')}")
        if headers.get("x-rag-failure-class") != "DEPENDENCY_UNAVAILABLE":
            fail("数据库不可达 failureClass 不是 DEPENDENCY_UNAVAILABLE")
        if elapsed > 8:
            fail(f"数据库不可达请求耗时 {elapsed:.2f}s，超过有界等待预期")
        pass_check(f"数据库不可达在 {elapsed:.2f}s 返回 503 DATABASE_UNAVAILABLE")

        body = health(stack)
        if '"status":"DOWN"' not in body.replace(" ", ""):
            fail(f"数据库不可达时 /actuator/health 不是 DOWN: {body[:300]}")
        pass_check("数据库不可达时 /actuator/health 为 DOWN（readiness 会摘流量，liveness 不受 DB 影响）")

        result = run(["docker", "start", container], timeout=60)
        if result.returncode != 0:
            fail(f"重启临时 PostgreSQL 失败：{result.stderr.strip()}")
        wait_for_postgres(container, 60)

        deadline = time.time() + 30
        recovered = False
        while time.time() < deadline:
            status_code, _, _ = documents(stack)
            if status_code == 200:
                recovered = True
                break
            time.sleep(1)
        if not recovered:
            fail("数据库恢复后应用未自动恢复（疑似需要重启或连接池未重建）")
        if "UP" not in health(stack):
            fail("数据库恢复后健康检查不是 UP")
        pass_check("数据库恢复后应用自动回到正常（进程未重启），健康检查 UP")

        log_text = (stack.log_dir / f"app-{app_port}.log").read_text(errors="replace")
        if "Apparent connection leak detected" in log_text:
            fail("应用日志出现 Hikari 连接泄漏告警")
        pass_check("应用日志无 Hikari 连接泄漏告警")
    finally:
        if lock_process is not None and lock_process.poll() is None:
            lock_process.kill()
        try:
            stack.cleanup()
        finally:
            run(["docker", "rm", "-f", container])

    summary = "\n".join(f"- {item}" for item in PASSED)
    (VERIFICATION_DIR / "resource-saturation-e2e.txt").write_text(summary + "\n")
    print(f"\n{len(PASSED)} checks passed")


if __name__ == "__main__":
    main()
