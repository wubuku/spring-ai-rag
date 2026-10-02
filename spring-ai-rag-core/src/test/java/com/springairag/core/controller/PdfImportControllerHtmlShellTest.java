package com.springairag.core.controller;

import com.springairag.core.entity.FsFile;
import com.springairag.core.service.MarkdownRendererService;
import com.springairag.core.service.PdfImportService;
import com.springairag.core.service.PdfToRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Batch 814。预览页外壳里那个 {@code <base href>} 的转义边界。
 *
 * <p>Batch 813 把净化放进 {@code MarkdownRendererService}，于是<b>正文</b>安全了。
 * 但 {@code buildHtmlShell} 是控制器自己用字符串拼的，正净化不到它：
 * {@code title} 走了 {@code escapeHtml}，{@code baseTag} 没有。
 *
 * <p>本类把"请求派生的值进 HTML 属性必须转义"钉成契约，而不是钉"这个载荷现在打不穿"——
 * 后者取决于 {@code PdfImportService.getFile} 恰好会 404，那是另一处的检查，
 * 换一处实现就不会自动跟着对。
 */
@DisplayName("预览页外壳：请求派生的值进 HTML 标记必须转义")
class PdfImportControllerHtmlShellTest {

    private PdfImportService pdfImportService;
    private MarkdownRendererService markdownRendererService;
    private PdfImportController controller;

    @BeforeEach
    void setUp() {
        pdfImportService = mock(PdfImportService.class);
        markdownRendererService = mock(MarkdownRendererService.class);
        controller = new PdfImportController(
                pdfImportService, markdownRendererService, mock(PdfToRagService.class));
    }

    /** 让任意路径都能"找到文件"，把控制器的转义行为与文件存在性检查隔离开。 */
    private void stubAnyPathResolves() {
        FsFile fsFile = new FsFile();
        fsFile.setPath("whatever/default.md");
        fsFile.setMimeType("text/markdown");
        when(pdfImportService.getFile(anyString())).thenReturn(Optional.of(fsFile));
        when(markdownRendererService.renderToHtml(any(FsFile.class)))
                .thenReturn("<h1>ok</h1>");
    }

    // ── base href ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("base href 里的引号必须被转义，不能闭合属性")
    void baseHrefEscapesQuotes() {
        stubAnyPathResolves();

        ResponseEntity<String> response = controller.previewHtmlFragment(
                "\"><script>alert(1)</script>/x.pdf");

        assertEquals(200, response.getStatusCode().value());
        String html = response.getBody();
        assertNotNull(html);
        assertFalse(html.contains("\"><script"),
                () -> "base href 属性被载荷闭合了：" + html);
        assertFalse(html.contains("<script>alert(1)</script>"),
                () -> "载荷原样进入了页面：" + html);
    }

    @Test
    @DisplayName("base href 里的尖括号必须被转义")
    void baseHrefEscapesAngleBrackets() {
        stubAnyPathResolves();

        ResponseEntity<String> response = controller.previewHtmlFragment(
                "<img src=x onerror=alert(1)>/x.pdf");

        String html = response.getBody();
        assertNotNull(html);
        assertFalse(html.contains("<img src=x"), () -> "标签原样进入了页面：" + html);
    }

    @Test
    @DisplayName("页面标题里的同一批字符同样必须被转义（回归护栏）")
    void titleKeepsEscaping() {
        stubAnyPathResolves();

        ResponseEntity<String> response = controller.previewHtmlFragment(
                "\"><script>alert(1)</script>/x.pdf");

        String html = response.getBody();
        assertNotNull(html);
        assertTrue(html.contains("&quot;"), () -> "引号未被转义：" + html);
    }

    // ── 正常路径不能被"修坏" ────────────────────────────────────────────────

    @Test
    @DisplayName("合法 UUID 的 base href 必须仍然是可用的相对基址")
    void legitimateUuidStillProducesUsableBase() {
        stubAnyPathResolves();
        String uuid = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

        ResponseEntity<String> response = controller.previewHtmlFragment(uuid + "/original.pdf");

        String html = response.getBody();
        assertNotNull(html);
        assertTrue(html.contains("<base href=\"/files/raw/" + uuid + "/\">"),
                () -> "合法 UUID 的 base 标签坏了：" + html);
    }

    @Test
    @DisplayName("独立整页端点走同一外壳，同样受约束")
    void standalonePageSharesTheSameBoundary() {
        stubAnyPathResolves();

        ResponseEntity<String> response =
                controller.previewHtmlPage("\"><script>alert(1)</script>");

        assertEquals(200, response.getStatusCode().value());
        String html = response.getBody();
        assertNotNull(html);
        assertFalse(html.contains("\"><script"), () -> "独立整页端点的 base href 未转义：" + html);
    }

    // ── 安全究竟来自哪里：钉住这条事实 ──────────────────────────────────────

    @Test
    @DisplayName("文件不存在时返回 404——当前安全性依赖这条，而非转义")
    void missingFileStillReturnsNotFound() {
        when(pdfImportService.getFile(anyString())).thenReturn(Optional.empty());

        ResponseEntity<String> response = controller.previewHtmlFragment(
                "\"><script>alert(1)</script>/x.pdf");

        assertEquals(404, response.getStatusCode().value(),
                "如果这条不再成立，说明『文件必须存在』不再是阻止注入的理由，"
                        + "此时转义就是唯一防线。");
    }
}
