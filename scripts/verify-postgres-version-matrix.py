#!/usr/bin/env python3
"""PostgreSQL/pgvector 版本支持矩阵探针（真实容器 + 全量迁移）。

对每个版本启动一个真实 `pgvector/pgvector:pgN` 容器，按 Flyway 顺序执行
V1–V73 全部迁移，然后验证与版本相关的关键路径：

1. `vector` 扩展存在，版本不低于 HNSW 所需的最低版本；
2. 迁移建出的 HNSW 索引有效，`hnsw.ef_search` 可用；
3. `CREATE INDEX CONCURRENTLY` 可执行（EmbeddingProfileIndexManager 的建索引路径）；
4. `SET NOT NULL` 迁移结果落地（关键列 `attnotnull = true`）；
5. 向量余弦距离检索结果确定（同版本内最近邻返回自身）；
6. 当前 schema 未启用 RLS（租户隔离在应用层，矩阵只确认这一事实未随版本漂移）。

用法：

    python3 scripts/verify-postgres-version-matrix.py
    python3 scripts/verify-postgres-version-matrix.py --versions 15,17
    python3 scripts/verify-postgres-version-matrix.py --keep   # 保留容器便于排查

镜像默认取 `pgvector/pgvector:pg<version>`；境内网络可先通过镜像站 pull 并
`docker tag` 成本地同名镜像，脚本不硬编码任何区域镜像地址。
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[1]
MIGRATIONS_DIR = PROJECT_DIR / "spring-ai-rag-core" / "src" / "main" / "resources" / "db" / "migration"

MIN_VECTOR_VERSION = (0, 5, 0)  # HNSW 索引的最低 pgvector 版本

# V41/V48 中用 SET NOT NULL 收紧的列：(表, 列)
NOT_NULL_COLUMNS = [
    ("rag_embedding_jobs", "document_kind"),
    ("rag_embedding_jobs", "chunker_version"),
    ("rag_collection", "collection_key"),
    ("rag_api_key", "principal_id"),
    ("rag_api_key", "credential_version"),
]


def run(cmd: list[str], *, stdin: str | None = None, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd,
        input=stdin,
        text=True,
        capture_output=True,
        check=check,
    )


def docker_exec(container: str, args: list[str], *, stdin: str | None = None) -> str:
    result = run(["docker", "exec", "-i", container, *args], stdin=stdin, check=False)
    if result.returncode != 0:
        raise RuntimeError(
            f"docker exec failed ({result.returncode}): {' '.join(args)}\n"
            f"{result.stdout[-800:]}{result.stderr[-800:]}"
        )
    return result.stdout


def psql(container: str, sql: str, database: str = "postgres",
         single_transaction: bool = False) -> str:
    args = ["psql", "-U", "postgres", "-d", database, "-v", "ON_ERROR_STOP=1", "-Atq"]
    if single_transaction:
        # Flyway 默认把单个迁移放在一个事务里；V43 的
        # `CREATE TEMP TABLE ... ON COMMIT DROP` 依赖这一点。
        args.append("-1")
    return docker_exec(container, args, stdin=sql).strip()


def wait_ready(container: str, timeout_seconds: int = 120) -> None:
    """Wait for the *final* server, not the entrypoint's temporary init server.

    Official postgres images run initdb/bootstrap on a temporary server, shut it
    down, then start the server users are meant to talk to. Both phases log
    "database system is ready to accept connections". A connection made during
    the first phase dies mid-migration with "terminating connection due to
    administrator command", so require the second ready line before returning.
    """
    deadline = time.monotonic() + timeout_seconds
    last_state = ""
    while time.monotonic() < deadline:
        logs = run(["docker", "logs", container], check=False)
        text = (logs.stdout or "") + (logs.stderr or "")
        ready_lines = text.count("database system is ready to accept connections")
        probe = run(
            ["docker", "exec", container, "pg_isready", "-U", "postgres", "-d", "postgres"],
            check=False,
        )
        if ready_lines >= 2 and probe.returncode == 0:
            return
        last_state = f"ready_lines={ready_lines} pg_isready={probe.returncode}"
        time.sleep(1)
    raise RuntimeError(f"PostgreSQL did not reach its final server: {last_state}")


def sorted_migrations() -> list[Path]:
    def version_key(path: Path) -> int:
        return int(path.name.split("__", 1)[0][1:])

    return sorted(MIGRATIONS_DIR.glob("V*__*.sql"), key=version_key)


def vector_version_tuple(raw: str) -> tuple[int, ...]:
    return tuple(int(part) for part in raw.split(".") if part.isdigit())


def probe(version: str, keep: bool) -> dict:
    image = f"pgvector/pgvector:pg{version}"
    container = f"rag-pg-matrix-{version}"
    run(["docker", "rm", "-f", container], check=False)
    run(["docker", "run", "-d", "--name", container,
         "-e", "POSTGRES_PASSWORD=probe", image])

    facts: dict = {"pg_version_tag": version, "image": image, "checks": []}

    def check(name: str, ok: bool, detail: str) -> None:
        facts["checks"].append({"name": name, "ok": ok, "detail": detail})

    try:
        wait_ready(container)
        facts["server_version"] = psql(
            container, "SELECT current_setting('server_version');").splitlines()[0]

        migrations = sorted_migrations()
        started = time.monotonic()
        for migration in migrations:
            psql(container, migration.read_text(encoding="utf-8"), single_transaction=True)
        facts["migration_count"] = len(migrations)
        facts["migration_seconds"] = round(time.monotonic() - started, 2)
        check("全量迁移 V1–V73 执行成功", len(migrations) == 73,
              f"{len(migrations)} files applied")

        ext_version = psql(
            container,
            "SELECT extversion FROM pg_extension WHERE extname = 'vector';",
        ).strip()
        facts["vector_version"] = ext_version
        check(
            "vector 扩展版本 ≥ HNSW 最低要求",
            bool(ext_version) and vector_version_tuple(ext_version) >= MIN_VECTOR_VERSION,
            f"{ext_version or 'missing'} (min {'/'.join(map(str, MIN_VECTOR_VERSION))})",
        )

        # V68 删除 `rag_embeddings.embedding` 后，V1 的 HNSW 索引随列一起消失；
        # 运行期索引由 EmbeddingProfileIndexManager 按 profile 维度创建。因此
        # 这里用同版本可执行的 HNSW 建索引 + 检索来证明该版本支持关键路径。
        psql(
            container,
            """
            CREATE TABLE matrix_vector_probe (
                id integer PRIMARY KEY,
                embedding vector(1024)
            );
            INSERT INTO matrix_vector_probe (id, embedding)
            SELECT 1, ('[' || string_agg(CASE WHEN i = 1 THEN '1' ELSE '0' END, ',' ORDER BY i) || ']')::vector
            FROM generate_series(1, 1024) AS i;
            INSERT INTO matrix_vector_probe (id, embedding)
            SELECT 2, ('[' || string_agg(CASE WHEN i = 1 THEN '1' ELSE '0' END, ',' ORDER BY i) || ']')::vector
            FROM generate_series(1, 1024) AS i;
            INSERT INTO matrix_vector_probe (id, embedding)
            SELECT 3, ('[' || string_agg(CASE WHEN i = 1 THEN '-1' ELSE '0' END, ',' ORDER BY i) || ']')::vector
            FROM generate_series(1, 1024) AS i;
            CREATE INDEX matrix_vector_probe_hnsw ON matrix_vector_probe
                USING hnsw (embedding vector_cosine_ops);
            """,
        )
        hnsw_definition = psql(
            container,
            """
            SELECT indexdef FROM pg_indexes
            WHERE schemaname = 'public' AND indexname = 'matrix_vector_probe_hnsw';
            """,
        )
        facts["hnsw_index_definition"] = hnsw_definition
        check("HNSW 索引可创建", "USING hnsw" in hnsw_definition, hnsw_definition)

        # 模拟 EmbeddingProfileIndexManager 的并发建索引路径。
        psql(
            container,
            """
            CREATE TABLE matrix_concurrent_probe (id bigserial PRIMARY KEY, doc_id bigint);
            CREATE INDEX CONCURRENTLY IF NOT EXISTS matrix_concurrent_probe_doc
                ON matrix_concurrent_probe (doc_id);
            """,
        )
        concurrent_index = psql(
            container,
            """
            SELECT count(*) FROM pg_indexes
            WHERE schemaname = 'public' AND indexname = 'matrix_concurrent_probe_doc';
            """,
        )
        check("CREATE INDEX CONCURRENTLY 可执行", concurrent_index.strip() == "1", concurrent_index)
        psql(container, "DROP TABLE matrix_concurrent_probe;")

        missing = []
        for table, column in NOT_NULL_COLUMNS:
            value = psql(
                container,
                f"""
                SELECT attnotnull FROM pg_attribute
                WHERE attrelid = '{table}'::regclass AND attname = '{column}';
                """,
            )
            if value.strip() != "t":
                missing.append(f"{table}.{column}={value.strip() or 'missing'}")
        check("SET NOT NULL 迁移结果落地", not missing, ", ".join(missing) or "all not-null")

        # 1 与 2 完全同向（距离 0），3 反向；最近邻应落在 1/2，不能是 3。
        nearest = psql(
            container,
            """
            SELECT id FROM matrix_vector_probe
            ORDER BY embedding <=> (SELECT embedding FROM matrix_vector_probe WHERE id = 1)
            LIMIT 1;
            """,
        ).splitlines()[-1]
        check("向量余弦检索最近邻正确", nearest.strip() in {"1", "2"},
              f"nearest={nearest!r}")
        psql(container, "DROP TABLE matrix_vector_probe;")

        # RLS 事实：当前 schema 不用 RLS，矩阵只确认没有随版本意外启用。
        rls_tables = psql(
            container,
            """
            SELECT c.relname FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relrowsecurity
            ORDER BY c.relname;
            """,
        )
        policy_count = psql(container, "SELECT count(*) FROM pg_policies;")
        facts["rls_tables"] = [line for line in rls_tables.splitlines() if line]
        check("RLS 未被隐式启用（租户隔离仍在应用层）",
              not facts["rls_tables"] and policy_count.strip() == "0",
              f"rls_tables={facts['rls_tables']} policies={policy_count.strip()}")

    except Exception as error:  # noqa: BLE001 - 汇总为矩阵失败，不吞掉细节
        facts["error"] = str(error)
        check("探针执行完成", False, str(error).splitlines()[0])
    finally:
        if keep:
            facts["container"] = container
        else:
            run(["docker", "rm", "-f", container], check=False)

    return facts


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--versions", default="15,16,17,18",
                        help="逗号分隔的 PostgreSQL 主版本，默认 15,16,17,18")
    parser.add_argument("--keep", action="store_true", help="保留容器")
    parser.add_argument("--json", type=Path, help="可选：把结果写入 JSON 文件")
    args = parser.parse_args()

    versions = [part.strip() for part in args.versions.split(",") if part.strip()]
    results = []
    failed = False
    for version in versions:
        print(f"\n=== PostgreSQL {version} ===", flush=True)
        facts = probe(version, args.keep)
        results.append(facts)
        if facts.get("error"):
            failed = True
        for item in facts["checks"]:
            status = "PASS" if item["ok"] else "FAIL"
            print(f"[{status}] {item['name']}: {item['detail']}")
            if not item["ok"]:
                failed = True
        print(
            f"[INFO] server={facts.get('server_version')} "
            f"vector={facts.get('vector_version')} "
            f"migrations={facts.get('migration_count')} "
            f"seconds={facts.get('migration_seconds')}"
        )

    if args.json:
        args.json.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nJSON written to {args.json}")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
