package com.springairag.core.controller;

import com.springairag.core.entity.FsFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * inferContentType 扩展名映射矩阵（Batch 323）：存储 MIME 优先
 * 于扩展名推断、application/octet-stream 触发扩展名回退、各扩展
 * 名逐一映射、未知扩展回退 octet-stream。
 */
class PdfImportControllerContentTypeTest {

    private com.springairag.core.service.PdfImportService pdfImportService;
    private PdfImportController controller;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        pdfImportService = mock(com.springairag.core.service.PdfImportService.class);
        controller = new PdfImportController(
                pdfImportService,
                mock(com.springairag.core.service.MarkdownRendererService.class),
                mock(com.springairag.core.service.PdfToRagService.class));
    }

    private void stubFile(String path, String storedMime) {
        FsFile file = new FsFile();
        file.setPath(path);
        file.setMimeType(storedMime);
        when(pdfImportService.getFile(path)).thenReturn(Optional.of(file));
        when(pdfImportService.loadFileAsResource(path))
                .thenReturn(Optional.of(new ByteArrayResource(new byte[] {1})));
    }

    private String downloadContentType(String storedMime, String filename) {
        String path = "uuid-1/" + filename;
        stubFile(path, storedMime);

        ResponseEntity<org.springframework.core.io.Resource> response =
                controller.getRawFilePath("uuid-1", filename);

        assertEquals(200, response.getStatusCode().value());
        return response.getHeaders().getContentType().toString();
    }

    @Test
    void storedMimeTypeWinsWhenSpecific() {
        // 具体 MIME（非 octet-stream）直接采用，不受扩展名影响。
        assertEquals("text/custom-vendor",
                downloadContentType("text/custom-vendor", "file.unknownext"));
        assertEquals("application/pdf",
                downloadContentType("application/pdf", "file.pdf"));
    }

    @Test
    void everyKnownExtensionMapsToItsContentType() {
        Map<String, String> expectations = new java.util.LinkedHashMap<>();
        expectations.put("a.png", "image/png");
        expectations.put("a.jpg", "image/jpeg");
        expectations.put("a.jpeg", "image/jpeg");
        expectations.put("a.gif", "image/gif");
        expectations.put("a.webp", "image/webp");
        expectations.put("a.svg", "image/svg+xml");
        expectations.put("a.pdf", "application/pdf");
        expectations.put("a.md", "text/markdown");
        expectations.put("a.markdown", "text/markdown");
        expectations.put("a.txt", "text/plain");
        expectations.put("a.json", "application/json");
        expectations.put("a.css", "text/css");
        expectations.put("a.js", "application/javascript");

        expectations.forEach((filename, expected) ->
                assertEquals(expected,
                        downloadContentType("application/octet-stream", filename),
                        filename + " 应映射为 " + expected));
    }

    @Test
    void uppercaseExtensionAndUnknownFallback() {
        // 大写扩展名同样识别（内部 toLowerCase）。
        assertEquals("image/png",
                downloadContentType("application/octet-stream", "PHOTO.PNG"));
        // 未知扩展与无扩展均回退 octet-stream。
        assertEquals("application/octet-stream",
                downloadContentType("application/octet-stream", "a.weird"));
        assertEquals("application/octet-stream",
                downloadContentType("application/octet-stream", "noextension"));

        // null MIME 也走扩展名回退。
        String path = "uuid-1/doc.md";
        FsFile file = new FsFile();
        file.setPath(path);
        file.setMimeType(null);
        when(pdfImportService.getFile(path)).thenReturn(Optional.of(file));
        when(pdfImportService.loadFileAsResource(path))
                .thenReturn(Optional.of(new ByteArrayResource(new byte[] {1})));

        ResponseEntity<org.springframework.core.io.Resource> response =
                controller.getRawFilePath("uuid-1", "doc.md");

        assertEquals("text/markdown",
                response.getHeaders().getContentType().toString());
    }
}
