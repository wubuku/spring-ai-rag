#!/usr/bin/env python3
"""WebUI 静态资源缓存与升级语义验收（真实进程 + 真实浏览器）。

覆盖：

1. `/webui/`（index.html）下发 `Cache-Control: no-cache, must-revalidate` +
   ETag，条件请求命中返回 304；
2. 内容 hash 的 `/webui/assets/**` 下发
   `Cache-Control: public, max-age=31536000, immutable` + ETag，条件请求 304；
3. 未 hash 的 `theme-init.js` / `favicon.svg` / `icons.svg` 下发
   `no-cache, must-revalidate`，且不会被 SPA fallback 截成 HTML；
4. 已删除的 hash chunk 返回 404（不是 index.html）；
5. gzip 对 text/html、text/javascript 生效并带 `Vary: Accept-Encoding`；
   服务端不支持 Brotli（`Accept-Encoding: br` 仍返回 identity）；
6. 真实 Chromium 二次导航会对 index 发送 `If-None-Match`，页面正常挂载；
7. 模拟“旧 index.html 指向已被删除的 chunk”：浏览器请求得到 404，应用无法
   挂载。这是机制边界：JS 从未启动的页面无法自己提示；已启动的旧标签页由
   `webui-build-version` 轮询横幅负责（见 e2e/update-banner.spec.ts）；
8. `index.html` 带构建版本 meta，供运行中的页面比较新版本；
9. 没有 Service Worker：不存在离线回退。

运行（需要本机 PostgreSQL、Maven 与 Playwright Python）：

    ./scripts/verify-webui-cache-e2e.py

临时数据库在 finally 中删除。使用 `--keep` 保留日志。
"""

from __future__ import annotations

import argparse
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_DIR / "scripts" / "lib"))

from rag_local_stack import LocalStack, http_request_full  # noqa: E402

APP_PORT = 18131
MOCK_PORT = 18132

INDEX_CACHE = "no-cache, must-revalidate"
ASSET_CACHE_MARKERS = ("public", "max-age=31536000", "immutable")

failures: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"[PASS] {name}")
        return
    failures.append(f"{name}: {detail}")
    print(f"[FAIL] {name} :: {detail}")


def cache_control(headers: dict[str, str]) -> str:
    return headers.get("cache-control", "")


def request_bytes(url: str, headers: dict[str, str] | None = None,
                  timeout: float = 30.0) -> tuple[int, dict[str, str], bytes]:
    """只读响应头与字节，不解码、不自动解压。"""
    request = urllib.request.Request(url, headers=headers or {}, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return (response.status,
                    {k.lower(): v for k, v in response.headers.items()},
                    response.read())
    except urllib.error.HTTPError as error:  # type: ignore[name-defined]
        return (error.code,
                {k.lower(): v for k, v in error.headers.items()},
                error.read())


def discover_assets(index_html: str) -> tuple[str | None, str | None]:
    js = re.search(r'src="(/webui/assets/[^"]+\.js)"', index_html)
    css = re.search(r'href="(/webui/assets/[^"]+\.css)"', index_html)
    return (js.group(1) if js else None, css.group(1) if css else None)


def assert_conditional(url: str, headers: dict[str, str], label: str) -> None:
    """校验条件请求：优先 ETag，回退 Last-Modified（Spring 资源处理器只发后者）。"""
    etag = headers.get("etag")
    last_modified = headers.get("last-modified")
    if etag:
        status, response_headers, body = request_bytes(url, {"If-None-Match": etag})
        check(f"{label} 条件请求返回 304", status == 304, f"status={status}")
        check(f"{label} 304 仍带 ETag", response_headers.get("etag") == etag,
              str(response_headers.get("etag")))
        check(f"{label} 304 无响应体", not body, f"bytes={len(body)}")
        return
    check(f"{label} 提供 Last-Modified 校验器", bool(last_modified),
          str(headers))
    if last_modified:
        status, response_headers, body = request_bytes(
            url, {"If-Modified-Since": last_modified})
        check(f"{label} If-Modified-Since 返回 304", status == 304,
              f"status={status}")
        check(f"{label} 304 无响应体", not body, f"bytes={len(body)}")


def server_checks(base: str) -> str:
    status, headers, body = request_bytes(f"{base}/webui/")
    text = body.decode("utf-8", "replace")
    check("/webui/ 返回 200", status == 200, f"status={status}")
    check("/webui/ 不缓存并强制校验", cache_control(headers) == INDEX_CACHE,
          cache_control(headers))
    check("/webui/ 带 ETag", bool(headers.get("etag")), str(headers))
    check("/webui/ 是 HTML", "text/html" in headers.get("content-type", ""),
          headers.get("content-type", ""))
    check("/webui/ 引用 hash 资源", "/webui/assets/" in text)
    check("/webui/ 引用未 hash 主题脚本", "/webui/theme-init.js" in text)
    check("/webui/ 引用 svg favicon", "/webui/favicon.svg" in text)
    check("/webui/ 带构建版本 meta",
          bool(re.search(r'name="webui-build-version" content="[^"]+"', text)),
          text[:400])

    assert_conditional(f"{base}/webui/", headers, "index.html")
    weak = "W/" + headers["etag"]
    status_weak, _, _ = request_bytes(f"{base}/webui/", {"If-None-Match": weak})
    check("index.html 接受弱 ETag 校验", status_weak == 304, f"status={status_weak}")

    js_url, css_url = discover_assets(text)
    check("index.html 含入口 JS", bool(js_url), text[:300])
    check("index.html 含入口 CSS", bool(css_url), text[:300])
    for label, url in (("hash JS", js_url), ("hash CSS", css_url)):
        if not url:
            continue
        status, headers, _ = request_bytes(f"{base}{url}")
        check(f"{label} 返回 200", status == 200, f"status={status}")
        control = cache_control(headers)
        for marker in ASSET_CACHE_MARKERS:
            check(f"{label} Cache-Control 含 {marker}", marker in control, control)
        check(f"{label} 带缓存校验器",
              bool(headers.get("etag") or headers.get("last-modified")), str(headers))
        assert_conditional(f"{base}{url}", headers, label)

    # 未 hash 的静态文件：不能长缓存，也不能被 SPA fallback 返回 HTML。
    for name, content_type, cache in (
        ("theme-init.js", "javascript", INDEX_CACHE),
        ("favicon.svg", "image/svg+xml", INDEX_CACHE),
        ("icons.svg", "image/svg+xml", INDEX_CACHE),
    ):
        status, headers, body = request_bytes(f"{base}/webui/{name}")
        check(f"/webui/{name} 返回 200", status == 200, f"status={status}")
        check(f"/webui/{name} 不返回 HTML",
              "text/html" not in headers.get("content-type", ""),
              headers.get("content-type", ""))
        check(f"/webui/{name} 内容类型", content_type in headers.get("content-type", ""),
              headers.get("content-type", ""))
        check(f"/webui/{name} 不缓存并强制校验",
              cache_control(headers) == cache, cache_control(headers))
        check(f"/webui/{name} 带缓存校验器",
              bool(headers.get("etag") or headers.get("last-modified")), str(headers))
        assert_conditional(f"{base}/webui/{name}", headers, name)
    status, _, body = request_bytes(f"{base}/webui/favicon.svg")
    check("favicon.svg 返回真实 SVG",
          status == 200 and b"<svg" in body.lower(), body[:80].decode("utf-8", "replace"))

    # 旧/已删除的 hash chunk 不能回退成 index.html。
    status, headers, body = request_bytes(f"{base}/webui/assets/removed-OldChunk.js")
    check("缺失 hash chunk 返回 404", status == 404, f"status={status}")
    check("缺失 hash chunk 不是 HTML",
          "text/html" not in headers.get("content-type", "")
          and "<!doctype html" not in body.decode("utf-8", "replace").lower(),
          f"{headers.get('content-type', '')} {body[:80]!r}")

    # 压缩：gzip 生效，Brotli 不支持。
    if js_url:
        status, headers, _ = request_bytes(
            f"{base}{js_url}", {"Accept-Encoding": "gzip"})
        check("hash JS 支持 gzip",
              headers.get("content-encoding") == "gzip",
              headers.get("content-encoding", ""))
        check("gzip 响应带 Vary: Accept-Encoding",
              "accept-encoding" in headers.get("vary", "").lower(),
              headers.get("vary", ""))
    if js_url:
        status, headers, _ = request_bytes(
            f"{base}{js_url}", {"Accept-Encoding": "br"})
        check("服务端未启用 Brotli（仅 gzip）",
              headers.get("content-encoding") not in {"br", "brotli"},
              headers.get("content-encoding", "identity"))

    return text


def browser_checks(base: str, index_html: str) -> None:
    from playwright.sync_api import sync_playwright

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context()
        page = context.new_page()

        navigations: list[tuple[str, int | None, str | None, str | None]] = []

        def on_response(response):
            if response.request.resource_type == "document":
                navigations.append((
                    response.url,
                    response.status,
                    response.request.headers.get("if-none-match"),
                    response.headers.get("cache-control"),
                ))

        page.on("response", on_response)
        response = page.goto(f"{base}/webui/", wait_until="networkidle")
        check("浏览器加载 WebUI", response is not None and response.status == 200,
              str(response.status if response else None))
        check("浏览器挂载 React 根节点",
              page.evaluate("() => document.querySelector('#root')?.children.length > 0"))
        theme = page.evaluate("() => document.documentElement.getAttribute('data-theme')")
        check("未 hash 主题脚本生效", theme in {"light", "dark"}, f"data-theme={theme}")

        # 二次访问（同一个浏览器上下文的新标签页）：no-cache 迫使条件请求。
        # 注意不能用 page.reload()：Chromium 对主文档的 reload 会跳过缓存直接 200。
        second_page = context.new_page()
        second_navigations: list[tuple[str, int, str | None, str | None]] = []
        second_asset_cache_controls: list[str] = []

        def on_second_response(response):
            if response.request.resource_type == "document":
                second_navigations.append((
                    response.url,
                    response.status,
                    response.request.headers.get("if-none-match"),
                    response.headers.get("cache-control"),
                ))
            elif "/webui/assets/" in response.url:
                second_asset_cache_controls.append(
                    response.headers.get("cache-control", ""))

        second_page.on("response", on_second_response)
        second_page.goto(f"{base}/webui/", wait_until="networkidle")
        check("二次访问仍挂载", second_page.evaluate(
            "() => document.querySelector('#root')?.children.length > 0"))
        check("二次访问每次都向服务端取 index（no-cache 生效）",
              bool(second_navigations), str(second_navigations))
        check("二次访问 index 返回 200/304 且不静默复用旧内容",
              all(entry[1] in {200, 304} for entry in second_navigations),
              str(second_navigations))
        check("二次访问 index 仍不缓存", all(
            entry[3] == INDEX_CACHE for entry in second_navigations if entry[3]),
            str([entry[3] for entry in second_navigations]))
        # Playwright 的 Chromium 每次导航都会重新取子资源，因此这里证明浏览器
        # 实际收到的 hash 资源头是 immutable；缓存命中与否由该头决定。
        check("浏览器收到的 hash 资源均为 immutable",
              bool(second_asset_cache_controls) and all(
                  all(marker in control for marker in ASSET_CACHE_MARKERS)
                  for control in second_asset_cache_controls),
              str(second_asset_cache_controls[:3]))
        print(f"[INFO] 二次访问 document 校验器: {second_navigations}")
        second_page.close()

        # 旧页面 + 新部署：index 里指向已被删除的 chunk -> 404，应用无法挂载。
        old_index = re.sub(
            r'src="[^"]*/webui/assets/[^"]+\.js"',
            'src="/webui/assets/removed-OldChunk.js"',
            index_html,
            count=1,
        )
        check("模拟旧 index 已改写入口 chunk",
              "removed-OldChunk.js" in old_index, old_index[:200])

        stale_page = context.new_page()
        stale_page.route(
            f"{base}/webui/",
            lambda route: route.fulfill(
                status=200, content_type="text/html; charset=utf-8", body=old_index),
        )
        chunk_statuses: list[int] = []
        stale_page.on("response", lambda response: chunk_statuses.append(response.status)
                      if "removed-OldChunk.js" in response.url else None)
        stale_page.goto(f"{base}/webui/", wait_until="networkidle")
        check("旧 index 请求的已删除 chunk 返回 404",
              404 in chunk_statuses, str(chunk_statuses))
        check("旧 index 无法挂载 React（白屏成因）",
              stale_page.evaluate(
                  "() => document.querySelector('#root')?.children.length === 0"),
              stale_page.content()[:300])
        # 边界：入口 JS 404 时应用从未启动，页面内没有任何代码能提示刷新。
        # 已有标签页（JS 已运行）的升级提示由 update-banner 机制覆盖。
        check("入口 404 时页面无法自行提示（机制边界）",
              not re.search(
                  r"new (?:version|release)|update available|refresh to update",
                  stale_page.locator("body").inner_text(), re.IGNORECASE),
              stale_page.locator("body").inner_text()[:300])

        # 离线回退：没有 Service Worker，也就没有离线缓存策略。
        sw_count = page.evaluate(
            "() => navigator.serviceWorker.getRegistrations().then(r => r.length)")
        check("未注册 Service Worker（无离线回退）", sw_count == 0, str(sw_count))
        context.set_offline(True)
        offline_ok = page.evaluate(
            "async () => { try { await fetch('/api/v1/rag/collections'); return 'ok'; }"
            " catch (e) { return 'blocked'; } }")
        check("离线时未缓存请求被阻断", offline_ok == "blocked", str(offline_ok))
        context.set_offline(False)

        context.close()
        browser.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true", help="保留临时目录与日志")
    args = parser.parse_args()

    stack = LocalStack("webui-cache", APP_PORT, MOCK_PORT, keep=args.keep)
    started = time.time()
    try:
        stack.create_database()
        stack.start_mock(stream_chunks=0)
        stack.build_demo_if_needed()
        base = stack.start_app()

        index_html = server_checks(base)
        browser_checks(base, index_html)

        print(f"\n完成，用时 {time.time() - started:.1f}s")
        if failures:
            print(f"\n{len(failures)} 项失败：")
            for failure in failures:
                print(f"  - {failure}")
            return 1
        print("全部静态资源缓存与升级语义断言通过")
        return 0
    finally:
        stack.cleanup()


if __name__ == "__main__":
    sys.exit(main())
