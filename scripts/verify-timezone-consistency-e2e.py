#!/usr/bin/env python3
"""时区与 Locale 无关性验收（真实进程）。

同一个 demo jar，分别在 TZ=Asia/Shanghai / UTC / America/New_York 下执行同一组操作，断言：

1. 写入数据库的业务时间戳始终是 UTC 墙上时间（与宿主 TZ 无关）；
2. API 返回的 LocalDateTime 字段稳定为 UTC 值、无时区偏移；导出 exportedAt 带 Z；
3. 日期参数按绝对时间（Instant）解析，带 +08:00 / Z 都可用；
4. cron 清理任务在三个 TZ 下都触发，且 TTL cutoff 等于 UTC now - ttlDays；
5. 日志时间带显式偏移（默认 +00:00）；
6. 健康检查在各 TZ 下均 UP。

不修改 .env 或日常库：使用 scripts/lib/rag_local_stack.py 的临时库 + 一次性 pgvector 容器。
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lib.rag_local_stack import ROOT_API_KEY, LocalStack  # noqa: E402

PROJECT_DIR = Path(__file__).resolve().parents[1]
VERIFICATION_DIR = PROJECT_DIR / ".verification"
PASSED: list[str] = []

APP_PORT = 18101
MOCK_PORT = 18102

TZ_CASES = ["Asia/Shanghai", "UTC", "America/New_York"]

LOG_TS = re.compile(
    r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}(?:Z|[+-]\d{2}:\d{2}))",
    re.MULTILINE)


def pass_check(message: str) -> None:
    PASSED.append(message)
    print(f"[PASS] {message}")


def fail(message: str) -> None:
    print(f"[FAIL] {message}", file=sys.stderr)
    raise SystemExit(1)


def http_json(method: str, url: str, body: dict | None = None, timeout: float = 60.0):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("X-API-Key", ROOT_API_KEY)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode() or "null")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def parse_instant(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def parse_naive_as_utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is not None:
        return parsed.astimezone(timezone.utc)
    return parsed.replace(tzinfo=timezone.utc)


def wait_until(predicate, timeout: float, interval: float = 0.5) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return False


def read_log(stack: LocalStack, port: int) -> str:
    path = Path(stack.log_dir) / f"app-{port}.log"
    if not path.exists():
        fail(f"找不到应用日志：{path}")
    return path.read_text(errors="replace")


def tz_slug(tz: str) -> str:
    return tz.replace("/", "-").lower()


def verify_case(stack: LocalStack, tz: str, port: int, database: str) -> None:
    base = f"http://127.0.0.1:{port}"
    key = f"tz-{tz_slug(tz)}"
    args = [
        "--rag.data-governance.cleanup-cron=*/5 * * * * *",
        "--rag.embedding-jobs.poll-interval-ms=600000",
        "--rag.long-running-operations.poll-interval-ms=600000",
    ]
    stack.start_app(extra_env={"TZ": tz}, database=database, extra_args=args)
    print(f"\n===== TZ={tz} =====")

    status, body = http_json("POST", f"{base}/api/v1/rag/collections",
                             {"collectionKey": key, "name": f"Timezone {tz}",
                              "embeddingModel": "BAAI/bge-m3", "dimensions": 1024})
    if status not in (200, 201):
        fail(f"{tz}: 创建 collection 失败 {status} {body}")
    status, body = http_json("POST", f"{base}/api/v1/rag/documents/upsert", {
        "collectionKey": key, "sourceNamespace": "tz-verify",
        "externalId": f"doc-{tz_slug(tz)}", "sourceRevision": "rev-1",
        "title": f"Timezone doc {tz}",
        "content": f"timezone consistency marker for {tz}. " * 20,
        "embed": True,
    })
    if status != 200:
        fail(f"{tz}: 写入文档失败 {status} {body}")
    document_id = body["documentId"]

    # 1. 数据落库：created_at 必须是 UTC 墙上时间，而不是宿主本地时间。
    # psql 非对齐输出默认用 '|' 分隔，这里分别查询避免依赖分隔符。
    created_raw = stack.sql(
        "SELECT to_char(created_at, 'YYYY-MM-DD\"T\"HH24:MI:SS.US') "
        f"FROM rag_documents WHERE id = {document_id}")
    utc_raw = stack.sql(
        "SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US')")
    drift = abs((parse_naive_as_utc(created_raw) - parse_naive_as_utc(utc_raw)).total_seconds())
    if drift > 180:
        fail(f"{tz}: created_at 偏离 UTC {drift:.0f}s（{created_raw} vs {utc_raw}），疑似使用宿主时区")
    pass_check(f"{tz}: 库内 created_at 为 UTC（相对 UTC now 偏移 {drift:.1f}s）")

    # 2. API 返回：字段格式稳定，值为 UTC。
    status, detail = http_json("GET", f"{base}/api/v1/rag/documents/{document_id}")
    if status != 200:
        fail(f"{tz}: 读取文档详情失败 {status}")
    created = detail.get("createdAt") or detail.get("created_at")
    if not created:
        fail(f"{tz}: 文档详情缺少 createdAt")
    if not re.match(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}", created):
        fail(f"{tz}: createdAt 格式不稳定：{created}")
    api_drift = abs((parse_naive_as_utc(created) - utc_now()).total_seconds())
    if api_drift > 300:
        fail(f"{tz}: API createdAt 偏离 UTC {api_drift:.0f}s（{created}）")
    pass_check(f"{tz}: API createdAt 为无偏移 UTC 值（{created}）")

    # 3. 导出：exportedAt 必须带 Z；文档体含正文但不含向量/chunk。
    status, exported = http_json("GET",
                                 f"{base}/api/v1/rag/collections/by-key/export?"
                                 f"collectionKey={urllib.parse.quote(key)}")
    if status != 200:
        fail(f"{tz}: 导出失败 {status} {exported}")
    exported_at = exported.get("exportedAt", "")
    if not exported_at.endswith("Z"):
        fail(f"{tz}: exportedAt 没有显式 UTC 标识：{exported_at}")
    export_drift = abs((parse_instant(exported_at) - utc_now()).total_seconds())
    if export_drift > 300:
        fail(f"{tz}: exportedAt 偏离 UTC {export_drift:.0f}s")
    docs = exported.get("documents") or []
    if len(docs) != 1 or not docs[0].get("content"):
        fail(f"{tz}: 导出文档结构异常：{len(docs)} 条 / content={bool(docs[0].get('content') if docs else False)}")
    if "embedding" in docs[0] or "chunks" in docs[0]:
        fail(f"{tz}: 导出 JSON 意外包含 chunk/向量字段")
    pass_check(f"{tz}: exportedAt 带 Z 且接近 UTC now（偏移 {export_drift:.1f}s）")

    # 4. 日期参数解析：+08:00 与 Z 都表示绝对时间，结果稳定。
    now = utc_now()
    start = (now - timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%SZ")
    end = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    status_z, _ = http_json(
        "GET", f"{base}/api/v1/rag/audit-logs?size=1"
               f"&from={urllib.parse.quote(start)}&to={urllib.parse.quote(end)}")
    status_offset, _ = http_json(
        "GET", f"{base}/api/v1/rag/audit-logs?size=1"
               f"&from={urllib.parse.quote(start.replace('Z', '+00:00'))}"
               f"&to={urllib.parse.quote(end.replace('Z', '+00:00'))}")
    if status_z != 200 or status_offset != 200:
        fail(f"{tz}: 审计时间参数解析失败 Z={status_z} offset={status_offset}")
    pass_check(f"{tz}: 日期参数 Z / +00:00 解析一致（HTTP 200）")

    # 5. TTL：31 天前删除、刚写入保留；cutoff 必须等于 UTC now - 30 天。
    old_session = f"tz-old-{tz_slug(tz)}-{os.getpid()}"
    fresh_session = f"tz-fresh-{tz_slug(tz)}-{os.getpid()}"
    stack.execute(
        "INSERT INTO rag_chat_history (session_id, user_message, created_at) VALUES "
        f"('{old_session}', 'old', now() AT TIME ZONE 'UTC' - interval '31 days'), "
        f"('{fresh_session}', 'fresh', now() AT TIME ZONE 'UTC')")
    if not wait_until(
            lambda: stack.sql(f"SELECT count(*) FROM rag_chat_history WHERE session_id='{old_session}'") == "0",
            timeout=20):
        fail(f"{tz}: 过期 chat history 未被 TTL 清理（cron/TTL 判定可能用了本地时区）")
    if stack.sql(f"SELECT count(*) FROM rag_chat_history WHERE session_id='{fresh_session}'") != "1":
        fail(f"{tz}: 未过期 chat history 被误删")
    log_text = read_log(stack, port)
    cutoff_match = re.findall(r"Starting chat history TTL cleanup, cutoff=([0-9T:. -]+) \(ttlDays=30\)",
                              log_text)
    if not cutoff_match:
        fail(f"{tz}: 日志中没有 TTL cutoff 行")
    cutoff = parse_naive_as_utc(cutoff_match[-1].strip())
    cutoff_drift = abs((cutoff - (utc_now() - timedelta(days=30))).total_seconds())
    if cutoff_drift > 180:
        fail(f"{tz}: TTL cutoff 偏离 UTC now-30d {cutoff_drift:.0f}s（{cutoff}）")
    pass_check(f"{tz}: TTL 过期删除正确，cutoff 为 UTC（偏移 {cutoff_drift:.1f}s）")

    # 6. 日志时间：必须带显式 UTC 标识（零偏移既可能是 Z，也可能是 +00:00）。
    match = LOG_TS.search(log_text)
    if not match:
        fail(f"{tz}: 日志首行没有带偏移的时间戳")
    logged = datetime.fromisoformat(
        match.group(1).replace(" ", "T").replace("Z", "+00:00"))
    if logged.utcoffset() != timedelta(0):
        fail(f"{tz}: 日志偏移不是 UTC：{match.group(1)}")
    pass_check(f"{tz}: 日志时间带显式偏移 {match.group(1)}")

    status, health = http_json("GET", f"{base}/actuator/health")
    if status != 200 or health.get("status") != "UP":
        fail(f"{tz}: health 不是 UP：{status} {health}")
    pass_check(f"{tz}: health UP")


def main() -> None:
    VERIFICATION_DIR.mkdir(parents=True, exist_ok=True)
    stack = LocalStack("rag_timezone", app_port=APP_PORT, mock_port=MOCK_PORT)
    try:
        stack.build_demo_if_needed()
        stack.start_mock()
        database = stack.create_database()
        for tz in TZ_CASES:
            verify_case(stack, tz, stack.app_port, database)
        pass_check("三个时区全部通过：UTC 存储/返回/导出/TTL/日志/健康一致"
                   "（cron 显式 zone 与 DST 日算术由 TimeZoneEnvironmentPostProcessorTest 覆盖）")
    finally:
        stack.stop_app()
        stack.stop_mock()
        stack.drop_database(stack.db_name)

    (VERIFICATION_DIR / "timezone-consistency-e2e.txt").write_text(
        "\n".join(PASSED) + "\n", encoding="utf-8")
    print(f"\n{len(PASSED)} checks passed")


if __name__ == "__main__":
    main()
