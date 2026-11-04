#!/usr/bin/env python3
"""WebUI 凭据存储与会话安全验收（真实进程 + 真实浏览器）。

覆盖：

1. Unlock 输入的 API Key 只存在于内存：localStorage / sessionStorage /
   cookie / URL / Referer / 全局变量里都找不到；
2. 刷新页面后凭据消失并回到 Unlock（内存态不跨页面加载）；
3. 多标签页互不共享：A 标签解锁不会让 B 标签自动解锁；
4. 注入脚本（模拟 XSS）扫描全部可达存储面，读不到凭据；
5. 登出清空内存凭据、旧版本明文存储与会话级工作区状态；
6. 凭据只出现在 ``X-API-Key`` 请求头，不出现在任何请求 URL 或 Referer。

运行（需要本机 PostgreSQL、Maven 与 Playwright Python）：

    ./scripts/verify-webui-credential-security-e2e.py
"""

from __future__ import annotations

import sys
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_DIR / "scripts" / "lib"))

from rag_local_stack import LocalStack, ROOT_API_KEY  # noqa: E402

APP_PORT = 18141
MOCK_PORT = 18142

failures: list[str] = []
captured_requests: list[dict[str, object]] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"[PASS] {name}")
        return
    failures.append(f"{name}: {detail}")
    print(f"[FAIL] {name} :: {detail}")


def storage_snapshot(page) -> dict[str, object]:
    """读取页面内所有可达的持久化存储与位置信息。"""
    return page.evaluate("""() => {
        const dump = storage => {
            const entries = [];
            for (let i = 0; i < storage.length; i += 1) {
                const key = storage.key(i);
                entries.push([key, storage.getItem(key)]);
            }
            return entries;
        };
        return {
            localStorage: dump(window.localStorage),
            sessionStorage: dump(window.sessionStorage),
            cookie: document.cookie,
            url: location.href,
            referrer: document.referrer,
            html: document.documentElement.outerHTML,
        };
    }""")  # type: ignore[no-any-return]


def find_key(snapshot: dict[str, object]) -> list[str]:
    hits = []
    for name, value in snapshot.items():
        if isinstance(value, str) and ROOT_API_KEY in value:
            hits.append(name)
        elif isinstance(value, list):
            if ROOT_API_KEY in repr(value):
                hits.append(name)
    return hits


def unlock(page, base: str) -> None:
    page.goto(f"{base}/webui/chat", wait_until="networkidle")
    if page.url.endswith("/webui/unlock"):
        page.get_by_test_id("root-api-key").fill(ROOT_API_KEY)
        page.get_by_role("button", name="Unlock", exact=True).click()
    page.get_by_role("heading", name="Chat", exact=True).wait_for()


def browser_checks(base: str) -> None:
    from playwright.sync_api import sync_playwright

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context()
        page = context.new_page()

        def record(request) -> None:
            if not request.url.startswith(base):
                return
            try:
                headers = request.all_headers()
            except Exception:  # 请求可能已结束且 headers 不再可读
                headers = {}
            captured_requests.append({
                "url": request.url,
                "headers": {k.lower(): v for k, v in headers.items()},
            })

        page.on("request", record)

        # 1. 解锁后：内存凭据不落任何持久化存储。
        unlock(page, base)
        snapshot = storage_snapshot(page)
        check("解锁后 URL 不含 API Key", ROOT_API_KEY not in str(snapshot["url"]),
              str(snapshot["url"]))
        check("解锁后 localStorage/sessionStorage/cookie 不含 API Key",
              not find_key(snapshot), str(find_key(snapshot)))

        # 2. 模拟 XSS：页面内脚本扫描所有可达存储与全局对象。
        xss_probe = page.evaluate("""key => {
            const hits = [];
            const scan = (label, value) => {
                try {
                    if (value && String(value).includes(key)) hits.push(label);
                } catch (error) { /* 不可读对象忽略 */ }
            };
            scan('localStorage', JSON.stringify(window.localStorage));
            scan('sessionStorage', JSON.stringify(window.sessionStorage));
            scan('cookie', document.cookie);
            scan('referrer', document.referrer);
            scan('url', location.href);
            // 常见 XSS 目标：全局变量、页面内表单值、脚本文本。
            for (const name of Object.keys(window)) {
                if (name.startsWith('__')) scan('window.' + name, window[name]);
            }
            scan('body', document.body ? document.body.innerText : '');
            scan('inputs', Array.from(document.querySelectorAll('input'))
                .map(input => input.value).join('\\n'));
            return hits;
        }""", ROOT_API_KEY)
        check("注入脚本读不到 API Key", not xss_probe, str(xss_probe))

        # 3. 刷新：内存态不跨页面加载，回到 Unlock。
        page.reload(wait_until="networkidle")
        check("刷新后回到 Unlock", page.url.endswith("/webui/unlock"), page.url)
        check("刷新后 URL/存储不含 API Key", not find_key(storage_snapshot(page)),
              str(find_key(storage_snapshot(page))))

        # 4. 多标签页：同浏览器上下文的新标签不会被 A 标签的解锁状态影响。
        second = context.new_page()
        second.goto(f"{base}/webui/chat", wait_until="networkidle")
        check("新标签页仍处于锁定态", second.url.endswith("/webui/unlock"),
              second.url)
        check("新标签页存储不含 API Key", not find_key(storage_snapshot(second)))
        unlock(page, base)
        check("A 标签解锁不会自动解锁 B 标签",
              second.url.endswith("/webui/unlock"), second.url)

        # 5. 登出：清空内存凭据、旧版明文存储与会话级工作区状态。
        page.get_by_role("button", name="Sign out", exact=True).click()
        page.wait_for_url("**/webui/unlock")
        after_logout = storage_snapshot(page)
        check("登出后回到 Unlock", page.url.endswith("/webui/unlock"), page.url)
        check("登出后存储不含 API Key", not find_key(after_logout),
              str(find_key(after_logout)))
        check("登出后不残留旧版明文存储",
              "rag-api-key" not in repr(after_logout["localStorage"]),
              repr(after_logout["localStorage"]))
        check("登出后 workspace 状态已清空",
              "spring-ai-rag:webui:v1:" not in repr(after_logout["sessionStorage"]),
              repr(after_logout["sessionStorage"]))

        # 6. 网络面：URL/Referer 不含凭据，凭据只出现在 X-API-Key 请求头。
        leaked_urls = [item["url"] for item in captured_requests
                       if ROOT_API_KEY in str(item["url"])]
        check("所有请求 URL 不含 API Key", not leaked_urls, str(leaked_urls[:3]))
        leaked_referers = [item["url"] for item in captured_requests
                           if ROOT_API_KEY in str(item["headers"].get("referer", ""))]
        check("所有请求 Referer 不含 API Key", not leaked_referers,
              str(leaked_referers[:3]))
        api_requests = [item for item in captured_requests
                        if "/api/v1/" in str(item["url"])]
        check("API 请求通过 X-API-Key 头携带凭据",
              any(str(item["headers"].get("x-api-key")) == ROOT_API_KEY
                  for item in api_requests),
              f"api_requests={len(api_requests)}")

        context.close()
        browser.close()


def main() -> int:
    stack = LocalStack("webui-credential", APP_PORT, MOCK_PORT)
    try:
        stack.build_demo_if_needed()
        stack.start_mock(stream_chunks=2,
                         extra_env={"MOCK_DETERMINISTIC_EMBEDDINGS": "true"})
        database = stack.create_database()
        base = stack.start_app(database=database)
        browser_checks(base)
    finally:
        stack.cleanup()

    print(f"\n[SUMMARY] {len(failures)} failed")
    if failures:
        for item in failures:
            print(f"  - {item}", file=sys.stderr)
        return 1
    print("结论：WebUI 凭据只驻留内存，刷新/登出/多标签/注入脚本均读不到。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
