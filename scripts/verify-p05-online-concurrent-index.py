#!/usr/bin/env python3
"""P0-5 大表专项：百万行 rag_embeddings 上的在线 CREATE INDEX CONCURRENTLY 演练。

只操作脚本自建的一次性 Docker 容器（默认随机宿主端口），绝不连接共享 dev 库。

演练内容：
  1. 建 ~1,000,000 行 rag_embeddings（10 个 tenant，每租户 100k 行；不建目标索引）。
  2. 后台连接持续采样 pg_stat_activity / pg_locks / 索引状态（indisvalid、indisready）。
  3. 在独立运维连接上执行 CREATE INDEX CONCURRENTLY（带 lock_timeout / statement_timeout）。
  4. 记录锁等待事件、耗时、最终索引有效性与数据可读写性；失败时清理 INVALID 索引并写明原因。

用法示例：
  python3 scripts/verify-p05-online-concurrent-index.py \\
      --rows 1000000 --tenants 10 --out /tmp/p05-concurrent.json

退出码：演练完成（含预期失败+清理）返回 0；基础设施错误返回 1。
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

try:
    import psycopg2
except ImportError:
    sys.exit("缺少 psycopg2：请 pip install psycopg2-binary")
from pgvector.psycopg2 import register_vector
INDEX_NAME = "idx_rag_embeddings_embedding_1024_hnsw_p05"


def log(msg: str) -> None:
    print(f"[p05-concurrent] {datetime.now(timezone.utc).isoformat()} {msg}", flush=True)


def run(cmd: list[str], check: bool = True, capture: bool = False) -> subprocess.CompletedProcess:
    result = subprocess.run(cmd, text=True, capture_output=capture)
    if check and result.returncode != 0:
        raise RuntimeError(f"命令失败: {' '.join(cmd)}\n{result.stdout}\n{result.stderr}")
    return result


@dataclass
class Stack:
    container: str
    host: str = "127.0.0.1"

    @property
    def dsn(self) -> dict[str, Any]:
        return {"host": self.host, "port": 5432, "dbname": "p05", "user": "p05", "password": "p05"}

    def start(self, image: str) -> None:
        log(f"启动一次性容器 image={image}")
        run(["docker", "run", "-d", "--name", self.container,
             "-e", "POSTGRES_DB=p05", "-e", "POSTGRES_USER=p05", "-e", "POSTGRES_PASSWORD=p05",
             "--health-cmd", "pg_isready -U p05 -d p05", "--health-interval=2s",
             "--health-timeout=2s", "--health-retries=60", image])
        for _ in range(60):
            status = run(["docker", "inspect", "--format", "{{.State.Health.Status}}", self.container],
                         capture=True).stdout.strip()
            if status == "healthy":
                # 直接走 Docker bridge IP，不依赖 host port mapping。
                self.host = run([
                    "docker", "inspect", "-f",
                    "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", self.container
                ], capture=True).stdout.strip()
                return
            time.sleep(1)
        raise RuntimeError("容器未健康")

    def stop(self, keep: bool) -> None:
        if keep:
            log(f"保留容器 {self.container}")
            return
        run(["docker", "rm", "-f", self.container], check=False)


def connect(stack: Stack) -> psycopg2.extensions.connection:
    last: Exception | None = None
    for _ in range(30):
        try:
            conn = psycopg2.connect(**stack.dsn, connect_timeout=5)
            break
        except Exception as exc:
            last = exc
            time.sleep(1)
    else:
        raise RuntimeError(f"30s 内无法连接容器 PostgreSQL：{last}")
    conn.autocommit = True
    with conn.cursor() as cur:
        cur.execute("CREATE EXTENSION IF NOT EXISTS vector")
        cur.execute("SELECT extversion FROM pg_extension WHERE extname='vector'")
        log(f"pgvector {cur.fetchone()[0]}")
    register_vector(conn)
    return conn


def create_schema(cur) -> None:
    cur.execute("""CREATE TABLE rag_embeddings (
        id BIGSERIAL PRIMARY KEY,
        tenant_id VARCHAR(64) NOT NULL,
        document_id BIGINT NOT NULL,
        chunk_index INTEGER NOT NULL DEFAULT 0,
        embedding_1024 VECTOR(1024) NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    )""")
    cur.execute("CREATE INDEX idx_rag_embeddings_tenant_leading ON rag_embeddings (tenant_id)")


def load_rows(cur, rows: int, tenants: int, seed: int, batch: int) -> float:
    """服务端 SQL 生成 1024 维向量，无需 Python 序列化百万行。"""
    cur.execute(f"SELECT setseed({seed / 2**31:.12f})")
    t0 = time.perf_counter()
    loaded = 0
    while loaded < rows:
        size = min(batch, rows - loaded)
        cur.execute("""
            INSERT INTO rag_embeddings (id, tenant_id, document_id, embedding_1024)
            SELECT g, 't_' || ((g - 1) %% %s)::text, g,
                   (ARRAY(SELECT random() * 2 - 1 FROM generate_series(1, 1024)))::vector(1024)
            FROM generate_series(%s, %s) g
        """, (tenants, loaded + 1, loaded + size))
        loaded += size
        log(f"已写入 {loaded}/{rows} 行")
    cur.execute("SELECT setval('rag_embeddings_id_seq', (SELECT max(id) FROM rag_embeddings))")
    cur.execute("ANALYZE rag_embeddings")
    elapsed = time.perf_counter() - t0
    log(f"语料写入完成 {rows} 行 / {elapsed:.1f}s")
    return round(elapsed, 2)


@dataclass
class Monitor:
    dsn: dict[str, Any]
    interval: float
    samples: list[dict[str, Any]] = field(default_factory=list)
    stop_flag: threading.Event = field(default_factory=threading.Event)
    thread: threading.Thread | None = None

    def start(self) -> None:
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def _loop(self) -> None:
        conn = None
        try:
            while not self.stop_flag.is_set():
                try:
                    if conn is None or conn.closed:
                        conn = psycopg2.connect(**self.dsn, connect_timeout=5)
                        conn.autocommit = True
                    with conn.cursor() as cur:
                        cur.execute("""
                            SELECT pid, state, wait_event_type, wait_event,
                                   left(query, 160) AS query
                            FROM pg_stat_activity
                            WHERE query LIKE '%CREATE INDEX%'
                               OR wait_event_type = 'Lock'
                               OR application_name = 'p05-writer'
                            ORDER BY pid
                        """)
                        activity = [{"pid": r[0], "state": r[1], "wait_event_type": r[2],
                                     "wait_event": r[3], "query": r[4]} for r in cur.fetchall()]
                        cur.execute("""
                            SELECT count(*) FILTER (WHERE wait_event_type = 'Lock'),
                                   count(*) FILTER (WHERE state = 'active'),
                                   count(*)
                            FROM pg_stat_activity WHERE backend_type = 'client backend'
                        """)
                        lock_waiters, active, total = cur.fetchone()
                        cur.execute("""
                            SELECT c.relname, i.indisvalid, i.indisready, i.indislive
                            FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                            WHERE c.relname = %s
                        """, (INDEX_NAME,))
                        idx = cur.fetchone()
                    self.samples.append({
                        "ts": datetime.now(timezone.utc).isoformat(),
                        "activity": activity,
                        "lock_waiters": lock_waiters,
                        "active_backends": active,
                        "total_backends": total,
                        "index": {"name": idx[0], "indisvalid": idx[1], "indisready": idx[2],
                                  "indislive": idx[3]} if idx else None,
                    })
                except Exception as exc:
                    log(f"监控采样失败（将重连）：{exc}")
                    if conn is not None:
                        try:
                            conn.close()
                        except Exception:
                            pass
                        conn = None
                self.stop_flag.wait(self.interval)
        finally:
            if conn is not None:
                try:
                    conn.close()
                except Exception:
                    pass

    def stop(self) -> list[dict[str, Any]]:
        self.stop_flag.set()
        if self.thread:
            self.thread.join(timeout=10)
            if self.thread.is_alive():
                log("监控线程未在 10s 内退出，放弃等待（采样数据可能不完整）")
        return self.samples


def index_state(cur) -> dict[str, Any] | None:
    with cur.connection.cursor() as c:
        c.execute("""
            SELECT c.relname, am.amname, i.indisvalid, i.indisready, i.indislive,
                   pg_size_pretty(pg_relation_size(c.oid))
            FROM pg_index i
            JOIN pg_class c ON c.oid = i.indexrelid
            JOIN pg_am am ON am.oid = c.relam
            WHERE c.relname = %s
        """, (INDEX_NAME,))
        row = c.fetchone()
        if not row:
            return None
        return {"name": row[0], "method": row[1], "indisvalid": row[2], "indisready": row[3],
                "indislive": row[4], "size": row[5]}


def run_drill(args: argparse.Namespace) -> dict[str, Any]:
    stack = Stack(container=f"p05-concurrent-{os.getpid()}")
    result: dict[str, Any] = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "parameters": vars(args),
        "status": "RUNNING",
    }
    try:
        stack.start(args.image)
        conn = connect(stack)
        create_schema(conn.cursor())
        result["load_seconds"] = load_rows(conn.cursor(), args.rows, args.tenants, args.seed, args.batch)
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM rag_embeddings")
            result["rows"] = cur.fetchone()[0]

        # 模拟在线写入：后台连接每 0.1s 更新一行（不同租户），验证 CONCURRENTLY 不阻塞业务写入。
        write_stop = threading.Event()
        write_errors: list[str] = []

        def writer() -> None:
            wconn = psycopg2.connect(**stack.dsn)
            wconn.autocommit = True
            n = 0
            try:
                while not write_stop.is_set():
                    with wconn.cursor() as wc:
                        wc.execute("UPDATE rag_embeddings SET chunk_index = chunk_index + 1 WHERE id = %s", (n + 1,))
                    n += 1
                    write_stop.wait(0.1)
            except Exception as exc:
                write_errors.append(str(exc))
            finally:
                wconn.close()

        wt = threading.Thread(target=writer, daemon=True)
        wt.start()
        monitor = Monitor(dsn=stack.dsn, interval=args.sample_interval)
        monitor.start()

        # 独立运维连接，设置 GUC 后执行 CONCURRENTLY；不进 Flyway 版本链。
        idx_conn = psycopg2.connect(**stack.dsn)
        idx_conn.autocommit = True
        ddl = (
            f"CREATE INDEX CONCURRENTLY {INDEX_NAME} ON rag_embeddings "
            "USING hnsw (embedding_1024 vector_cosine_ops) WITH (m=16, ef_construction=64)"
        )
        result["ddl"] = ddl
        log(f"开始执行 {ddl}")
        t0 = time.perf_counter()
        error: str | None = None
        try:
            with idx_conn.cursor() as cur:
                cur.execute(f"SET lock_timeout = '{args.lock_timeout}s'")
                cur.execute(f"SET statement_timeout = '{args.statement_timeout}s'")
                cur.execute(ddl)
        except Exception as exc:
            error = f"{type(exc).__name__}: {exc}"
            log(f"CONCURRENTLY 失败：{error}")
        result["elapsed_seconds"] = round(time.perf_counter() - t0, 2)
        idx_conn.close()

        write_stop.set()
        wt.join(timeout=5)
        samples = monitor.stop()
        result["monitor_samples"] = samples
        result["writer_updates"] = "持续更新直到 CONCURRENTLY 结束；错误=" + repr(write_errors)
        with conn.cursor() as cur:
            state = index_state(cur)
        result["index_state"] = state
        result["concurrent_success"] = error is None and bool(state and state["indisvalid"])
        if not result["concurrent_success"]:
            result["failure_reason"] = error or "索引存在但 indisvalid=false（CONCURRENTLY 未完成）"
            log("清理 INVALID 索引")
            with conn.cursor() as cur:
                cur.execute(f"DROP INDEX CONCURRENTLY IF EXISTS {INDEX_NAME}")
            with conn.cursor() as cur:
                result["index_after_cleanup"] = index_state(cur)
        # 数据可读写性复核
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM rag_embeddings")
            result["rows_after"] = cur.fetchone()[0]
            cur.execute("SELECT count(*) FROM rag_embeddings WHERE tenant_id='t_0'")
            result["tenant_t0_rows"] = cur.fetchone()[0]
        result["status"] = "PASS"
    except Exception as exc:
        result["status"] = "FAILED"
        result["error"] = f"{type(exc).__name__}: {exc}"
        log(f"演练基础设施失败：{result['error']}")
        return 1
    finally:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        log(f"结果写入 {args.out}（status={result['status']}）")
        stack.stop(args.keep_container)
    return 0


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--image", default="pgvector/pgvector:pg16")
    p.add_argument("--rows", type=int, default=1_000_000)
    p.add_argument("--tenants", type=int, default=10)
    p.add_argument("--seed", type=int, default=20261128)
    p.add_argument("--batch", type=int, default=5000)
    p.add_argument("--lock-timeout", type=int, default=30)
    p.add_argument("--statement-timeout", type=int, default=1800)
    p.add_argument("--sample-interval", type=float, default=0.5)
    p.add_argument("--out", required=True)
    p.add_argument("--keep-container", action="store_true")
    return p.parse_args()


if __name__ == "__main__":
    sys.exit(run_drill(parse_args()))
