#!/usr/bin/env python3
"""端点行为矩阵运行时门禁。

读取 testdata/api-contract/endpoint-behavior-matrix.json，对真实启动的
PostgreSQL + demo 应用执行：

- 未认证：每个端点不带 API Key → 401（过滤器先于路由，未启用的 /v1 也一样）
- 非法参数：必填 JSON body 发畸形 JSON、必填 query 省略 → 400
- 不存在：路径参数使用格式合法但不存在的值 → 404
- 成功：矩阵声明的成功探针 → 2xx，并校验响应必需字段 / Content-Type

SSE 与 multipart 的成功探针也在同一进程内验证。
"""

from __future__ import annotations

import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from lib.rag_local_stack import (  # noqa: E402
    ROOT_API_KEY,
    LocalStack,
    json_request,
    multipart_request,
)

ROOT = Path(__file__).resolve().parent.parent
MATRIX_PATH = ROOT / "testdata/api-contract/endpoint-behavior-matrix.json"
OPENAPI_PATH = ROOT / "testdata/api-contract/openapi-baseline.json"
APP_PORT = 18087
MOCK_PORT = 18088

APP_ARGS = [
    "--rag.openai-compatibility.enabled=true",
    "--rag.openai-compatibility.models.rag-default.mode=KNOWLEDGE",
    "--rag.embedding-jobs.poll-interval-ms=10000",
    "--rag.timeout.chat-ask-ms=20000",
    "--rag.timeout.chat-stream-ms=20000",
    # 默认关闭的文档生命周期能力在矩阵验收中全部打开，避免用 503 绕过行为断言。
    "--rag.document-lifecycle.sync-runs-enabled=true",
    "--rag.document-lifecycle.version-restore-enabled=true",
    "--rag.document-lifecycle.relocation-enabled=true",
    "--rag.document-lifecycle.derivation-repair-enabled=true",
]


class ProbeFailure(AssertionError):
    pass


def resolve_ref(spec: dict, schema: dict, depth: int = 0) -> dict:
    if not schema or depth > 6:
        return {}
    if schema.get("$ref"):
        name = schema["$ref"].split("/")[-1]
        return resolve_ref(spec, spec.get("components", {}).get("schemas", {}).get(name, {}), depth + 1)
    if schema.get("oneOf"):
        return resolve_ref(spec, schema["oneOf"][0], depth + 1)
    if schema.get("allOf"):
        merged = {"type": "object", "required": [], "properties": {}}
        for part in schema["allOf"]:
            resolved = resolve_ref(spec, part, depth + 1)
            merged["required"].extend(resolved.get("required", []))
            merged["properties"].update(resolved.get("properties", {}))
        merged["required"] = list(dict.fromkeys(merged["required"]))
        return merged
    return schema


def synthesize(spec: dict, schema: dict, depth: int = 0):
    """按 OpenAPI schema 生成最小合法请求体。"""
    schema = resolve_ref(spec, schema, depth)
    if depth > 5 or not schema:
        return {}
    schema_type = schema.get("type")
    if schema_type == "object" or "properties" in schema:
        result = {}
        properties = schema.get("properties", {})
        for name in schema.get("required", []):
            if name in properties:
                result[name] = synthesize(spec, properties[name], depth + 1)
        return result
    if schema_type == "array":
        return []
    if schema_type == "integer":
        return 1
    if schema_type == "number":
        return 1.0
    if schema_type == "boolean":
        return True
    if schema.get("enum"):
        return schema["enum"][0]
    return "probe"


def bogus_value(name: str, schema: dict) -> str:
    if schema.get("format") == "uuid" or name.lower().endswith("id") and schema.get("type") == "string":
        return str(uuid.UUID(int=0))
    if schema.get("type") == "integer":
        return "999999"
    if name.lower().startswith("session"):
        return "no-such-session"
    if name.lower() in ("filename", "file"):
        return "no-such-file.txt"
    return "no-such-value"


def fill_path(endpoint: str, entry: dict) -> str:
    filled = endpoint
    for parameter in entry.get("pathParams") or []:
        value = bogus_value(parameter["name"], parameter.get("schema") or {})
        filled = re.sub(r"\{" + re.escape(parameter["name"]) + r"\}", value, filled)
    # 文档独有端点（例如 /v1/models/{id}）没有 OpenAPI 参数定义，统一兜底，
    # 避免把字面量花括号发到 Tomcat 得到 400。
    return re.sub(r"\{[^}]+\}", "no-such-value", filled)


def send_raw(method: str, url: str, body: bytes | None, headers: dict[str, str],
             timeout: float = 15):
    request = urllib.request.Request(url, data=body, method=method)
    for key, value in headers.items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read(4096)
            content_type = response.headers.get("Content-Type", "")
            return response.status, content_type, raw
    except urllib.error.HTTPError as error:
        return error.code, error.headers.get("Content-Type", ""), error.read(2048)


def json_status(method: str, url: str, payload, headers: dict[str, str] | None = None,
                timeout: float = 15):
    merged = {"X-API-Key": ROOT_API_KEY, "Content-Type": "application/json"}
    merged.update(headers or {})
    body = json.dumps(payload).encode() if payload is not None else None
    status, content_type, raw = send_raw(method, url, body, merged, timeout)
    try:
        parsed = json.loads(raw) if raw else None
    except json.JSONDecodeError:
        parsed = None
    return status, content_type, parsed, raw


def check_shape(probe_name: str, status: int, content_type: str, payload,
                expect: dict) -> None:
    if status != expect["status"]:
        raise ProbeFailure(
            f"{probe_name}: HTTP {status} != {expect['status']}，body={str(payload)[:200]}")
    expected_type = expect.get("contentType")
    if expected_type and expected_type not in (content_type or ""):
        raise ProbeFailure(
            f"{probe_name}: Content-Type {content_type!r} 不含 {expected_type!r}")
    for key in expect.get("requiredKeys", []):
        if isinstance(payload, dict) and key not in payload:
            raise ProbeFailure(
                f"{probe_name}: 响应缺少必需字段 {key}，实际字段 {list(payload)[:10]}")
        if isinstance(payload, list) and key not in payload:
            raise ProbeFailure(f"{probe_name}: 数组响应中缺少 {key}")


class Runner:
    def __init__(self, stack: LocalStack, base_url: str):
        self.stack = stack
        self.base_url = base_url
        self.spec = json.loads(OPENAPI_PATH.read_text())
        self.matrix = json.loads(MATRIX_PATH.read_text())
        self.variables: dict[str, str] = {}
        self.failures: list[str] = []

    def fail(self, message: str) -> None:
        self.failures.append(message)

    def url(self, endpoint: str, query: str | None = None) -> str:
        expanded = endpoint
        for name, value in self.variables.items():
            expanded = expanded.replace("${" + name + "}", str(value))
        if query:
            expanded += ("&" if "?" in expanded else "?") + query
        return self.base_url + expanded

    def required_query(self, key: str) -> list[str]:
        canon = key.split(" ", 1)[1]
        for path, item in self.spec.get("paths", {}).items():
            if re.sub(r"\{[^}]*\}", "{}", path) != canon:
                continue
            for method in ("get", "post", "put", "patch", "delete"):
                operation = item.get(method)
                if not operation:
                    continue
                return [p["name"] for p in operation.get("parameters", [])
                        if p.get("in") == "query" and p.get("required")]
        return []

    def request_body_schema(self, key: str) -> dict | None:
        canon = key.split(" ", 1)[1]
        for path, item in self.spec.get("paths", {}).items():
            if re.sub(r"\{[^}]*\}", "{}", path) != canon:
                continue
            for method in ("post", "put", "patch", "delete"):
                operation = item.get(method)
                if operation and operation.get("requestBody"):
                    content = operation["requestBody"].get("content", {})
                    return content.get("application/json", {}).get("schema")
        return None

    def probe_unauth(self, entry: dict) -> None:
        key = f"{entry['method']} {entry['path']}"
        target = fill_path(entry["path"], entry)
        status, content_type, raw = send_raw(
            entry["method"], self.url(target), None,
            {"Content-Type": "application/json"}, timeout=10)
        if status != 401:
            self.fail(f"{key} 未认证期望 401，实际 {status} body={raw[:120]!r}")

    def probe_invalid(self, entry: dict) -> None:
        key = f"{entry['method']} {entry['path']}"
        probe = entry.get("probes", {}).get("invalid")
        if not probe:
            return
        target = fill_path(entry["path"], entry)
        if probe["kind"] == "json":
            status, _, raw = send_raw(
                entry["method"], self.url(target), b"{not-json",
                {"X-API-Key": ROOT_API_KEY, "Content-Type": "application/json"},
                timeout=10)
        else:
            omitted = probe.get("omitted", [])
            available = {k: v for k, v in
                         (pair.split("=", 1) for pair in
                          urllib.parse.urlparse(target).query.split("&") if pair)
                         if k not in omitted}
            base = target.split("?", 1)[0]
            query = urllib.parse.urlencode(available)
            status, _, raw = send_raw(
                entry["method"], self.url(base, query),
                None, {"X-API-Key": ROOT_API_KEY}, timeout=10)
        if status != 400:
            self.fail(f"{key} 非法参数期望 400，实际 {status} body={raw[:120]!r}")

    def probe_missing(self, entry: dict) -> None:
        key = f"{entry['method']} {entry['path']}"
        probe = entry.get("probes", {}).get("missing")
        if not probe:
            return
        target = self.url(fill_path(entry["path"], entry))
        query = entry.get("missingQuery") or []
        if query:
            target = self.url(fill_path(entry["path"], entry),
                              urllib.parse.urlencode(
                                  {item["name"]: item["value"] for item in query}))
        headers = {"X-API-Key": ROOT_API_KEY, "Content-Type": "application/json"}
        for item in entry.get("missingHeaders") or []:
            headers[item["name"]] = item["value"]
        schema = self.request_body_schema(key)
        status, _, raw = send_raw(
            entry["method"], target,
            json.dumps(synthesize(self.spec, schema or {})).encode()
            if schema else None,
            headers, timeout=10)
        expected = probe["status"]
        if status != expected:
            self.fail(f"{key} 不存在资源期望 {expected}，实际 {status} body={raw[:120]!r}")

    def run_matrix_probes(self) -> None:
        for entry in self.matrix["entries"]:
            self.probe_unauth(entry)
            self.probe_invalid(entry)
            self.probe_missing(entry)
        print(f"  静态行为探针完成：{len(self.matrix['entries'])} 个端点 × 401/400/404")

    def expand(self, value):
        if isinstance(value, str):
            match = re.fullmatch(r"\$\{([A-Za-z0-9_]+)\}", value)
            if match and match.group(1) in self.variables:
                raw = self.variables[match.group(1)]
                return int(raw) if str(raw).isdigit() else raw
            for name, replacement in self.variables.items():
                value = value.replace("${" + name + "}", str(replacement))
            return value
        if isinstance(value, dict):
            return {key: self.expand(item) for key, item in value.items()}
        if isinstance(value, list):
            return [self.expand(item) for item in value]
        return value

    def run_success_probe(self, name: str, probe: dict, entry: dict) -> None:
        timeout = 25
        # 删除探针需等待异步嵌入收敛，避免在 INDEXING 时删除得到 409。
        if probe.get("waitUntil"):
            wait = probe["waitUntil"]
            deadline = time.time() + wait.get("timeoutSeconds", 10)
            while time.time() < deadline:
                status, _, payload, _ = json_status(
                    "GET", self.url(wait["path"]), None, timeout=10)
                if status == 200 and isinstance(payload, dict) \
                        and payload.get(wait["field"]) not in wait.get("notIn", []):
                    break
                time.sleep(0.5)
        headers = {}
        payload = self.expand(probe.get("body"))
        query = self.expand(probe.get("query"))
        target = self.url(
            probe["path"], urllib.parse.urlencode(query) if query else None)
        if "multipart" in probe:
            spec = probe["multipart"]
            status, raw = multipart_request(
                target, spec.get("field", "files"),
                spec["filename"], spec["content"].encode(),
                spec.get("fields", {}), extra_headers=headers, timeout=timeout)
            content_type = "application/json"
            parsed = json.loads(raw) if raw else None
        else:
            status, content_type, parsed, raw = json_status(
                probe["method"], target, payload,
                headers=headers, timeout=timeout)
            raw = raw
        check_shape(name, status, content_type, parsed, probe["expect"])
        captures = probe.get("capture", {})
        if captures and isinstance(parsed, dict):
            for field, variable in captures.items():
                if field in parsed:
                    self.variables[variable] = parsed[field]
        print(f"  成功探针 {name}: {status}")

    def run_success_probes(self) -> None:
        entries_by_probe = {}
        for entry in self.matrix["entries"]:
            name = entry.get("successProbe")
            if name:
                entries_by_probe.setdefault(name, []).append(entry)
        for name, probe in self.matrix["successProbes"].items():
            if name not in entries_by_probe:
                continue
            self.run_success_probe(name, probe, entries_by_probe[name][0])
        print(f"  成功探针完成：{len(entries_by_probe)} 个端点")


def main() -> int:
    stack = LocalStack("endpoint_behavior", APP_PORT, MOCK_PORT)
    try:
        stack.build_demo_if_needed()
        stack.create_database()
        stack.start_mock(delay_ms=0)
        base_url = stack.start_app(extra_args=APP_ARGS)
        print(f"临时库 {stack.db_name}，应用 {base_url}")
        runner = Runner(stack, base_url)
        runner.run_matrix_probes()
        runner.run_success_probes()
        if runner.failures:
            print("\n端点行为矩阵运行时失败：")
            for failure in runner.failures:
                print(f"  - {failure}")
            return 1
        print("\n端点行为矩阵运行时全部通过。")
        return 0
    finally:
        stack.cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
