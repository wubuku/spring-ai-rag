#!/usr/bin/env python3
"""Generate the real-format retrieval goldenset corpus.

This script is a development aid: the generated files are committed under
`testdata/real-goldenset/corpus/`, so evaluation runs do not regenerate them.
It exists so the corpus can be rebuilt and reviewed, not so it can be mutated
at test time.

Formats are real files parsed by the application:
- PDF: PyMuPDF writes two-column bodies, three-column tables and mixed layouts
- DOCX: pandoc converts the markdown source to a real OOXML package
- MD / HTML / TXT / CSV: written directly (TXT includes a GBK-encoded document)

Text content is fictional Nebula platform documentation. It is deliberately
small and stable: queries and their human annotations live in
`testdata/real-goldenset/queries.json`, and are not generated from model output.
"""

from __future__ import annotations

import io
import pathlib
import re
import subprocess
import sys
import zipfile

import fitz

ROOT = pathlib.Path(__file__).resolve().parents[2]
CORPUS = ROOT / "testdata" / "real-goldenset" / "corpus"

PAGE_W, PAGE_H = 595, 842
MARGIN = 55
LINE_HEIGHT = 14.5


def has_cjk(text: str) -> bool:
    return any("\u4e00" <= ch <= "\u9fff" for ch in text)


def font_name(text: str) -> str:
    return "china-s" if has_cjk(text) else "helv"


def tokenize(text: str) -> list[str]:
    """Split into CJK characters, Latin words and whitespace runs.

    Latin words must stay whole (annotations match whole terms), while CJK has no
    spaces and therefore wraps per character.
    """
    return re.findall(r"[\u4e00-\u9fff]|\s+|[^\s\u4e00-\u9fff]+", text)


def text_width(text: str, fontsize: float) -> float:
    """Estimate rendered width.

    PyMuPDF 的内置 ``china-s`` 字体把每个字符（含拉丁字母和空格）按整字宽
    渲染，``Font.text_length`` 会低估约 40%，直接用它会写出越过栏边界、
    两栏互相重叠的文本。这里按实际渲染规则保守估算。
    """
    if has_cjk(text):
        return len(text) * fontsize
    return fitz.Font(fontname="helv").text_length(text, fontsize)


def wrap_lines(text: str, width: float, fontsize: float) -> list[str]:
    lines: list[str] = []
    current = ""
    for token in tokenize(text):
        if not current:
            if token.isspace():
                continue
            current = token
            continue
        candidate = current + token
        if text_width(candidate, fontsize) <= width:
            current = candidate
        else:
            lines.append(current.rstrip())
            current = "" if token.isspace() else token.lstrip()
    if current.strip():
        lines.append(current.rstrip())
    return lines or [""]


def draw_paragraph(page: fitz.Page, rect: fitz.Rect, text: str,
                   fontsize: float = 10.5, leading: float = LINE_HEIGHT) -> float:
    """Draw a wrapped paragraph inside rect; return the y after the last line."""
    lines = wrap_lines(text, rect.width, fontsize)
    y = rect.y0 + fontsize
    for line in lines:
        if y > rect.y1:
            raise RuntimeError(f"paragraph overflow in {rect}: {text[:30]}...")
        page.insert_text((rect.x0, y), line, fontname=font_name(line), fontsize=fontsize)
        y += leading
    return y + 6


def draw_two_columns(page: fitz.Page, left: list[str], right: list[str],
                     top: float = 70, bottom: float = 780) -> None:
    left_rect = fitz.Rect(MARGIN, top, 265, bottom)
    right_rect = fitz.Rect(330, top, PAGE_W - MARGIN, bottom)
    left_y = draw_blocks_in_column(page, left_rect, left)
    right_y = draw_blocks_in_column(page, right_rect, right)
    if left_y > bottom or right_y > bottom:
        raise RuntimeError("two-column content overflows the page")


def draw_blocks_in_column(page: fitz.Page, rect: fitz.Rect, blocks: list) -> float:
    y = rect.y0
    for block in blocks:
        if isinstance(block, str):
            sub = fitz.Rect(rect.x0, y, rect.x1, rect.y1)
            y = draw_paragraph(page, sub, block)
            continue
        kind = block["kind"]
        if kind == "table":
            y = draw_table(page, fitz.Rect(rect.x0, y, rect.x1, rect.y1), block)
            continue
        if kind == "text":
            sub = fitz.Rect(rect.x0, y, rect.x1, rect.y1)
            y = draw_paragraph(page, sub, block["text"], block.get("fontsize", 10.5))
            continue
        raise RuntimeError(f"unknown block kind: {kind}")
    return y


def draw_table(page: fitz.Page, rect: fitz.Rect, spec: dict,
               fontsize: float = 10.0) -> float:
    headers = spec["headers"]
    rows = spec["rows"]
    col_count = len(headers)
    col_width = rect.width / col_count
    row_height = 22.0
    y = rect.y0
    header_font = "hebo" if not any(has_cjk(h) for h in headers) else "china-s"
    for row_index, row in enumerate([headers] + rows):
        x = rect.x0
        for col_index, cell in enumerate(row):
            cell_rect = fitz.Rect(x + 3, y + 3, x + col_width - 3, y + row_height - 3)
            text = str(cell)
            name = header_font if row_index == 0 else font_name(text)
            lines = wrap_lines(text, cell_rect.width, fontsize)
            if len(lines) > 1:
                raise RuntimeError(f"table cell wraps unexpectedly: {text}")
            page.insert_text((cell_rect.x0, y + 15), lines[0],
                             fontname=name, fontsize=fontsize)
            page.draw_line((x, y), (x, y + row_height), color=(0.4, 0.4, 0.4))
            x += col_width
        page.draw_line((rect.x0, y), (rect.x0 + col_width * col_count, y),
                       color=(0.4, 0.4, 0.4))
        y += row_height
    page.draw_line((rect.x0, y), (rect.x0 + col_width * col_count, y),
                   color=(0.4, 0.4, 0.4))
    return y + 10


def draw_single_column(page: fitz.Page, blocks: list[str], top: float = 70,
                       bottom: float = 790) -> None:
    rect = fitz.Rect(MARGIN, top, PAGE_W - MARGIN, bottom)
    y = draw_blocks_in_column(page, rect, blocks)
    if y > bottom:
        raise RuntimeError("single-column content overflows the page")


def render_pdf(path: pathlib.Path, layout: str, title: str, blocks) -> None:
    doc = fitz.open()
    page = doc.new_page(width=PAGE_W, height=PAGE_H)
    heading_font = "china-s" if has_cjk(title) else "hebo"
    page.insert_text((MARGIN, 45), title, fontname=heading_font, fontsize=15)
    if layout == "columns":
        draw_two_columns(page, blocks["left"], blocks["right"])
    elif layout == "single":
        draw_single_column(page, blocks)
    else:
        raise RuntimeError(f"unknown pdf layout: {layout}")
    # 固定元数据/文档 ID，保证同样的源码每次生成字节级一致的 PDF。
    doc.set_metadata({
        "title": title,
        "author": "spring-ai-rag goldenset",
        "creator": "spring-ai-rag goldenset",
        "producer": "spring-ai-rag goldenset",
        "creationDate": "D:20260101000000Z",
        "modDate": "D:20260101000000Z",
    })
    doc.save(path, deflate=True, no_new_id=True)
    doc.close()


def write_text(path: pathlib.Path, text: str, encoding: str = "utf-8") -> None:
    path.write_bytes(text.encode(encoding))


def make_docx(path: pathlib.Path, markdown: str) -> None:
    temp = path.with_suffix(".source.md")
    temp.write_text(markdown, encoding="utf-8")
    subprocess.run(["pandoc", "-f", "markdown", "-t", "docx", str(temp), "-o", str(path)],
                   check=True)
    temp.unlink()
    normalize_docx(path)


def normalize_docx(path: pathlib.Path) -> None:
    """把 pandoc 写入的创建/修改时间与 zip 条目时间固定，保证 DOCX 可复现。"""
    stamp = "2026-01-01T00:00:00Z"
    with zipfile.ZipFile(path) as source:
        entries = [(info.filename, source.read(info.filename))
                   for info in source.infolist()]
    rewritten = []
    for name, data in entries:
        if name == "docProps/core.xml":
            text = data.decode("utf-8")
            text = re.sub(r"<dcterms:created[^>]*>.*?</dcterms:created>",
                          f'<dcterms:created xsi:type="dcterms:W3CDTF">{stamp}'
                          "</dcterms:created>", text, flags=re.S)
            text = re.sub(r"<dcterms:modified[^>]*>.*?</dcterms:modified>",
                          f'<dcterms:modified xsi:type="dcterms:W3CDTF">{stamp}'
                          "</dcterms:modified>", text, flags=re.S)
            data = text.encode("utf-8")
        rewritten.append((name, data))
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as target:
        for name, data in rewritten:
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            target.writestr(info, data)
    path.write_bytes(buffer.getvalue())


def pdf_two_column_en() -> None:
    render_pdf(CORPUS / "pdf-two-column-en.pdf", "columns",
               "Nebula Runtime Overview", {
                   "left": [
                       "Nebula is a distributed inference platform. The runtime separates "
                       "the control plane from the data plane so that a control-plane upgrade "
                       "never interrupts live traffic.",
                       "A standard deployment runs three control-plane nodes. Each data-plane "
                       "node keeps a warm model cache and a local request queue, which is why a "
                       "cold start stays under 800 ms even after a rolling restart.",
                       "The runtime retries a failed inference request at most three times. "
                       "Idempotency keys are recorded for 24 hours so a retried request is never "
                       "double-charged.",
                   ],
                   "right": [
                       "Capacity planning starts from the appliance matrix. The NEBULA-400 "
                       "appliance sustains 12000 QPS with four accelerators and a 4U footprint.",
                       "Operators can drain a node with nebulactl drain --node before "
                       "maintenance. Drain waits for in-flight requests, then marks the node "
                       "unschedulable.",
                       "Metrics are exported every 15 seconds. The runtime exposes "
                       "nebula_queue_depth and nebula_tokens_per_second as its primary capacity "
                       "signals.",
                   ],
               })


def pdf_two_column_zh() -> None:
    render_pdf(CORPUS / "pdf-two-column-zh.pdf", "columns",
               "星云平台部署指南", {
                   "left": [
                       "星云平台采用控制面与数据面分离的架构。控制面负责调度与配额，数据面只处理推理请求，"
                       "因此升级控制面不会中断在线流量。",
                       "标准部署需要三个控制面节点。单节点部署只适用于开发环境，生产环境至少需要两个可用区。",
                       "升级窗口默认是 45 分钟，滚动升级期间旧副本继续提供服务。",
                   ],
                   "right": [
                       "部署前先执行 preflight 检查，确认 PostgreSQL 与对象存储均可访问。",
                       "地域 cn-north-4 支持双活部署，跨可用区复制延迟低于 20 毫秒。",
                       "回滚使用同一个版本号重新发布，不需要重新导入模型。",
                   ],
               })


def pdf_table_three_column() -> None:
    render_pdf(CORPUS / "pdf-table-three-column.pdf", "single", "Nebula Appliance Matrix", [
        "The appliance matrix lists sustained throughput and rack footprint per model.",
        {"kind": "table", "headers": ["Model", "Sustained QPS", "Rack Units"], "rows": [
            ["NEBULA-100", "1200", "1U"],
            ["NEBULA-200", "3000", "2U"],
            ["NEBULA-300", "6000", "2U"],
            ["NEBULA-400", "9000", "4U"],
            ["NEBULA-500", "18000", "8U"],
        ]},
        "Values are measured with a 512-token prompt and a warm model cache. "
        "A model row is only offered in regions where the required accelerators are stocked.",
    ])


def pdf_table_two_column() -> None:
    render_pdf(CORPUS / "pdf-table-two-column.pdf", "single",
               "Nebula Service Level Specification", [
                   "This specification states the committed service level for the managed platform.",
                   {"kind": "table", "headers": ["Metric", "Value"], "rows": [
                       ["Availability", "99.95%"],
                       ["P99 latency", "42 ms"],
                       ["RPO", "15 minutes"],
                       ["RTO", "30 minutes"],
                       ["Audit retention", "400 days"],
                       ["Support first response", "30 minutes"],
                   ]},
                   "Measurements exclude scheduled maintenance announced at least seven days in advance.",
               ])


def pdf_mixed_columns_table() -> None:
    render_pdf(CORPUS / "pdf-mixed-columns-table.pdf", "columns",
               "Incident Response Playbook", {
                   "left": [
                       "Severity is assigned at triage. A P1 incident pages the primary on-call "
                       "immediately and opens a shared incident channel.",
                       "The first response target for a P1 is 7 minutes. P2 incidents are "
                       "acknowledged within 30 minutes.",
                       "Every incident produces a timeline entry and a follow-up action with a "
                       "named owner.",
                   ],
                   "right": [
                       "The response phases and their owners are fixed:",
                       {"kind": "table", "headers": ["Phase", "Owner"], "rows": [
                           ["Detect", "on-call engineer"],
                           ["Mitigate", "service owner"],
                           ["Review", "incident commander"],
                           ["Close", "engineering manager"],
                       ]},
                       "On-call rotation is 12 hours per shift, with a handover note at the changeover.",
                   ],
               })


def pdf_mixed_zh_table() -> None:
    render_pdf(CORPUS / "pdf-mixed-zh-table.pdf", "columns",
               "星云平台容量规划", {
                   "left": [
                       "容量规划从分片开始。每个分片建议不超过 18 GB，超过后需要重新分片。",
                       "扩容阈值是 75%，达到阈值后平台会自动增加一个副本，但不会自动扩展分片。",
                       "存储配额按集合计算，单个集合最多 500 万个 chunk。",
                   ],
                   "right": [
                       "以下参数按地域生效：",
                       {"kind": "table", "headers": ["地域", "单分片上限"], "rows": [
                           ["cn-north-4", "18 GB"],
                           ["cn-east-2", "16 GB"],
                           ["cn-south-1", "20 GB"],
                       ]},
                       "变更分片上限需要提交工单，生效时间为下一个维护窗口。",
                   ],
               })


def pdf_single_column_whitepaper() -> None:
    render_pdf(CORPUS / "pdf-single-column-whitepaper.pdf", "single",
               "Nebula Architecture Whitepaper", [
                   "The architecture has two layers. The control plane schedules work and stores "
                   "declarative configuration in etcd; three etcd nodes form a quorum.",
                   "The data plane executes inference and streams results. It is stateless except "
                   "for a bounded request queue, so a data-plane node can be replaced at any time.",
                   "Every request carries a trace identifier that is propagated across services. "
                   "Traces are sampled at 1 in 1000 by default.",
                   "A dedicated gateway terminates TLS and enforces per-tenant quotas before a "
                   "request reaches the scheduler.",
               ])


def pdf_single_column_zh_report() -> None:
    render_pdf(CORPUS / "pdf-single-column-zh-report.pdf", "single",
               "星云平台安全白皮书", [
                   "所有租户数据在写入前使用 AES-256 加密，密钥由平台托管并按租户隔离。",
                   "密钥轮换周期是 90 天，轮换期间新旧密钥并存，避免在线解密失败。",
                   "审计日志记录管理动作与数据导出，默认保留 180 天，可按合规要求延长。",
                   "生产环境禁止直接访问数据库，所有变更必须通过审计过的管理接口。",
               ])


def pdf_two_column_en_sdk() -> None:
    render_pdf(CORPUS / "pdf-two-column-en-sdk.pdf", "columns",
               "Nebula SDK Reference", {
                   "left": [
                       "The Python and Java SDKs share the same retry contract. A retried create "
                       "must carry the same idempotency key; otherwise the server treats it as a "
                       "new request.",
                       "SDK version NEBULA-SDK-2.4.0 adds streaming cancellation and a bounded "
                       "connection pool.",
                       "Configuration is read from a profile file, then overridden by environment "
                       "variables.",
                   ],
                   "right": [
                       "Timeouts default to 30 seconds for search and 120 seconds for generation.",
                       "The SDK retries only 429 and 503 responses, and stops after three attempts.",
                       "Every error carries a request identifier that support can use to find the "
                       "server-side trace.",
                   ],
               })


def pdf_two_column_zh_ops() -> None:
    render_pdf(CORPUS / "pdf-two-column-zh-ops.pdf", "columns",
               "星云平台运维手册", {
                   "left": [
                       "后台任务使用 lease 协调。lease 过期时间是 60 秒，实例崩溃后由另一个副本接管。",
                       "日志默认按 1/1000 采样，故障期间可以临时提高到 1/100。",
                       "每个节点保留最近 7 天的本地指标，更早的数据写入对象存储。",
                   ],
                   "right": [
                       "重启前先摘除流量，确认队列为空后再停止进程。",
                       "巡检脚本每隔 5 分钟检查一次健康状态，连续三次失败会触发告警。",
                       "容量告警阈值是 80%，超过后先扩容再排查异常流量。",
                   ],
               })


def docx_paragraph_table_en() -> None:
    make_docx(CORPUS / "docx-paragraph-table-en.docx", """# Nebula Billing Rules

Billing is metered per second, with a minimum billable unit of 60 seconds. Unused reserved capacity is not refunded.

| Plan | Discount |
| --- | --- |
| Standard | 0% |
| Committed 1 year | 18% |
| Committed 3 years | 28% |

Invoices close on the first day of each calendar month. Credits are applied before tax.
""")


def docx_paragraph_table_zh() -> None:
    make_docx(CORPUS / "docx-paragraph-table-zh.docx", """# 星云平台计费说明

按秒计费，最小计费单位 60 秒。预留实例按自然月结算，未使用部分不退。

| 套餐 | 折扣 |
| --- | --- |
| 按量付费 | 无 |
| 包年 | 35% |
| 包三年 | 50% |

账单在每个自然月 1 日生成，优惠金额在税前扣除。
""")


def docx_list_heading_en() -> None:
    make_docx(CORPUS / "docx-list-heading-en.docx", """# Release Checklist

## Before the window

- Freeze changes two hours before the window.
- Confirm the canary canary at 5% traffic is healthy.

## During the window

- Run the smoke suite against the canary at 5% traffic.
- Rollback window is 30 minutes; after that the release is treated as accepted.

## After the window

- Publish the change note and update the status page.
""")


def docx_list_heading_zh() -> None:
    make_docx(CORPUS / "docx-list-heading-zh.docx", """# 发布检查清单

## 发布前

- 发布前两小时冻结变更。
- 先向 5% 灰度流量发布，观察 15 分钟。

## 发布中

- 回滚窗口 25 分钟，超时后按已接受处理。

## 发布后

- 更新变更记录与状态页。
""")


def docx_mixed_en() -> None:
    make_docx(CORPUS / "docx-mixed-en.docx", """# Nebula Onboarding Guide

The first deployment takes four guided steps: create a collection, upload a document, embed it, then run a search.

Install the CLI with NEBULA-CLI-1.9 and then run nebulactl login.

| Step | Command |
| --- | --- |
| Create | nebulactl collection create |
| Embed | nebulactl document embed |

Chinese teams often keep the original document title so citations stay readable.
""")


def docx_mixed_zh() -> None:
    make_docx(CORPUS / "docx-mixed-zh.docx", """# 星云平台接入指南

接入分为 5 步：创建集合、上传文档、生成向量、验证检索、上线问答。

SDK 版本 2.4.0 支持流式取消，升级前先确认兼容性。

| 环境 | 地址 |
| --- | --- |
| 测试 | test.example.com |
| 生产 | api.example.com |

接入验收必须包含中英文各 10 条查询。
""")


def md_table_en() -> None:
    write_text(CORPUS / "md-table-en.md", """# Quota Matrix

| Resource | Default quota |
| --- | --- |
| Concurrent requests | 64 |
| Documents per collection | 200000 |
| Chunks per collection | 5,000,000 |
| Upload size per file | 20 MB |

Quotas are enforced per tenant. Raising a quota requires an approved capacity review.
""")


def md_code_en() -> None:
    write_text(CORPUS / "md-code-en.md", """# Rate Limit Middleware

A rejected request returns these headers:

```http
X-RateLimit-Limit: 120
X-RateLimit-Remaining: 7
Retry-After: 2
```

The token bucket refills at 120 requests per second per API key. A rejected request returns 429 and never reaches retrieval.
""")


def md_table_zh() -> None:
    write_text(CORPUS / "md-table-zh.md", """# 配额对照表

| 资源 | 默认配额 |
| --- | --- |
| 单次查询字符 | 10000 |
| 单文档大小 | 20 MB |
| 并发请求 | 64 |
| 集合数量 | 200 |

配额按租户计算，临时提升需要容量评审。
""")


def md_mixed_zh() -> None:
    write_text(CORPUS / "md-mixed-zh.md", """# 故障排查手册

遇到 429 时先看响应头里的 Retry-After，再按指数退避重试，最多重试 3 次。

错误码 RAG-4291 表示配额耗尽，RAG-4017 表示 API Key 已过期。

If the error persists, attach the request id to the ticket.

向量维度不匹配会返回 RAG-5032，需要重建索引。
""")


def md_heading_en() -> None:
    write_text(CORPUS / "md-heading-en.md", """# Observability Metrics

## Retrieval latency

rag_search_p95_ms tracks the 95th percentile retrieval latency; the SLO target is 250 ms.

## Embedding failures

rag_embed_errors_total counts failed embedding calls by provider.

## Queue pressure

A sustained queue depth above 1000 triggers the capacity alert.
""")


def md_mixed_en() -> None:
    write_text(CORPUS / "md-mixed-en.md", """# Data Retention Policy

Audit logs are kept for 90 days unless a legal hold extends them.

Embedding backups are kept for 7 days; restore takes about 2 hours.

删除请求会在 24 小时内生效，缓存副本同步失效。

User feedback rows are retained for 365 days unless erased.
""")


def html_table_en() -> None:
    write_text(CORPUS / "html-table-en.html", """<!doctype html>
<html><head><title>Region Availability</title>
<style>body{font-family:sans-serif} .hidden{display:none}</style>
<script>window.analytics=function(){return "noise";}</script>
</head><body>
<h1>Region Availability</h1>
<script>var tracking = {id: "noise-1"};</script>
<table>
<thead><tr><th>Region</th><th>Zones</th><th>Median RTT</th></tr></thead>
<tbody>
<tr><td>cn-north-4</td><td>2</td><td>38 ms</td></tr>
<tr><td>cn-east-2</td><td>3</td><td>22 ms</td></tr>
<tr><td>eu-west-1</td><td>3</td><td>180 ms</td></tr>
<tr><td>us-west-2</td><td>3</td><td>210 ms</td></tr>
</tbody></table>
<p>A region is selectable only when all of its zones report healthy.</p>
<div class="hidden">internal-only note</div>
</body></html>
""")


def html_table_zh() -> None:
    write_text(CORPUS / "html-table-zh.html", """<!doctype html>
<html><head><title>地域可用性</title>
<style>.ad{display:none}</style><script>window.pixel=1;</script>
</head><body>
<h1>地域可用性</h1>
<p>华东区域包含 2 个可用区，跨可用区复制延迟低于 25 毫秒。</p>
<table><thead><tr><th>地域</th><th>复制延迟</th></tr></thead>
<tbody>
<tr><td>cn-east-2</td><td>18 ms</td></tr>
<tr><td>cn-north-4</td><td>20 ms</td></tr>
</tbody></table>
<script>console.log("noise-2")</script>
</body></html>
""")


def html_mixed_zh() -> None:
    write_text(CORPUS / "html-mixed-zh.html", """<!doctype html>
<html><head><title>API 错误码大全</title><script>var noise=2;</script></head>
<body>
<h1>API 错误码大全</h1>
<ul>
<li>RAG-4001 参数校验失败</li>
<li>RAG-4003 无权限访问该集合</li>
<li>RAG-4290 并发超限，请降低并发</li>
<li>RAG-5031 上游超时</li>
</ul>
<p>RAG-5001 means the embedding provider is unavailable.</p>
<p>所有错误码都包含 request id，便于定位服务端 trace。</p>
</body></html>
""")


def html_heading_en() -> None:
    write_text(CORPUS / "html-heading-en.html", """<!doctype html>
<html><head><title>Security Hardening</title></head><body>
<h1>Security Hardening</h1>
<h2>Transport</h2>
<p>TLS 1.3 is required at the edge; plaintext traffic is rejected.</p>
<p>Mutual TLS is optional for private deployments and mandatory for partner integrations.</p>
<h2>Keys</h2>
<p>Keys are hashed at rest and never logged.</p>
</body></html>
""")


def html_noisy_en() -> None:
    write_text(CORPUS / "html-noisy-en.html", """<!doctype html>
<html><head><title>Deployment FAQ</title>
<script>var a=1;var b=2;var c=3;</script>
<style>.x{color:red}.y{color:blue}</style>
</head><body>
<h1>Deployment FAQ</h1>
<p>Can Nebula run offline? Yes, an offline installation bundle is about 1.2 GB.</p>
<p>支持私有化部署吗？支持，离线安装包约 1.2 GB。</p>
<p>Does the platform support ARM64? Yes, images are multi-arch.</p>
<script>document.write("noise");</script>
</body></html>
""")


def txt_utf8_zh() -> None:
    write_text(CORPUS / "txt-utf8-zh.txt", """星云平台术语表

租户 tenant：逻辑隔离的计费与权限单位。
命名空间 namespace：集合与凭证的命名边界。
灰度发布 canary release：先向小比例流量开放新版本。
分片 shard：向量数据的物理分区，单分片建议不超过 18 GB。
回填 backfill：为历史文档补充向量。
""")


def txt_utf8_en() -> None:
    write_text(CORPUS / "txt-utf8-en.txt", """Support Escalation Policy

Sev1: acknowledge within 15 minutes and page the incident commander.
Sev2: acknowledge within 4 hours during business hours.
Sev3: respond within two business days.
Escalation to engineering requires a written impact summary.
""")


def txt_ascii_en() -> None:
    write_text(CORPUS / "txt-ascii-en.txt", """CLI Cheatsheet

nebulactl collection create --name docs
nebulactl document embed --id 42
nebulactl drain --node node-7
nebulactl search --query "retention policy"

Run nebulactl version to confirm the client build.
""")


def txt_gbk_zh() -> None:
    write_text(CORPUS / "txt-gbk-zh.txt", """兼容性说明

平台只接受 1024 维向量，其他维度需要新建 Profile。
文档编码支持 UTF-8 与 GBK，导入时会自动探测。
浏览器端要求 Chromium 110 或更高版本。
""", encoding="gbk")


def csv_metrics() -> None:
    write_text(CORPUS / "csv-metrics.csv", """scenario,throughput,p99_latency_ms
bulk_ingest,2400 docs/min,180
search_hybrid,1800 qps,95
chat_generation,120 req/min,2400
""")


DOCS = {
    "pdf-two-column-en.pdf": pdf_two_column_en,
    "pdf-two-column-zh.pdf": pdf_two_column_zh,
    "pdf-table-three-column.pdf": pdf_table_three_column,
    "pdf-table-two-column.pdf": pdf_table_two_column,
    "pdf-mixed-columns-table.pdf": pdf_mixed_columns_table,
    "pdf-mixed-zh-table.pdf": pdf_mixed_zh_table,
    "pdf-single-column-whitepaper.pdf": pdf_single_column_whitepaper,
    "pdf-single-column-zh-report.pdf": pdf_single_column_zh_report,
    "pdf-two-column-en-sdk.pdf": pdf_two_column_en_sdk,
    "pdf-two-column-zh-ops.pdf": pdf_two_column_zh_ops,
    "docx-paragraph-table-en.docx": docx_paragraph_table_en,
    "docx-paragraph-table-zh.docx": docx_paragraph_table_zh,
    "docx-list-heading-en.docx": docx_list_heading_en,
    "docx-list-heading-zh.docx": docx_list_heading_zh,
    "docx-mixed-en.docx": docx_mixed_en,
    "docx-mixed-zh.docx": docx_mixed_zh,
    "md-table-en.md": md_table_en,
    "md-code-en.md": md_code_en,
    "md-table-zh.md": md_table_zh,
    "md-mixed-zh.md": md_mixed_zh,
    "md-heading-en.md": md_heading_en,
    "md-mixed-en.md": md_mixed_en,
    "html-table-en.html": html_table_en,
    "html-table-zh.html": html_table_zh,
    "html-mixed-zh.html": html_mixed_zh,
    "html-heading-en.html": html_heading_en,
    "html-noisy-en.html": html_noisy_en,
    "txt-utf8-zh.txt": txt_utf8_zh,
    "txt-utf8-en.txt": txt_utf8_en,
    "txt-ascii-en.txt": txt_ascii_en,
    "txt-gbk-zh.txt": txt_gbk_zh,
    "csv-metrics.csv": csv_metrics,
}


def main() -> int:
    CORPUS.mkdir(parents=True, exist_ok=True)
    for name, build in DOCS.items():
        build()
        print("generated", name)
    print(f"{len(DOCS)} corpus files written to {CORPUS}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
