#!/usr/bin/env python3
"""部署态安全姿态验收：prod 拒绝启动 + 非 prod 仅告警。

验收点：

1. `postgresql,prod` + `RAG_SECURITY_ENABLED=false` → 进程非 0 退出；
2. `postgresql,prod` + 认证开启 → 正常启动，未认证请求 401，root key 可读；
3. `postgresql`（非 prod） + 认证关闭 → 仍可启动，健康检查标记 risk=HIGH。

运行（需要本机 PostgreSQL 与 demo jar）：

    ./scripts/verify-security-deployment-posture.py
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_DIR / "scripts" / "lib"))

from rag_local_stack import (  # noqa: E402
    LocalStack,
    ROOT_API_KEY,
    http_request,
)

APP_PORT = 18099
MOCK_PORT = 18100

failures: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"[PASS] {name}")
        return
    failures.append(f"{name}: {detail}")
    print(f"[FAIL] {name} :: {detail}")


def start_expecting_refusal(stack: LocalStack, extra_env: dict[str, str]) -> tuple[int, str]:
    env = stack._base_env()  # noqa: SLF001 - 复用临时库与 mock 配置
    env.update(extra_env)
    log_path = stack.log_dir / "prod-refusal.log"
    with log_path.open("w") as log:
        process = subprocess.Popen(
            ["java", "-jar", str(stack.demo_jar()), f"--server.port={APP_PORT}",
             f"--rag.security.root-api-key={ROOT_API_KEY}"],
            cwd=str(PROJECT_DIR), stdout=log, stderr=subprocess.STDOUT, env=env,
        )
        try:
            code = process.wait(timeout=180)
        except subprocess.TimeoutExpired:
            process.kill()
            code = -1
    return code, log_path.read_text(errors="replace")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true", help="保留临时目录与日志")
    args = parser.parse_args()

    stack = LocalStack("security-posture", APP_PORT, MOCK_PORT, keep=args.keep)
    started = time.time()
    try:
        stack.create_database()
        stack.start_mock()
        stack.build_demo_if_needed()

        # ---------- prod + 认证关闭：拒绝启动 ----------
        code, log_text = start_expecting_refusal(stack, {
            "SPRING_PROFILES_ACTIVE": "postgresql,prod",
            "RAG_SECURITY_ENABLED": "false",
        })
        check("prod + 认证关闭拒绝启动", code != 0, f"exit={code}")
        check("拒绝启动日志包含明确原因",
              "Refusing to start" in log_text
              and "rag.security.enabled=false" in log_text,
              log_text[-400:])

        # ---------- prod + 认证开启：正常启动 ----------
        base = stack.start_app(extra_env={
            "SPRING_PROFILES_ACTIVE": "postgresql,prod",
            "RAG_SECURITY_ENABLED": "true",
        })
        status, health = http_request("GET", f"{base}/actuator/health")
        posture = json.loads(health or "{}").get("components", {}).get(
            "ragSecurityPosture", {})
        check("prod 认证开启正常启动", status == 200 and posture.get("status") == "UP",
              f"status={status} posture={posture}")
        check("prod 健康检查标明认证已启用",
              str(posture.get("details", {}).get("securityEnabled")).lower() == "true",
              f"posture={posture}")

        status, body = http_request("GET", f"{base}/api/v1/rag/collections")
        check("prod 未认证数据面请求被拒绝", status == 401,
              f"status={status} body={body[:200]}")
        status, _ = http_request(
            "GET", f"{base}/api/v1/rag/collections",
            headers={"X-API-Key": ROOT_API_KEY})
        check("prod root key 可访问数据面", status == 200, f"status={status}")

        # ---------- 非 prod + 认证关闭：启动但告警 ----------
        base = stack.start_app(extra_env={
            "SPRING_PROFILES_ACTIVE": "postgresql",
            "RAG_SECURITY_ENABLED": "false",
        })
        status, health = http_request("GET", f"{base}/actuator/health")
        posture = json.loads(health or "{}").get("components", {}).get(
            "ragSecurityPosture", {})
        check("非 prod 认证关闭仍可启动", status == 200 and posture.get("status") == "UP",
              f"status={status} posture={posture}")
        check("非 prod 健康检查标记 HIGH 风险",
              posture.get("details", {}).get("risk") == "HIGH",
              f"posture={posture}")

        print(f"\n完成，用时 {time.time() - started:.1f}s")
        if failures:
            print(f"\n{len(failures)} 项失败：")
            for failure in failures:
                print(f"  - {failure}")
            return 1
        print("全部部署态安全姿态断言通过")
        return 0
    finally:
        stack.cleanup()


if __name__ == "__main__":
    sys.exit(main())
