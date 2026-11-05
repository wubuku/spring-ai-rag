#!/usr/bin/env python3
"""真实 PostgreSQL 验收栈：临时数据库 + mock provider + demo-basic-rag。

用于并发/幂等与备份恢复演练脚本共享启动逻辑。所有资源都在显式
`cleanup()` 中释放；临时数据库永远不指向开发者日常库。
"""

from __future__ import annotations

import glob
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[2]
MOCK_SCRIPT = PROJECT_DIR / "scripts" / "run-mock-llm.sh"
DEMO_DIR = PROJECT_DIR / "demos" / "demo-basic-rag"

ROOT_API_KEY = "verify-root-key-0123456789abcdefghij"


def read_env(key: str, default: str = "") -> str:
    """先读进程环境变量，再回退到工作区 .env，最后才用默认值。

    工作区 .env 可能导出 SPRING_DATASOURCE_URL，它会覆盖 demo 的 POSTGRES_*
    配置，让“临时库验收”悄悄连到日常开发库；因此这里只读取需要的键。

    进程环境必须排在最前：CI 是干净检出、没有 .env，密码一类只存在于
    job env 的键如果先判断 `.env` 是否存在，就会被静默替换成默认值
    （Batch 947 的 nightly soak 就因此以“SCRAM no password”失败）。
    """
    env_value = os.environ.get(key)
    if env_value:
        return env_value
    env_file = PROJECT_DIR / ".env"
    if not env_file.exists():
        return default
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
    return default if value is None else value


class LocalStack:
    """临时数据库 + mock provider + 一个或两个 demo JVM。"""

    def __init__(self, db_prefix: str, app_port: int, mock_port: int, keep: bool = False):
        self.db_prefix = db_prefix
        self.app_port = app_port
        self.mock_port = mock_port
        self.keep = keep
        self.db_name = f"{db_prefix}_{int(time.time())}_{os.getpid()}"
        self.work_dir = Path("/tmp") / f"rag-verify-{db_prefix}.{os.getpid()}"
        self.log_dir = self.work_dir / "logs"
        self.work_dir.mkdir(parents=True, exist_ok=True)
        self.log_dir.mkdir(parents=True, exist_ok=True)

        self.pg_host = read_env("POSTGRES_HOST", "localhost")
        self.pg_port = read_env("POSTGRES_PORT", "5432")
        self.pg_user = read_env("POSTGRES_USER", "postgres")
        self.pg_password = read_env("POSTGRES_PASSWORD", "")
        self.pg_env = dict(os.environ)
        self.pg_env.pop("SPRING_DATASOURCE_URL", None)
        if self.pg_password:
            self.pg_env["PGPASSWORD"] = self.pg_password

        self.app_process: subprocess.Popen | None = None
        # 多实例验收：额外实例（用于观察调度是否被每个实例重复执行）。
        self.extra_app_processes: list[tuple[subprocess.Popen, int]] = []
        self.mock_process: subprocess.Popen | None = None
        self.databases: list[str] = []

    # ---------- 数据库 ----------

    def _psql(self, db: str, statement: str, *, scalar: bool = True) -> str:
        args = [
            "psql", "-h", self.pg_host, "-p", self.pg_port,
            "-U", self.pg_user, "-d", db, "-X", "-q",
            "-v", "ON_ERROR_STOP=1",
        ]
        args += ["-tAc", statement] if scalar else ["-c", statement]
        result = subprocess.run(args, env=self.pg_env, capture_output=True, text=True)
        if result.returncode != 0:
            raise RuntimeError(
                f"psql failed ({db}): {statement}\n{result.stderr.strip()}")
        return result.stdout.strip()

    def sql(self, statement: str, db: str | None = None) -> str:
        return self._psql(db or self.db_name, statement)

    def sql_rows(self, statement: str, db: str | None = None) -> list[list[str]]:
        output = self.sql(statement, db)
        # psql -tA 的默认字段分隔符是 '|'，不是制表符。
        return [line.split("|") for line in output.splitlines() if line]

    def execute(self, statement: str, db: str | None = None) -> None:
        """执行无返回值语句（INSERT/UPDATE），失败即抛异常。"""
        self._psql(db or self.db_name, statement, scalar=False)

    def create_database(self, name: str | None = None) -> str:
        name = name or self.db_name
        self._psql("postgres", f'DROP DATABASE IF EXISTS "{name}"', scalar=False)
        self._psql("postgres", f'CREATE DATABASE "{name}"', scalar=False)
        if name not in self.databases:
            self.databases.append(name)
        return name

    def drop_database(self, name: str) -> None:
        self._psql(
            "postgres",
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
            f"WHERE datname='{name}' AND pid <> pg_backend_pid()",
            scalar=False,
        )
        self._psql("postgres", f'DROP DATABASE IF EXISTS "{name}"', scalar=False)

    # ---------- 进程 ----------

    def _base_env(self, database: str | None = None) -> dict[str, str]:
        env = {k: v for k, v in os.environ.items()
               if not k.startswith("SPRING_DATASOURCE_")}
        env.update({
            "POSTGRES_HOST": self.pg_host,
            "POSTGRES_PORT": self.pg_port,
            "POSTGRES_DATABASE": database or self.db_name,
            "POSTGRES_USER": self.pg_user,
            "POSTGRES_PASSWORD": self.pg_password,
            # 验收栈必须和 dev/prod 用同一个 PostgreSQL profile：否则
            # EmbeddingProfileBootstrap 不会创建 HNSW 索引，三个后台 worker
            # 也不会注册，压测跑的是"去掉索引与后台任务"的另一套配置。
            "SPRING_PROFILES_ACTIVE": "postgresql",
            "LLM_PROVIDER": "openai",
            "SPRING_AI_OPENAI_API_KEY": "mock-key",
            "SPRING_AI_OPENAI_BASE_URL": f"http://127.0.0.1:{self.mock_port}",
            "SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL": "mock-chat",
            "RAG_EMBEDDING_API_KEY": "mock-key",
            "RAG_EMBEDDING_BASE_URL": f"http://127.0.0.1:{self.mock_port}",
            "RAG_EMBEDDING_MODEL": "BAAI/bge-m3",
            "RAG_EMBEDDING_DIMENSIONS": "1024",
            "RAG_EMBEDDING_PROFILE_KEY": "siliconflow-bge-m3-1024-v1",
            "RAG_EMBEDDING_PROVIDER": "siliconflow",
            "RAG_RETRIEVAL_MIN_SCORE": "0",
            "RAG_SECURITY_ENABLED": "true",
            "RAG_ROOT_API_KEY": ROOT_API_KEY,
        })
        return env

    def start_mock(self, delay_ms: int = 0, stream_delay_ms: int = 20,
                   stream_chunks: int = 0, stream_first_delay_ms: int = 0,
                   error_rate: float = 0.0,
                   extra_env: dict[str, str] | None = None) -> None:
        log = (self.log_dir / "mock.log").open("w")
        mock_env = dict(os.environ)
        if extra_env:
            mock_env.update({k: str(v) for k, v in extra_env.items()})
        self.mock_process = subprocess.Popen(
            [str(MOCK_SCRIPT), "--port", str(self.mock_port),
             "--delay", str(delay_ms),
             "--error-rate", str(error_rate),
             "--stream-delay", str(stream_delay_ms),
             "--stream-chunks", str(stream_chunks),
             "--stream-first-delay", str(stream_first_delay_ms)],
            cwd=str(PROJECT_DIR), stdout=log, stderr=subprocess.STDOUT,
            env=mock_env,
        )
        self._wait_http(f"http://127.0.0.1:{self.mock_port}/health", 60, "mock provider")
        log.close()

    def stop_mock(self) -> None:
        process = self.mock_process
        if process is None:
            return
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        self.mock_process = None

    @staticmethod
    def demo_jar() -> Path:
        candidates = sorted(
            glob.glob(str(DEMO_DIR / "target" / "demo-basic-rag-*.jar")),
            key=os.path.getmtime,
        )
        if not candidates:
            raise RuntimeError(
                "找不到 demo jar；先执行 "
                "`mvn -B -DskipTests -pl demos/demo-basic-rag -am package`")
        return Path(candidates[-1])

    def _jar_is_stale(self, jar: Path) -> bool:
        jar_mtime = jar.stat().st_mtime
        roots = [PROJECT_DIR / name / "src" for name in (
            "spring-ai-rag-api", "spring-ai-rag-core",
            "spring-ai-rag-documents", "spring-ai-rag-starter",
        )]
        roots.append(DEMO_DIR / "src")
        # 静态产物（index.html / hash chunk / svg）不是 .java/.yml，但同样会被
        # 打进 jar；漏掉它们会让验收跑在上一版前端上。这里把它们纳入新鲜度判断。
        static_suffixes = {
            ".java", ".yml", ".yaml", ".properties",
            ".html", ".js", ".css", ".svg",
        }
        for root in roots:
            if not root.exists():
                continue
            for path in root.rglob("*"):
                if path.suffix in static_suffixes \
                        and path.stat().st_mtime > jar_mtime:
                    return True
        return False

    def build_demo_if_needed(self, force: bool = False) -> None:
        jar = self.demo_jar() if not force else None
        if jar is not None and not self._jar_is_stale(jar):
            return
        # demo 模块不在根 reactor 里：先安装被依赖的模块，再构建 demo 本身。
        root_log = (self.log_dir / "maven-install.log").open("w")
        root_result = subprocess.run(
            ["mvn", "-B", "-DskipTests", "install"],
            cwd=str(PROJECT_DIR), stdout=root_log, stderr=subprocess.STDOUT,
            env=dict(os.environ),
        )
        root_log.close()
        if root_result.returncode != 0:
            raise RuntimeError(
                f"根模块构建失败，见 {self.log_dir / 'maven-install.log'}")
        demo_log = (self.log_dir / "maven-package.log").open("w")
        # 必须 clean：demo 自身的 class 没变时 jar:jar 会认为产物已是最新，
        # spring-boot:repackage 随后复用旧的 .original，导致嵌套的 core jar
        # 仍是上一版——压测会跑在旧代码上却看不出来。
        demo_result = subprocess.run(
            ["mvn", "-B", "-DskipTests", "clean", "package"],
            cwd=str(DEMO_DIR), stdout=demo_log, stderr=subprocess.STDOUT,
            env=dict(os.environ),
        )
        demo_log.close()
        if demo_result.returncode != 0:
            raise RuntimeError(
                f"demo 构建失败，见 {self.log_dir / 'maven-package.log'}")

    def start_app(self, extra_env: dict[str, str] | None = None,
                  port: int | None = None,
                  database: str | None = None,
                  extra_args: list[str] | None = None,
                  keep_existing: bool = False) -> str:
        if not keep_existing:
            self.stop_app()
        port = port or self.app_port
        env = self._base_env(database)
        if extra_env:
            env.update({k: str(v) for k, v in extra_env.items()})
        jar = self.demo_jar()
        log = (self.log_dir / f"app-{port}.log").open("w")
        args = ["java", "-jar", str(jar), f"--server.port={port}",
                # demo 自带的 application.yml 会遮蔽 core jar 里的同名配置，
                # 因此 root key 直接走命令行属性，避免依赖 RAG_ROOT_API_KEY 绑定。
                f"--rag.security.root-api-key={ROOT_API_KEY}"]
        args.extend(extra_args or [])
        process = subprocess.Popen(
            args,
            cwd=str(PROJECT_DIR), stdout=log, stderr=subprocess.STDOUT, env=env,
        )
        log.close()
        self._wait_http(f"http://127.0.0.1:{port}/actuator/health", 180,
                        f"demo（端口 {port}）", process=process,
                        log_file=self.log_dir / f"app-{port}.log")
        if keep_existing:
            self.extra_app_processes.append((process, port))
        else:
            self.app_process = process
        return f"http://127.0.0.1:{port}"

    def kill_app(self) -> None:
        """发送 SIGKILL，模拟进程被强杀；不等待任何优雅关闭钩子。"""
        process = self.app_process
        if process is None:
            return
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
        self.app_process = None
        self._kill_port(self.app_port)

    def stop_app(self) -> None:
        process = self.app_process
        if process is None:
            return
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        self.app_process = None
        for extra, extra_port in self.extra_app_processes:
            if extra.poll() is None:
                extra.terminate()
                try:
                    extra.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    extra.kill()
                    extra.wait(timeout=10)
            self._kill_port(extra_port)
        self.extra_app_processes = []
        self._kill_port(self.app_port)

    def _kill_port(self, port: int) -> None:
        result = subprocess.run(
            ["lsof", "-ti", f":{port}", "-sTCP:LISTEN"],
            capture_output=True, text=True,
        )
        for pid in (p.strip() for p in result.stdout.splitlines()):
            if pid.isdigit() and int(pid) != os.getpid():
                try:
                    os.kill(int(pid), 15)
                except ProcessLookupError:
                    pass

    def _wait_http(self, url: str, seconds: int, label: str,
                   process: subprocess.Popen | None = None,
                   log_file: Path | None = None) -> None:
        import urllib.error
        import urllib.request
        deadline = time.time() + seconds
        while time.time() < deadline:
            if process is not None and process.poll() is not None:
                break
            try:
                with urllib.request.urlopen(url, timeout=2) as response:
                    if response.status < 500:
                        return
            except (urllib.error.URLError, OSError):
                pass
            time.sleep(1)
        detail = ""
        if log_file is not None and log_file.exists():
            detail = "\n" + "\n".join(log_file.read_text(errors="replace").splitlines()[-40:])
        raise RuntimeError(f"{label} 未在 {seconds}s 内就绪{detail}")

    def cleanup(self, *, drop_databases: bool = True) -> None:
        self.stop_app()
        if self.mock_process is not None and self.mock_process.poll() is None:
            self.mock_process.terminate()
            try:
                self.mock_process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.mock_process.kill()
        self.mock_process = None
        if drop_databases:
            for name in list(self.databases):
                try:
                    self.drop_database(name)
                except Exception as exc:  # 清理失败不能掩盖验收结果
                    print(f"[WARN] 删除临时库 {name} 失败：{exc}", file=sys.stderr)
                else:
                    self.databases.remove(name)
        if self.keep:
            print(f"[INFO] 保留临时目录：{self.work_dir}")
        else:
            shutil.rmtree(self.work_dir, ignore_errors=True)


# ---------- HTTP ----------


def http_request(method: str, url: str, *, headers: dict[str, str] | None = None,
                 body: bytes | None = None, timeout: float = 30.0) -> tuple[int, str]:
    import urllib.error
    import urllib.request
    request = urllib.request.Request(url, data=body, method=method)
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def http_request_full(method: str, url: str, *, headers: dict[str, str] | None = None,
                      body: bytes | None = None,
                      timeout: float = 30.0) -> tuple[int, dict[str, str], str]:
    """与 http_request 相同，但同时返回响应头（错误契约要校验 X-RAG-Failure-Class）。"""
    import urllib.error
    import urllib.request
    request = urllib.request.Request(url, data=body, method=method)
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            response_headers = {k.lower(): v for k, v in response.headers.items()}
            return response.status, response_headers, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        response_headers = {k.lower(): v for k, v in error.headers.items()}
        return error.code, response_headers, error.read().decode("utf-8", "replace")


def json_request(method: str, url: str, payload: dict, *,
                 api_key: str = ROOT_API_KEY,
                 extra_headers: dict[str, str] | None = None,
                 timeout: float = 60.0) -> tuple[int, str]:
    headers = {"Content-Type": "application/json", "X-API-Key": api_key}
    if extra_headers:
        headers.update(extra_headers)
    return http_request(method, url, headers=headers,
                        body=json.dumps(payload).encode(), timeout=timeout)


def multipart_request(url: str, field: str, filename: str, content: bytes,
                      form: dict[str, str], *, api_key: str = ROOT_API_KEY,
                      extra_headers: dict[str, str] | None = None,
                      timeout: float = 120.0) -> tuple[int, str]:
    boundary = f"----rag-verify-{os.getpid()}-{time.time_ns()}"
    parts: list[bytes] = []
    for name, value in form.items():
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"'
            f"\r\n\r\n{value}\r\n".encode())
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; '
        f'filename="{filename}"\r\nContent-Type: text/plain\r\n\r\n'.encode()
        + content + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    headers = {
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "X-API-Key": api_key,
    }
    if extra_headers:
        headers.update(extra_headers)
    return http_request("POST", url, headers=headers,
                        body=b"".join(parts), timeout=timeout)
