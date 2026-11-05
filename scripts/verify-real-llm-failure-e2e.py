#!/usr/bin/env python3
# =============================================================================
# 真实模型失败语义 / 预算 / provider 中立性端到端验收
#
# 和 `verify-upstream-recovery-e2e.py` 的区别：这里**真的调用上游模型**
# （LongCat / MiniMax），只在需要注入 429、5xx、超时、断流时在服务前置一个
# 本地故障代理；代理仍然转发真实请求到真实上游，不是 mock provider。
#
# 覆盖：
#   1. 真实非流式 / 流式成功路径（内容非空、SSE 终态完整）
#   2. 429：有界退避后重试；上游 Retry-After 透传
#   3. 5xx / 连接中断：映射 503，不 500
#   4. 读超时：映射 504
#   5. SSE 上游断流：HTTP 200 + error 终态，不悬挂
#   6. 客户端主动断开：会话随后可继续，不残留 CHAT_TURN_IN_PROGRESS
#   7. 预算：CHAT_BUDGET_EXHAUSTED / CHAT_CONTEXT_BUDGET_EXCEEDED 可诊断
#   8. provider 中立性：同一批 HTTP 接口分别对 LongCat 与 MiniMax 成功
#   9. 路径实测：base-url 不带 `/v1` 时 Spring AI 实际拼成 `<base>/v1/chat/completions`
#
# 成本与分层：
#   - 默认不执行，`REAL_LLM_FAILURE_E2E=1` 或 `--run` 才跑；不进 PR 快层。
#   - nightly 通过 `.env`/仓库 secret 提供密钥；脚本自身从不打印密钥。
#
# Usage:
#   REAL_LLM_FAILURE_E2E=1 python3 scripts/verify-real-llm-failure-e2e.py
#   python3 scripts/verify-real-llm-failure-e2e.py --run --providers openai,minimax
#   python3 scripts/verify-real-llm-failure-e2e.py --run --providers openai --skip-budget
#
# 退出码：0=通过/显式跳过；1=断言失败；2=前置条件缺失。
# =============================================================================
from __future__ import annotations

import argparse
import http.client
import json
import os
import random
import re
import signal
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUN_DIR = ROOT / ".verification" / "real-llm-failure-e2e" / time.strftime("%Y%m%d-%H%M%S")
PASS = 0
FAIL = 0


def ok(message: str) -> None:
    global PASS
    PASS += 1
    print(f"OK: {message}", flush=True)


def bad(message: str) -> None:
    global FAIL
    FAIL += 1
    print(f"FAIL: {message}", flush=True)


def load_dotenv(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        value = value.strip().strip('"').strip("'")
        value = re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}",
                       lambda m: values.get(m.group(1), ""), value)
        values[name.strip()] = value
    return values


def free_port() -> int:
    """选一个高段非 ephemeral 端口，并按启动脚本的判定排除任何占用。

    启动脚本用 `lsof -ti :port`（连临时源端口也算占用），而 macOS 的
    ephemeral 区间会覆盖 32768+；因此只在 20000-29999 里挑，且要求
    lsof 完全没有命中，避免刚选完就被客户端源端口撞上。
    """
    for _ in range(60):
        port = random.randint(20000, 29999)
        occupied = subprocess.run(
            ["lsof", "-nP", "-ti", f":{port}"],
            capture_output=True, text=True, timeout=10)
        if occupied.stdout.strip():
            continue
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            try:
                sock.bind(("127.0.0.1", port))
            except OSError:
                continue
        return port
    raise RuntimeError("could not find a free listening port")


# ---------------------------------------------------------------------------
# 故障代理：真实转发到上游，必要时注入故障
# ---------------------------------------------------------------------------
@dataclass
class FaultProxy:
    upstream_base: str
    prefix: str = "/openai"
    mode: str = "pass"
    delay_seconds: float = 0.0
    rate_limit_left: int = 0
    requests: list[dict] = field(default_factory=list)
    lock: threading.Lock = field(default_factory=threading.Lock)

    def __post_init__(self) -> None:
        proxy = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *_args):  # 只保留结构化记录，避免噪声
                return

            def handle(self):
                try:
                    super().handle()
                except (ConnectionResetError, BrokenPipeError):
                    # 客户端/上游主动断开时不再打印堆栈，故障场景本就如此。
                    pass

            def do_POST(self):
                proxy.handle(self)

            def do_GET(self):
                if self.path == "/__health":
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(b'{"status":"UP"}')
                else:
                    self.send_error(404)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = int(self.server.server_address[1])
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def set_mode(self, mode: str, delay_seconds: float = 0.0,
                 rate_limit_left: int = 0) -> None:
        with self.lock:
            self.mode = mode
            self.delay_seconds = delay_seconds
            self.rate_limit_left = rate_limit_left
            self.requests = []

    def stats(self) -> dict:
        with self.lock:
            return {"count": len(self.requests),
                    "requests": list(self.requests)}

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def handle(self, handler: BaseHTTPRequestHandler) -> None:
        started = time.monotonic()
        path = handler.path
        mode = self.mode

        if path != f"{self.prefix}/v1/chat/completions":
            self.record(path, mode, None, "unexpected path")
            handler.send_response(404)
            handler.send_header("Content-Type", "application/json")
            handler.end_headers()
            handler.wfile.write(b'{"error":"unexpected proxy path"}')
            return

        if mode == "disconnect":
            self.record(path, mode, None, "closed before response")
            handler.close_connection = True
            try:
                handler.connection.close()
            except OSError:
                pass
            return

        if mode == "timeout":
            self.record(path, mode, None, "delayed response")
            time.sleep(self.delay_seconds)
            handler.close_connection = True
            try:
                handler.connection.close()
            except OSError:
                pass
            return

        if mode == "rate_limit_once" and self.rate_limit_left > 0:
            with self.lock:
                self.rate_limit_left -= 1
            self.record(path, mode, 429, "injected")
            self.send_json_error(handler, 429, "rate_limit_exceeded",
                                 "injected rate limit", {"Retry-After": "2"})
            return

        if mode == "rate_limit":
            self.record(path, mode, 429, "injected")
            self.send_json_error(handler, 429, "rate_limit_exceeded",
                                 "injected rate limit", {"Retry-After": "2"})
            return

        if mode == "unavailable":
            self.record(path, mode, 503, "injected")
            self.send_json_error(handler, 503, "server_error", "injected 503")
            return

        if mode == "server_error":
            self.record(path, mode, 500, "injected")
            self.send_json_error(handler, 500, "server_error", "injected 500")
            return

        self.forward(handler, path, mode, started)

    def read_request_body(self, handler: BaseHTTPRequestHandler) -> bytes:
        """非流式 chat 走 chunked，流式可能带 Content-Length；两种都要转发。"""
        if handler.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks: list[bytes] = []
            while True:
                line = handler.rfile.readline()
                if not line:
                    break
                size_text = line.strip().split(b";", 1)[0]
                if not size_text:
                    continue
                size = int(size_text, 16)
                if size == 0:
                    handler.rfile.readline()
                    break
                chunks.append(handler.rfile.read(size))
                handler.rfile.read(2)
            return b"".join(chunks)
        length = int(handler.headers.get("Content-Length") or 0)
        return handler.rfile.read(length) if length else b""

    def forward(self, handler: BaseHTTPRequestHandler, path: str, mode: str,
                started: float) -> None:
        body = self.read_request_body(handler)
        upstream_path = path[len(self.prefix):] if path.startswith(self.prefix) else path
        absolute = self.upstream_base.rstrip("/") + upstream_path
        scheme = "https" if absolute.startswith("https://") else "http"
        without_scheme = absolute.split("://", 1)[1]
        host, _, rest = without_scheme.partition("/")
        request_path = "/" + rest

        headers = {
            "Content-Type": handler.headers.get("Content-Type", "application/json"),
            "Accept": handler.headers.get("Accept", "*/*"),
            "Authorization": handler.headers.get("Authorization", ""),
        }
        connection = (http.client.HTTPSConnection(host, timeout=120,
                                                  context=ssl.create_default_context())
                      if scheme == "https" else http.client.HTTPConnection(host, timeout=120))
        try:
            connection.request("POST", request_path, body=body, headers=headers)
            response = connection.getresponse()
        except Exception as error:  # noqa: BLE001 - 代理必须把网络错误变成断流
            self.record(path, mode, None, f"upstream error: {type(error).__name__}")
            handler.close_connection = True
            try:
                handler.connection.close()
            except OSError:
                pass
            return

        status = response.status
        content_type = response.getheader("Content-Type", "application/json")
        if mode == "stream_disconnect":
            handler.send_response(status)
            handler.send_header("Content-Type", content_type)
            handler.send_header("Connection", "close")
            handler.end_headers()
            forwarded = 0
            handler.close_connection = True
            try:
                while forwarded < 40:
                    chunk = response.read(16)
                    if not chunk:
                        break
                    handler.wfile.write(chunk)
                    handler.wfile.flush()
                    forwarded += len(chunk)
                handler.connection.close()
            except OSError:
                pass
            self.record(path, mode, status, f"closed after {forwarded} bytes")
            connection.close()
            return

        payload = response.read()
        note = "forwarded"
        if status >= 400:
            # 上游错误体不含密钥，保留前 200 字节用于诊断（不要记录请求头）。
            note = f"upstream body: {payload[:200].decode('utf-8', 'replace')}"
        self.record(path, mode, status, note,
                    round(time.monotonic() - started, 3), len(payload))
        handler.send_response(status)
        handler.send_header("Content-Type", content_type)
        handler.send_header("Content-Length", str(len(payload)))
        handler.end_headers()
        try:
            handler.wfile.write(payload)
        except OSError:
            pass
        connection.close()

    def send_json_error(self, handler: BaseHTTPRequestHandler, status: int,
                        code: str, message: str,
                        extra_headers: dict[str, str] | None = None) -> None:
        body = json.dumps({"error": {"message": message, "type": code, "code": code}}).encode()
        handler.send_response(status)
        handler.send_header("Content-Type", "application/json")
        handler.send_header("Content-Length", str(len(body)))
        for name, value in (extra_headers or {}).items():
            handler.send_header(name, value)
        handler.end_headers()
        try:
            handler.wfile.write(body)
        except OSError:
            pass

    def record(self, path: str, mode: str, status: int | None, note: str,
               duration: float | None = None, size: int = 0) -> None:
        with self.lock:
            self.requests.append({
                "path": path, "mode": mode, "status": status, "note": note,
                "durationSeconds": duration, "bytes": size,
            })


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------
def http_json(method: str, url: str, payload: dict | None,
              headers: dict[str, str], timeout: float = 120.0) -> tuple[int, dict, dict]:
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    for name, value in headers.items():
        request.add_header(name, value)
    if data:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
            parsed = json.loads(raw) if raw else {}
            return response.status, parsed, dict(response.headers)
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = {"raw": raw.decode("utf-8", "replace")}
        return error.code, parsed, dict(error.headers)


def read_sse(url: str, payload: dict, headers: dict[str, str],
             timeout: float = 180.0, abort_after_events: int | None = None,
             abort_after_seconds: float | None = None) -> tuple[int, list[dict], str]:
    absolute = urllib.parse.urlparse(url)
    connection = http.client.HTTPConnection(absolute.hostname, absolute.port, timeout=timeout)
    body = json.dumps(payload).encode()
    request_headers = {"Content-Type": "application/json", **headers}
    connection.request("POST", absolute.path, body=body, headers=request_headers)
    response = connection.getresponse()
    events: list[dict] = []
    raw_lines: list[str] = []
    started = time.monotonic()
    try:
        while True:
            line = response.readline()
            if not line:
                break
            text = line.decode("utf-8", "replace").rstrip("\r\n")
            raw_lines.append(text)
            if text.startswith("data:"):
                data = text[5:].strip()
                if data and data != "[DONE]":
                    try:
                        events.append(json.loads(data))
                    except json.JSONDecodeError:
                        pass
            if abort_after_events is not None and len(events) >= abort_after_events:
                break
            if abort_after_seconds is not None and time.monotonic() - started >= abort_after_seconds:
                break
    finally:
        connection.close()
    return response.status, events, "\n".join(raw_lines)


def sse_content(events: list[dict]) -> str:
    parts: list[str] = []
    for event in events:
        for choice in event.get("choices") or []:
            delta = choice.get("delta") or {}
            if isinstance(delta.get("content"), str):
                parts.append(delta["content"])
        if isinstance(event.get("content"), str):
            parts.append(event["content"])
        if isinstance(event.get("answer"), str):
            parts.append(event["answer"])
    return "".join(parts)


def sse_terminal(events: list[dict]) -> tuple[bool, bool]:
    done = any(isinstance(event.get("evidenceAssessment"), dict)
               or str(event.get("type", "")).lower() in ("done", "completion")
               or bool(event.get("done"))
               for event in events)
    error = any("error" in event for event in events)
    return done, error


# ---------------------------------------------------------------------------
# Server lifecycle
# ---------------------------------------------------------------------------
@dataclass
class Server:
    port: int
    pid: int
    log_path: Path


class Harness:
    def __init__(self, env: dict[str, str], providers: list[str],
                 skip_budget: bool = False) -> None:
        self.env = env
        self.providers = providers
        self.skip_budget = skip_budget
        self.db_name = f"rag_real_llm_{time.strftime('%Y%m%d%H%M%S')}_{random.randint(1000, 9999)}"
        self.servers: list[int] = []
        self.proxy: FaultProxy | None = None
        self.root_key = env.get("RAG_ROOT_API_KEY") or env.get("RAG_API_KEY") or ""
        self.auth = {"X-API-Key": self.root_key} if self.root_key else {}
        RUN_DIR.mkdir(parents=True, exist_ok=True)

    # -- infrastructure -----------------------------------------------------
    def psql(self, sql: str, database: str = "postgres") -> subprocess.CompletedProcess:
        command = [
            "psql", "-h", self.env.get("POSTGRES_HOST", "127.0.0.1"),
            "-p", self.env.get("POSTGRES_PORT", "5432"),
            "-U", self.env.get("POSTGRES_USER", "postgres"),
            "-d", database, "-v", "ON_ERROR_STOP=0", "-Xq", "-c", sql,
        ]
        environment = os.environ.copy()
        environment["PGPASSWORD"] = self.env.get("POSTGRES_PASSWORD", "")
        return subprocess.run(command, capture_output=True, text=True,
                              env=environment, timeout=60)

    def prepare_database(self) -> None:
        result = self.psql(f'CREATE DATABASE "{self.db_name}"')
        if result.returncode != 0:
            raise RuntimeError(f"cannot create database: {result.stderr.strip()}")
        for extension in ("vector", "pg_trgm", "pg_jieba"):
            result = self.psql(f'CREATE EXTENSION IF NOT EXISTS "{extension}"',
                               self.db_name)
            print(f"{extension}: {'available' if result.returncode == 0 else 'unavailable (optional)'}",
                  flush=True)

    def drop_database(self) -> None:
        self.psql(f'DROP DATABASE IF EXISTS "{self.db_name}" WITH (FORCE)')

    def serve_args(self) -> list[str]:
        return [str(ROOT / "scripts" / "start-real-e2e-server.sh")]

    def start_server(self, provider: str, base_url: str, extra: dict[str, str]) -> Server:
        port = free_port()
        environment = os.environ.copy()
        environment.update(self.env)
        environment.update({
            "SPRING_PROFILES_ACTIVE": "postgresql",
            "SERVER_PORT": str(port),
            "LLM_PROVIDER": provider,
            "APP_LLM_PROVIDER": provider,
            "REAL_LLM_CHAT_PROVIDER": provider,
            "MODELS_CONFIG_FILE": "none",
            "RAG_SECURITY_ENABLED": "false",
            "SPRING_DATASOURCE_URL":
                f"jdbc:postgresql://127.0.0.1:{self.env.get('POSTGRES_PORT', '5432')}/{self.db_name}",
            "SPRING_DATASOURCE_USERNAME": self.env.get("POSTGRES_USER", "postgres"),
            "SPRING_DATASOURCE_PASSWORD": self.env.get("POSTGRES_PASSWORD", ""),
        })
        if provider == "openai":
            environment["SPRING_AI_OPENAI_BASE_URL"] = base_url
        elif provider == "minimax":
            environment["SPRING_AI_MINIMAX_BASE_URL"] = base_url
        environment.update(extra)

        log_path = RUN_DIR / f"server-{provider}-{port}.log"
        with open(log_path, "w", encoding="utf-8") as log:
            process = subprocess.Popen(self.serve_args(), cwd=str(ROOT),
                                       env=environment, stdout=log,
                                       stderr=subprocess.STDOUT, start_new_session=True)
        pid = 0
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
            match = re.search(r"^PID (\d+)$", text, re.MULTILINE)
            if match:
                pid = int(match.group(1))
                break
            if process.poll() is not None:
                raise RuntimeError(f"server launcher exited; see {log_path}")
            time.sleep(0.25)
        if not pid:
            raise RuntimeError(f"server did not report a PID; see {log_path}")

        health_deadline = time.monotonic() + 180
        while time.monotonic() < health_deadline:
            try:
                status, body, _ = http_json(
                    "GET", f"http://127.0.0.1:{port}/actuator/health", None, {}, timeout=3)
                if status == 200 and body.get("status") == "UP":
                    self.servers.append(pid)
                    return Server(port=port, pid=pid, log_path=log_path)
            except Exception:  # noqa: BLE001 - 启动期连接失败是正常的
                pass
            if process.poll() is not None:
                raise RuntimeError(f"server process exited before health; see {log_path}")
            time.sleep(1)
        raise RuntimeError(f"server health timeout; see {log_path}")

    def stop_server(self, server: Server) -> None:
        try:
            os.kill(server.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            try:
                os.kill(server.pid, 0)
            except ProcessLookupError:
                break
            time.sleep(0.25)
        else:
            try:
                os.kill(server.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

    def close(self) -> None:
        for pid in self.servers:
            try:
                os.kill(pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        if self.proxy:
            self.proxy.close()
        self.drop_database()

    # -- API helpers --------------------------------------------------------
    def api(self, server: Server) -> str:
        return f"http://127.0.0.1:{server.port}/api/v1/rag"

    def ask(self, server: Server, message: str, session: str,
            mode: str = "PLAIN", timeout: float = 180.0,
            extra: dict | None = None) -> tuple[int, dict, dict]:
        payload = {"message": message, "sessionId": session, "mode": mode}
        if extra:
            payload.update(extra)
        return http_json("POST", f"{self.api(server)}/chat/ask", payload,
                         self.auth, timeout=timeout)

    def stream(self, server: Server, message: str, session: str,
               timeout: float = 180.0, extra: dict | None = None) -> tuple[int, list[dict], str]:
        payload = {"message": message, "sessionId": session, "mode": "KNOWLEDGE"}
        if extra:
            payload.update(extra)
        return read_sse(f"{self.api(server)}/chat/stream", payload, self.auth, timeout=timeout)

    def setup_knowledge_document(self, server: Server, token: str) -> dict:
        collection_key = f"real-failure-{token.lower()}"
        status, collection, _ = http_json(
            "POST", f"{self.api(server)}/collections",
            {"collectionKey": collection_key, "name": f"real-failure-{token}",
             "description": "real LLM failure/budget verification", "domainId": "default"},
            self.auth)
        if status not in (200, 201) or not collection.get("id"):
            raise RuntimeError(f"create collection failed: HTTP {status} {collection}")
        status, document, _ = http_json(
            "POST", f"{self.api(server)}/documents",
            {"title": f"Real failure E2E {token}",
             "content": "This is automated verification test data. "
                        f"The release verification code is {token}. "
                        "The code may be repeated verbatim when requested.",
             "collectionKey": collection_key},
            self.auth)
        if status not in (200, 201) or not document.get("id"):
            raise RuntimeError(f"create document failed: HTTP {status} {document}")
        status, associated, _ = http_json(
            "POST", f"{self.api(server)}/collections/by-key/documents?collectionKey={collection_key}",
            {"documentId": document["id"],
             "expectedDocumentRevision": document.get("documentRevision")},
            self.auth)
        if status not in (200, 201):
            raise RuntimeError(f"associate document failed: HTTP {status} {associated}")
        status, embedded, _ = http_json(
            "POST", f"{self.api(server)}/documents/{document['id']}/embed?force=true",
            None, self.auth)
        stored = int(embedded.get("embeddingsStored") or embedded.get("chunksCreated") or 0)
        if status not in (200, 201) or stored <= 0:
            raise RuntimeError(f"embed failed: HTTP {status} {embedded}")
        return {"collectionKey": collection_key, "documentId": document["id"], "token": token}

    # -- scenarios ----------------------------------------------------------
    def real_success(self, server: Server, provider: str, check_agent: bool = True) -> None:
        status, body, _ = self.ask(server, "Reply with the single word READY.", str(uuid.uuid4()))
        answer = body.get("answer") or ""
        if status == 200 and answer.strip():
            ok(f"{provider}: real non-stream chat returned non-empty content")
        else:
            bad(f"{provider}: real non-stream chat failed HTTP {status}: {body}")

        token = f"REAL_E2E_FAIL_{int(time.time())}_{random.randint(100, 999)}"
        knowledge = self.setup_knowledge_document(server, token)

        status, events, raw = self.stream(
            server,
            "Using only the selected verification document, return the release "
            "verification code exactly. The code begins with REAL_E2E_FAIL_.",
            str(uuid.uuid4()),
            extra={"maxResults": 5, "useHybridSearch": True, "useRerank": True,
                   "collectionKeys": [knowledge["collectionKey"]],
                   "documentIds": [knowledge["documentId"]]})
        content = sse_content(events)
        done, error = sse_terminal(events)
        if status == 200 and events and content.strip() and done and not error:
            ok(f"{provider}: real SSE returned content and a terminal done event")
        else:
            bad(f"{provider}: real SSE incomplete HTTP {status} events={len(events)} "
                f"content_len={len(content)} done={done} error={error}")

        if check_agent:
            status, agent, _ = self.ask(
                server,
                "Use the knowledge retrieval tool for the selected verification document. "
                "Return the release verification code exactly and cite the retrieved evidence "
                "with the provided citation handle. The code begins with REAL_E2E_FAIL_.",
                str(uuid.uuid4()), mode="AGENT",
                extra={"maxResults": 5, "useHybridSearch": True, "useRerank": True,
                       "collectionKeys": [knowledge["collectionKey"]],
                       "documentIds": [knowledge["documentId"]]})
            sources = agent.get("sources") or []
            metadata = agent.get("metadata") or {}
            transcript = metadata.get("toolTranscript") or []
            answer = agent.get("answer") or ""
            if (status == 200 and answer.strip() and sources and transcript
                    and token in answer
                    and all(source.get("sourceHandle") and source.get("citationId")
                            for source in sources)):
                ok(f"{provider}: agent really invoked retrieval and returned cited sources")
            else:
                bad(f"{provider}: agent tool/citation check failed HTTP {status} "
                    f"answer_len={len(answer)} sources={len(sources)} tool_calls={len(transcript)}")

    def failure_modes(self, server: Server) -> None:
        assert self.proxy is not None
        proxy = self.proxy

        # 0) 路径实测：base-url 是 `/openai`，Spring AI 必须拼成 `/openai/v1/chat/completions`
        proxy.set_mode("pass")
        status, body, _ = self.ask(server, "Reply with OK.", str(uuid.uuid4()))
        paths = [item["path"] for item in proxy.stats()["requests"]]
        if status == 200 and paths == ["/openai/v1/chat/completions"]:
            ok("base-url without /v1 composes to /openai/v1/chat/completions")
        else:
            bad(f"unexpected upstream path(s): {paths} (HTTP {status}); "
                f"requests={proxy.stats()['requests']}")

        # 1) 429 一次：必须等待 Retry-After 后有界重试并成功
        proxy.set_mode("rate_limit_once", rate_limit_left=1)
        started = time.monotonic()
        status, body, _ = self.ask(server, "Reply with OK.", str(uuid.uuid4()))
        elapsed = time.monotonic() - started
        forwarded = [item for item in proxy.stats()["requests"]
                     if item["status"] is not None]
        if status == 200 and forwarded and elapsed >= 1.8:
            ok(f"429 once: retried after Retry-After and succeeded in {elapsed:.1f}s")
        else:
            bad(f"429 once did not retry: HTTP {status} elapsed={elapsed:.1f}s "
                f"requests={proxy.stats()['requests']}")

        # 2) 429 持续：有界重试，最终 429 + RATE_LIMITED + Retry-After 透传
        proxy.set_mode("rate_limit", rate_limit_left=0)
        started = time.monotonic()
        status, body, headers = self.ask(server, "Reply with OK.", str(uuid.uuid4()))
        elapsed = time.monotonic() - started
        details = body.get("failureDetails") or {}
        attempts = proxy.stats()["count"]
        if (status == 429
                and headers.get("X-RAG-Failure-Class") == "RATE_LIMITED"
                and details.get("reason") == "UPSTREAM_RATE_LIMITED"
                and details.get("upstreamStatus") == 429
                and "injected rate limit" in (details.get("upstreamMessage") or "")
                and headers.get("Retry-After") == "2"
                and 2 <= attempts <= 3
                and elapsed >= 1.8):
            ok(f"persistent 429: bounded retry attempts={attempts}, final 429, Retry-After passed through")
        else:
            bad(f"persistent 429 mismatch: HTTP {status} class={headers.get('X-RAG-Failure-Class')} "
                f"retry-after={headers.get('Retry-After')} attempts={attempts} "
                f"elapsed={elapsed:.1f}s details={details}")

        # 3) 上游 5xx：不 500，映射 503，有界重试
        proxy.set_mode("unavailable")
        status, body, headers = self.ask(server, "Reply with OK.", str(uuid.uuid4()))
        details = body.get("failureDetails") or {}
        attempts = proxy.stats()["count"]
        if (status == 503
                and headers.get("X-RAG-Failure-Class") == "DEPENDENCY_UNAVAILABLE"
                and details.get("reason") == "UPSTREAM_UNAVAILABLE"
                and details.get("upstreamStatus") == 503
                and "injected 503" in (details.get("upstreamMessage") or "")
                and 2 <= attempts <= 3):
            ok("upstream 503: mapped to 503 DEPENDENCY_UNAVAILABLE after bounded retries (no 500)")
        else:
            bad(f"upstream 503 mismatch: HTTP {status} class={headers.get('X-RAG-Failure-Class')} "
                f"attempts={attempts} details={details}")

        proxy.set_mode("server_error")
        status, body, headers = self.ask(server, "Reply with OK.", str(uuid.uuid4()))
        details = body.get("failureDetails") or {}
        if (status == 503
                and headers.get("X-RAG-Failure-Class") == "DEPENDENCY_UNAVAILABLE"
                and details.get("reason") == "UPSTREAM_UNAVAILABLE"
                and details.get("upstreamStatus") == 500
                and "injected 500" in (details.get("upstreamMessage") or "")):
            ok("upstream 500: mapped to 503, never leaked as 500")
        else:
            bad(f"upstream 500 mismatch: HTTP {status} class={headers.get('X-RAG-Failure-Class')} "
                f"details={details}")

        # 4) 读超时：代理不回包，客户端读超时映射 504
        proxy.set_mode("timeout", delay_seconds=25.0)
        started = time.monotonic()
        status, body, headers = self.ask(server, "Reply with OK.", str(uuid.uuid4()))
        elapsed = time.monotonic() - started
        details = body.get("failureDetails") or {}
        if (status == 504
                and headers.get("X-RAG-Failure-Class") == "TIMEOUT"
                and details.get("reason") == "UPSTREAM_TIMEOUT"
                and elapsed >= 5.0):
            ok(f"read timeout: mapped to 504 TIMEOUT after {elapsed:.1f}s (not a generic 500/timeout)")
        else:
            bad(f"read timeout mismatch: HTTP {status} class={headers.get('X-RAG-Failure-Class')} "
                f"elapsed={elapsed:.1f}s details={details}")

        # 5) 连接断开：不悬挂，映射 503
        proxy.set_mode("disconnect")
        started = time.monotonic()
        status, body, headers = self.ask(server, "Reply with OK.", str(uuid.uuid4()))
        elapsed = time.monotonic() - started
        details = body.get("failureDetails") or {}
        if (status == 503 and details.get("reason") == "UPSTREAM_CONNECTION"
                and details.get("retryable") is True and elapsed < 30):
            ok(f"connection reset: bounded retries, 503 with real reason in {elapsed:.1f}s")
        else:
            bad(f"connection reset mismatch: HTTP {status} elapsed={elapsed:.1f}s details={details}")

        # 6) SSE 上游断流：HTTP 200 + error 终态，客户端不悬挂
        proxy.set_mode("stream_disconnect")
        started = time.monotonic()
        status, events, raw = self.stream(server, "Reply with one short sentence.",
                                          str(uuid.uuid4()))
        elapsed = time.monotonic() - started
        done, error = sse_terminal(events)
        error_details = {}
        for event in events:
            if isinstance(event.get("error"), dict):
                error_details = event["error"].get("failureDetails") or {}
        if status == 200 and error and not done and elapsed < 45:
            ok(f"SSE upstream disconnect: error terminal event in {elapsed:.1f}s "
                f"(reason={error_details.get('reason')})")
        else:
            bad(f"SSE upstream disconnect mismatch: HTTP {status} elapsed={elapsed:.1f}s "
                f"done={done} error={error} events={len(events)}")

        # 7) 客户端主动断开：随后同会话可继续，不残留 CHAT_TURN_IN_PROGRESS
        proxy.set_mode("pass")
        session = str(uuid.uuid4())
        try:
            status, events, _ = read_sse(
                f"{self.api(server)}/chat/stream",
                {"message": "Write a short paragraph about spring.", "sessionId": session,
                 "mode": "PLAIN"},
                self.auth, timeout=30, abort_after_events=1)
        except Exception:  # noqa: BLE001 - 主动 abort 的异常是预期
            status = -1
        time.sleep(1)
        status, body, _ = self.ask(server, "Reply with OK.", session)
        if status == 200 and (body.get("answer") or "").strip():
            ok("client abort: follow-up on the same session succeeded (no dangling turn)")
        else:
            bad(f"client abort left a dangling turn: HTTP {status} {body}")

    def budget_modes(self, server: Server) -> None:
        # 上下文预算：系统提示 + 用户消息超过窗口。请求体上限是 10000 字符，
        # 因此把窗口压到 7000（扣除输出预留与安全边际后可用 5720）；
        # 实测 9600 个中文汉字约 6.4k token，足以越过。
        huge = "这是一段用于验证长上下文预算的中文测试文本。" * 320  # ~9600 字
        status, body, _ = self.ask(server, huge, str(uuid.uuid4()))
        if (status == 422 and body.get("error") == "CHAT_CONTEXT_BUDGET_EXCEEDED"
                and (body.get("message") or "").strip()):
            ok("context budget: CHAT_CONTEXT_BUDGET_EXCEEDED with a diagnostic message")
        else:
            bad(f"context budget mismatch: HTTP {status} {body}")

        # 模型调用预算：AGENT 至少需要两次模型调用，上限 1 时第二次必须可诊断地失败
        token = f"BUDGET_{int(time.time())}"
        knowledge = self.setup_knowledge_document(server, token)
        payload_extra = {"maxResults": 5, "useHybridSearch": True, "useRerank": True,
                         "collectionKeys": [knowledge["collectionKey"]],
                         "documentIds": [knowledge["documentId"]]}
        message = ("Use the knowledge retrieval tool for the selected verification document. "
                   "Return the release verification code exactly and cite it. "
                   "The code begins with BUDGET_.")
        status = 0
        body = {}
        for _attempt in range(2):
            status, body, _ = self.ask(server, message, str(uuid.uuid4()),
                                       mode="AGENT", extra=payload_extra)
            if body.get("error") == "CHAT_BUDGET_EXHAUSTED":
                break
        if (status == 422 and body.get("error") == "CHAT_BUDGET_EXHAUSTED"
                and (body.get("message") or "").strip()
                and body.get("answer") in (None, "")):
            ok("model call budget: CHAT_BUDGET_EXHAUSTED with a diagnostic message (no empty answer)")
        else:
            bad(f"model call budget mismatch: HTTP {status} {body}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", action="store_true",
                        help="显式开启真实调用（等价 REAL_LLM_FAILURE_E2E=1）")
    parser.add_argument("--providers", default="openai",
                        help="逗号分隔：openai（LongCat）/ minimax")
    parser.add_argument("--skip-budget", action="store_true")
    parser.add_argument("--skip-failures", action="store_true")
    args = parser.parse_args()

    enabled = args.run or os.environ.get("REAL_LLM_FAILURE_E2E") == "1"
    if not enabled:
        print("SKIP: real LLM failure E2E is opt-in; set REAL_LLM_FAILURE_E2E=1 or pass --run.",
              flush=True)
        return 0

    env = load_dotenv(ROOT / ".env")
    providers = [item.strip().lower() for item in args.providers.split(",") if item.strip()]
    if not env:
        print("ERROR: .env is required for real LLM E2E (keys never enter the repository).",
              file=sys.stderr)
        return 2

    if "openai" in providers:
        required = ["SPRING_AI_OPENAI_API_KEY", "SPRING_AI_OPENAI_BASE_URL",
                    "SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL",
                    "RAG_EMBEDDING_API_KEY", "RAG_EMBEDDING_BASE_URL",
                    "RAG_EMBEDDING_MODEL", "POSTGRES_USER", "POSTGRES_PASSWORD"]
        missing = [name for name in required if not env.get(name)]
        if missing:
            print(f"ERROR: missing .env entries: {', '.join(missing)}", file=sys.stderr)
            return 2

    harness = Harness(env, providers, skip_budget=args.skip_budget)
    try:
        harness.prepare_database()

        for provider in providers:
            if provider == "openai":
                upstream = env["SPRING_AI_OPENAI_BASE_URL"].rstrip("/")
                harness.proxy = FaultProxy(upstream)
                base = f"http://127.0.0.1:{harness.proxy.port}/openai"
                extra = {
                    "APP_MODELS_LEGACY_CAPABILITIES_OPENAI_TOOL_CALLING": "true",
                    "RAG_CIRCUIT_BREAKER_ENABLED": "false",
                    "RAG_TIMEOUT_READTIMEOUTMS": "20000",
                    "RAG_TIMEOUT_CHATASKMS": "60000",
                    "RAG_RETRY_MAXATTEMPTS": "2",
                    "RAG_RETRY_MAXBACKOFFMS": "10000",
                }
            elif provider == "minimax":
                base = env.get("SPRING_AI_MINIMAX_BASE_URL", "https://api.minimaxi.com")
                extra = {}
            else:
                print(f"ERROR: unsupported provider {provider}", file=sys.stderr)
                return 2

            print(f"\n=== provider={provider} ===", flush=True)
            server = harness.start_server(provider, base, extra)
            try:
                harness.real_success(server, provider, check_agent=(provider == "openai"))
                if provider == "openai" and not args.skip_failures:
                    harness.failure_modes(server)
            finally:
                harness.stop_server(server)

            if provider == "openai" and not args.skip_budget:
                budget_server = harness.start_server(
                    "openai", base,
                    {"APP_MODELS_LEGACY_CAPABILITIES_OPENAI_TOOL_CALLING": "true",
                     "RAG_CIRCUIT_BREAKER_ENABLED": "false",
                     "RAG_CHAT_EXECUTION_MAXMODELCALLS": "1",
                     "RAG_CHAT_CONTEXT_FALLBACKCONTEXTWINDOW": "7000",
                     "RAG_CHAT_CONTEXT_OUTPUTRESERVETOKENS": "1024",
                     "RAG_CHAT_CONTEXT_SAFETYMARGINTOKENS": "256"})
                try:
                    harness.budget_modes(budget_server)
                finally:
                    harness.stop_server(budget_server)

        print(f"\nRESULT: pass={PASS} fail={FAIL}", flush=True)
        print(f"evidence: {RUN_DIR}", flush=True)
        return 0 if FAIL == 0 else 1
    finally:
        harness.close()


if __name__ == "__main__":
    sys.exit(main())
