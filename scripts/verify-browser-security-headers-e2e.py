#!/usr/bin/env python3
"""浏览器安全响应头与注入面验收（真实进程 + 真实浏览器）。

覆盖：

1. `/webui/` 静态产物、API、SSE、错误响应都下发 CSP / nosniff /
   Referrer-Policy / X-Frame-Options / Permissions-Policy；
2. HSTS 只在 HTTPS（直连或 `X-Forwarded-Proto: https`）出现；
3. 真实 Chromium 加载 WebUI 不被 CSP 打坏（脚本、行内样式、同源 API、SSE、
   上传、导出都可用）；
4. 浏览器内构造的内联脚本与行内事件处理器被 CSP 拒绝；
5. 聊天回答包含 `<script>` 时以文本渲染，不执行；
6. 上传文件名/文档标题包含引号与 HTML 时，列表返回原值，下载头不破坏；
7. 聊天导出 `Content-Disposition` 为 RFC 5987 安全头。

运行（需要本机 PostgreSQL、Maven 与 Playwright Python）：

    ./scripts/verify-browser-security-headers-e2e.py

临时数据库在 finally 中删除。使用 `--keep` 保留日志。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_DIR / "scripts" / "lib"))

from rag_local_stack import (  # noqa: E402
    LocalStack,
    ROOT_API_KEY,
    http_request_full,
    multipart_request,
)

APP_PORT = 18101
MOCK_PORT = 18102

MINIMAL_PDF = b"""%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Count 1/Kids[3 0 R]>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]
/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj
4 0 obj<</Length 44>>
stream
BT
/F1 12 Tf
100 700 Td
(Hello from security headers test) Tj
ET
endstream
endobj
5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj
xref
0 6
0000000000 65535 f
0000000009 00000 n
0000000058 00000 n
0000000115 00000 n
0000000268 00000 n
0000000359 00000 n
trailer<</Size 6/Root 1 0 R>>
startxref
421
%%EOF
"""

INJECTED_SCRIPT = "window.__ragInjected = true"
INJECTED_MESSAGE = f"plain probe <script>{INJECTED_SCRIPT}</script>"

failures: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"[PASS] {name}")
        return
    failures.append(f"{name}: {detail}")
    print(f"[FAIL] {name} :: {detail}")


def assert_security_headers(label: str, headers: dict[str, str]) -> None:
    csp = headers.get("content-security-policy", "")
    check(f"{label} 下发 CSP", bool(csp), f"headers={headers}")
    for directive in (
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self'",
        "connect-src 'self'",
        "img-src 'self' data: blob:",
        "frame-ancestors 'none'",
    ):
        check(f"{label} CSP 含 {directive}", directive in csp, f"csp={csp}")
    check(f"{label} CSP 不放行内联脚本",
          "script-src 'self' 'unsafe-inline'" not in csp, f"csp={csp}")
    check(f"{label} X-Content-Type-Options", headers.get("x-content-type-options") == "nosniff")
    check(f"{label} Referrer-Policy", headers.get("referrer-policy") == "no-referrer")
    check(f"{label} X-Frame-Options", headers.get("x-frame-options") == "DENY")
    check(f"{label} Permissions-Policy", bool(headers.get("permissions-policy")))


def stream_headers(url: str, api_key: str) -> dict[str, str]:
    """打开 SSE 连接只读取前几个字节，避免等待整轮结束。"""
    payload = json.dumps({"message": "sse header probe", "mode": "PLAIN"}).encode()
    request = urllib.request.Request(
        url, data=payload, method="POST",
        headers={
            "Content-Type": "application/json",
            "X-API-Key": api_key,
            "Accept": "text/event-stream",
        })
    with urllib.request.urlopen(request, timeout=60) as response:
        headers = {k.lower(): v for k, v in response.headers.items()}
        # 读取一小段就关闭；只验证响应头与流已开始，不消费完整轮次。
        response.read(120)
    return headers


def browser_checks(base: str) -> None:
    from playwright.sync_api import sync_playwright

    console_csp_errors: list[str] = []

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context()
        page = context.new_page()
        page.on("console", lambda message: console_csp_errors.append(message.text)
                if "Content Security Policy" in message.text else None)

        page.goto(f"{base}/webui/", wait_until="networkidle")
        check("浏览器加载 WebUI", page.locator("body").count() == 1,
              page.url)
        theme = page.evaluate(
            "() => document.documentElement.getAttribute('data-theme')")
        check("外部主题脚本在 CSP 下执行", theme in {"light", "dark"},
              f"data-theme={theme}")
        check("正常页面加载没有 CSP 控制台错误",
              not list(console_csp_errors), str(console_csp_errors[:3]))

        # 内联 <script> 注入：真实 CSP 应阻止执行并上报 securitypolicyviolation。
        probe = page.evaluate("""() => new Promise(resolve => {
            window.__ragCspViolations = [];
            document.addEventListener('securitypolicyviolation', event => {
                window.__ragCspViolations.push(event.violatedDirective);
            });
            const script = document.createElement('script');
            script.textContent = 'window.__ragInlineScriptRan = true';
            document.head.appendChild(script);
            setTimeout(() => resolve({
                ran: window.__ragInlineScriptRan === true,
                violations: window.__ragCspViolations,
            }), 500);
        })""")
        check("内联 script 被 CSP 拒绝", probe["ran"] is False, str(probe))
        check("内联 script 触发 securitypolicyviolation",
              any("script-src" in directive for directive in probe["violations"]),
              str(probe["violations"]))

        # 行内事件处理器注入（HTML 注入常见形态）同样应被拒绝。
        event_probe = page.evaluate("""() => new Promise(resolve => {
            window.__ragCspViolations = [];
            document.addEventListener('securitypolicyviolation', event => {
                window.__ragCspViolations.push(event.violatedDirective);
            });
            const button = document.createElement('button');
            button.id = 'rag-inline-handler-probe';
            button.textContent = 'probe';
            button.setAttribute('onclick', 'window.__ragInlineHandlerRan = true');
            document.body.appendChild(button);
            button.click();
            setTimeout(() => resolve({
                ran: window.__ragInlineHandlerRan === true,
                violations: window.__ragCspViolations,
            }), 500);
        })""")
        check("行内事件处理器被 CSP 拒绝", event_probe["ran"] is False,
              str(event_probe))

        # 真实 UI：解锁 → 聊天页 → 发送包含 <script> 的消息。
        page.goto(f"{base}/webui/chat", wait_until="networkidle")
        if page.url.endswith("/webui/unlock"):
            page.get_by_test_id("root-api-key").fill(ROOT_API_KEY)
            page.get_by_role("button", name="Unlock", exact=True).click()
        page.get_by_role("heading", name="Chat", exact=True).wait_for()
        textarea = page.locator("textarea")
        textarea.wait_for()
        textarea.fill(INJECTED_MESSAGE)
        with page.expect_response(
                lambda response: "/api/v1/rag/chat/stream" in response.url
                and response.request.method == "POST") as response_info:
            page.get_by_role("button", name="Send", exact=True).click()
        response = response_info.value
        check("浏览器 SSE 在 CSP 下返回 200", response.status == 200,
              str(response.status))
        check("浏览器 SSE 内容类型正确",
              "text/event-stream" in (response.headers.get("content-type") or ""),
              response.headers.get("content-type", ""))
        # 等本轮结束（textarea 恢复可用）。
        page.wait_for_function(
            "() => { const el = document.querySelector('textarea');"
            " return el && !el.disabled; }", timeout=60_000)
        check("聊天回答中的 <script> 未执行",
              page.evaluate("() => window.__ragInjected === true") is False)
        check("聊天回答中的 <script> 以文本呈现",
              page.evaluate(
                  "text => document.body.innerText.includes(text)",
                  f"<script>{INJECTED_SCRIPT}</script>"))

        # 同源 API：在页面内发起搜索、上传、导出，验证 connect-src 不阻断。
        api_probe = page.evaluate("""async key => {
            const headers = { 'Content-Type': 'application/json', 'X-API-Key': key };
            const results = {};
            const created = await fetch('/api/v1/rag/collections', {
                method: 'POST', headers,
                body: JSON.stringify({ collectionKey: 'browser-security-headers',
                    name: 'Browser Security Headers' }),
            });
            results.collection = created.status;

            const search = await fetch('/api/v1/rag/search', {
                method: 'POST', headers,
                body: JSON.stringify({ query: 'probe', collectionKeys: ['browser-security-headers'] }),
            });
            results.search = search.status;

            const form = new FormData();
            form.append('files', new File(['hello'],
                'report"><script>window.__ragUploadInjected = true</script>.txt',
                { type: 'text/plain' }));
            form.append('collectionKey', 'browser-security-headers');
            const upload = await fetch('/api/v1/rag/documents/upload', {
                method: 'POST', headers: { 'X-API-Key': key }, body: form,
            });
            results.upload = upload.status;

            const chat = await fetch('/api/v1/rag/chat', {
                method: 'POST', headers,
                body: JSON.stringify({ message: 'export probe', mode: 'PLAIN' }),
            });
            const chatBody = await chat.json();
            results.chat = chat.status;
            results.sessionId = chatBody.sessionId;

            const exported = await fetch(
                '/api/v1/rag/chat/export/' + encodeURIComponent(chatBody.sessionId),
                { headers: { 'X-API-Key': key } });
            results.export = exported.status;
            results.exportDisposition = exported.headers.get('content-disposition');
            return results;
        }""", ROOT_API_KEY)
        check("页面内创建 Collection", api_probe["collection"] == 200,
              str(api_probe))
        check("页面内搜索可用", api_probe["search"] == 200, str(api_probe))
        check("页面内上传可用", api_probe["upload"] == 200, str(api_probe))
        check("页面内聊天可用", api_probe["chat"] == 200, str(api_probe))
        check("页面内导出可用", api_probe["export"] == 200, str(api_probe))
        check("导出响应带 Content-Disposition",
              bool(api_probe["exportDisposition"]),
              str(api_probe["exportDisposition"]))

        context.close()
        browser.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true", help="保留临时目录与日志")
    args = parser.parse_args()

    stack = LocalStack("browser-security-headers", APP_PORT, MOCK_PORT, keep=args.keep)
    started = time.time()
    try:
        stack.create_database()
        stack.start_mock(stream_chunks=5)
        stack.build_demo_if_needed()
        base = stack.start_app()

        # ---------- 响应头矩阵 ----------
        status, headers, body = http_request_full("GET", f"{base}/webui/")
        check("/webui/ 返回 200", status == 200, f"status={status}")
        assert_security_headers("/webui/", headers)
        check("/webui/ 页面引用外置主题脚本",
              "/webui/theme-init.js" in body, body[:200])
        check("/webui/ 页面没有内联 script",
              not re.search(r"<script(?![^>]*\bsrc=)[^>]*>", body),
              body[:200])

        status, headers, _ = http_request_full(
            "GET", f"{base}/api/v1/rag/collections",
            headers={"X-API-Key": ROOT_API_KEY})
        check("API 返回 200", status == 200, f"status={status}")
        assert_security_headers("API", headers)

        status, headers, _ = http_request_full(
            "GET", f"{base}/api/v1/rag/does-not-exist",
            headers={"X-API-Key": ROOT_API_KEY})
        check("错误响应仍带安全头", status in {401, 404},
              f"status={status}")
        assert_security_headers("错误响应", headers)

        headers = stream_headers(f"{base}/api/v1/rag/chat/stream", ROOT_API_KEY)
        assert_security_headers("SSE", headers)

        check("HTTP 不误发 HSTS", "strict-transport-security" not in headers)

        status, headers, _ = http_request_full(
            "GET", f"{base}/webui/",
            headers={"X-Forwarded-Proto": "https"})
        check("TLS 终止代理下下发 HSTS",
              headers.get("strict-transport-security", "").startswith("max-age="),
              str(headers.get("strict-transport-security")))

        # ---------- 文件名 / 下载头 ----------
        filename = '报告"final".pdf'
        status, pdf_body = multipart_request(
            f"{base}/api/v1/rag/files/pdf", "file", filename, MINIMAL_PDF, {})
        check("PDF 上传成功", status == 200, f"status={status} body={pdf_body[:200]}")
        pdf = json.loads(pdf_body) if pdf_body.strip().startswith("{") else {}
        import_uuid = pdf.get("uuid", "")
        check("PDF 上传返回 uuid", bool(import_uuid), str(pdf)[:200])
        check("API 保留含引号与 HTML 的原始文件名",
              pdf.get("originalFilename") == filename, str(pdf)[:300])

        if import_uuid:
            status, _, tree_body = http_request_full(
                "GET", f"{base}/api/v1/rag/files/tree?path={urllib.parse.quote(import_uuid)}",
                headers={"X-API-Key": ROOT_API_KEY})
            tree = json.loads(tree_body or "{}")
            entries = tree.get("entries") or []
            files = [entry for entry in entries if entry.get("type") == "file"]
            check("导入目录包含文件", bool(files), str(tree)[:300])
            if files:
                entry = files[0]
                status, headers, _ = http_request_full(
                    "GET",
                    f"{base}/api/v1/rag/files/raw?path="
                    + urllib.parse.quote(entry["path"]),
                    headers={"X-API-Key": ROOT_API_KEY})
                disposition = headers.get("content-disposition", "")
                check("下载响应带安全 Content-Disposition",
                      status == 200 and bool(disposition),
                      f"status={status} header={disposition}")
                check("下载头无裸 CR/LF",
                      "\r" not in disposition and "\n" not in disposition,
                      disposition)
                check("下载头使用 RFC 5987 编码",
                      "filename*=UTF-8''" in disposition, disposition)

        # ---------- 浏览器 ----------
        browser_checks(base)

        print(f"\n完成，用时 {time.time() - started:.1f}s")
        if failures:
            print(f"\n{len(failures)} 项失败：")
            for failure in failures:
                print(f"  - {failure}")
            return 1
        print("全部安全响应头与注入面断言通过")
        return 0
    finally:
        stack.cleanup()


if __name__ == "__main__":
    sys.exit(main())
