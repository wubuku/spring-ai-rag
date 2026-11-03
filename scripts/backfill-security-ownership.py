#!/usr/bin/env python3
"""V71 安全归属受控回填（默认 dry-run，不自动执行）。

背景：V71 把文件、反馈与检索评估的可见范围收敛到 owner_principal_id。
历史行没有 owner，因此受限 API key 看不到它们（fail-closed）；root 与
未启用安全认证的部署行为不变。是否把历史数据归属给某个 principal 是
运维决策，本脚本不会在启动或迁移时自动执行。

用法：

    # 只统计，不写库（默认）
    ./scripts/backfill-security-ownership.py --owner-principal-id rag_k_xxx

    # 明确执行
    ./scripts/backfill-security-ownership.py --owner-principal-id rag_k_xxx --apply

    # 只回填文件，并同时把无 Collection 的历史文件归到指定 Collection
    ./scripts/backfill-security-ownership.py \
      --owner-principal-id rag_k_xxx --only files --files-collection-id 12 --apply

连接参数读取 POSTGRES_HOST/PORT/USER/PASSWORD，也可用 --database 指定库名
（默认 POSTGRES_DATABASE，再退回 spring_ai_rag）。
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[1]

TABLES = {
    "files": "fs_files",
    "feedback": "rag_user_feedback",
    "evaluations": "rag_retrieval_evaluations",
}


def read_env(key: str, default: str = "") -> str:
    """只读取键值，避免 source 整个工作区 .env 覆盖连接参数。"""
    env_file = PROJECT_DIR / ".env"
    if not env_file.exists():
        return os.environ.get(key, default)
    value = None
    for raw in env_file.read_text().splitlines():
        line = raw.strip()
        if line.startswith("export "):
            line = line[len("export "):]
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, candidate = line.split("=", 1)
        if name.strip() != key:
            continue
        candidate = candidate.strip()
        if len(candidate) >= 2 and candidate[0] == candidate[-1] and candidate[0] in "\"'":
            candidate = candidate[1:-1]
        value = candidate
    return os.environ.get(key) or (default if value is None else value)


def build_psql(database: str) -> tuple[list[str], dict[str, str]]:
    host = read_env("POSTGRES_HOST", "localhost")
    port = read_env("POSTGRES_PORT", "5432")
    user = read_env("POSTGRES_USER", "postgres")
    password = read_env("POSTGRES_PASSWORD", "")
    args = [
        "psql", "-h", host, "-p", port, "-U", user, "-d", database,
        "-X", "-q", "-v", "ON_ERROR_STOP=1",
    ]
    env = dict(os.environ)
    env.pop("SPRING_DATASOURCE_URL", None)
    if password:
        env["PGPASSWORD"] = password
    return args, env


def run(args: list[str], env: dict[str, str], statement: str, *, scalar: bool = True,
        single_transaction: bool = False) -> str:
    full = args + (["--single-transaction"] if single_transaction else [])
    full += ["-tAc", statement] if scalar else ["-c", statement]
    result = subprocess.run(full, env=env, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "psql failed")
    return result.stdout.strip()


def ownerless_count(args: list[str], env: dict[str, str], table: str) -> int:
    return int(run(args, env,
                   f"SELECT COUNT(*) FROM {table} "
                   "WHERE owner_principal_id IS NULL") or "0")


def sample(args: list[str], env: dict[str, str], table: str, columns: str) -> str:
    return run(args, env,
               f"SELECT {columns} FROM {table} "
               "WHERE owner_principal_id IS NULL ORDER BY 1 LIMIT 5")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--owner-principal-id", required=True,
                        help="归属目标 principal（例如 rag_k_ 开头的 key id）")
    parser.add_argument("--database", default=None,
                        help="目标库名；默认 POSTGRES_DATABASE 或 spring_ai_rag")
    parser.add_argument("--only", choices=sorted(TABLES), action="append",
                        help="只处理指定表，可重复；默认全部")
    parser.add_argument("--files-collection-id", type=int, default=None,
                        help="同时把无 Collection 的历史文件归到该 Collection id")
    parser.add_argument("--apply", action="store_true",
                        help="真正写库；缺省只统计（dry-run）")
    args_ns = parser.parse_args()

    database = (args_ns.database or read_env("POSTGRES_DATABASE", "")
                or read_env("POSTGRES_DB", "") or "spring_ai_rag")
    psql_args, psql_env = build_psql(database)

    selected = args_ns.only or list(TABLES)
    principal = args_ns.owner_principal_id
    if not all(ch.isalnum() or ch in "._:-" for ch in principal):
        print("[ERROR] owner-principal-id 仅允许字母、数字与 . _ : -", file=sys.stderr)
        return 2
    print(f"目标库：{database}")
    print(f"归属 principal：{principal}")
    print(f"模式：{'apply（写库）' if args_ns.apply else 'dry-run（只统计）'}")

    statements: list[str] = []
    for key in selected:
        table = TABLES[key]
        count = ownerless_count(psql_args, psql_env, table)
        print(f"\n[{key}] {table} 待回填行数：{count}")
        columns = {
            "files": "path",
            "feedback": "session_id, query",
            "evaluations": "query",
        }[key]
        preview = sample(psql_args, psql_env, table, columns)
        if preview:
            print("  示例：")
            for line in preview.splitlines():
                print(f"    {line}")
        if count == 0:
            continue
        if key == "files" and args_ns.files_collection_id is not None:
            statements.append(
                "UPDATE fs_files SET collection_id = "
                f"{args_ns.files_collection_id} "
                "WHERE owner_principal_id IS NULL AND collection_id IS NULL;")
        statements.append(
            f"UPDATE {table} SET owner_principal_id = '{principal}' "
            "WHERE owner_principal_id IS NULL;")

    if not args_ns.apply:
        print("\n未执行任何写入。确认影响后加 --apply 再运行。")
        return 0

    if not statements:
        print("\n没有需要回填的行，未执行写入。")
        return 0

    run(psql_args, psql_env, "\n".join(statements), scalar=False,
        single_transaction=True)
    print("\n回填完成。")
    for key in selected:
        table = TABLES[key]
        remaining = ownerless_count(psql_args, psql_env, table)
        print(f"[{key}] 剩余无归属行数：{remaining}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RuntimeError as error:
        print(f"[ERROR] {error}", file=sys.stderr)
        sys.exit(1)
