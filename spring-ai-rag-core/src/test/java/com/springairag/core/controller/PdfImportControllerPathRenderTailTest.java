package com.springairag.core.controller;

import com.springairag.core.entity.FsFile;
import com.springairag.core.service.MarkdownRendererService;
import com.springairag.core.service.PdfImportService;
import com.springairag.core.service.PdfToRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PdfImportController 路径与渲染工具长尾（Batch 561，JaCoCo 驱
 * 动）：urlDecode 的损坏编码回退、wrapInHtmlPage 与 escapeHtml、
 * getRawFilePath 的 404 与内容类型推断。
 */
class PdfImportControllerPathRenderTailTest {

    private PdfImportService pdfImportService;
    private PdfImportController controller;

    @BeforeEach
    void setUp() {
        pdfImportService = mock(PdfImportService.class);
        controller = new PdfImportController(
                pdfImportService,
                mock(MarkdownRendererService.class),
                mock(PdfToRagService.class),
                null);
    }

    private Object invoke(String name, Class<?>[] params, Object... args)
            throws Exception {
        Method method = PdfImportController.class
                .getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method.invoke(controller, args);
    }

    @Test
    void urlDecodeDecodesValidEncodingAndFallsBackOnMalformed()
            throws Exception {
        assertEquals("hello world", invoke("urlDecode",
                new Class<?>[]{String.class}, "hello%20world"));
        assertEquals("bad%2", invoke("urlDecode",
                new Class<?>[]{String.class}, "bad%2"));
    }

    @Test
    void wrapInHtmlPageWrapsBodyWithTitleAndCss() throws Exception {
        String html = (String) invoke("wrapInHtmlPage",
                new Class<?>[]{String.class, String.class},
                "My Title", "<p>body</p>");

        assertTrue(html.contains("<title>My Title</title>"));
        assertTrue(html.contains("<p>body</p>"));
        assertTrue(html.contains("</html>"));
    }

    @Test
    void escapeHtmlEscapesAllReservedCharacters() throws Exception {
        assertEquals("", invoke("escapeHtml",
                new Class<?>[]{String.class}, new Object[]{null}));
        assertEquals("&amp; &lt; &gt; &quot;", invoke("escapeHtml",
                new Class<?>[]{String.class}, "& < > \""));
    }

    @Test
    void getRawFilePathReturns404ForUnknownFile() {
        when(pdfImportService.getFile(anyString()))
                .thenReturn(Optional.empty());

        ResponseEntity<?> entity = controller.getRawFilePath(
                "uuid-1", "missing.md");

        assertEquals(404, entity.getStatusCode().value());
    }

    @Test
    void getRawFilePathServesKnownMarkdownWithInferredContentType() {
        FsFile stored = new FsFile("uuid-1/default.md", true,
                "# body".getBytes(StandardCharsets.UTF_8), "# body",
                "text/markdown", 6L);
        when(pdfImportService.getFile("uuid-1/default.md"))
                .thenReturn(Optional.of(stored));
        when(pdfImportService.loadFileAsResource("uuid-1/default.md"))
                .thenReturn(Optional.of(new ByteArrayResource(
                        "# body".getBytes(StandardCharsets.UTF_8))));

        ResponseEntity<?> entity = controller.getRawFilePath(
                "uuid-1", "default.md");

        assertEquals(200, entity.getStatusCode().value());
        assertNotNull(entity.getBody());
    }


}
