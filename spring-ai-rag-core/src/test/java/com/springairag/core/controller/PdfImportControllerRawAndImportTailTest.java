package com.springairag.core.controller;

import com.springairag.core.service.PdfImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PdfImportController 长尾补充（Batch 699，JaCoCo 驱动）：raw 文
 * 件记录存在但磁盘资源缺失时的 500、PDF-to-RAG 启动链对
 * IAE/SE 原样重抛与对未知异常的 ISE 包装。
 */
class PdfImportControllerRawAndImportTailTest {

    private PdfImportService pdfImportService;
    private PdfImportController controller;

    @BeforeEach
    void setUp() {
        pdfImportService = mock(PdfImportService.class);
        controller = new PdfImportController(
                pdfImportService,
                null,
                mock(com.springairag.core.service.PdfToRagService.class), null);
    }

    @Test
    void getRawFileReturns500WhenStoredResourceMissing() {
        com.springairag.core.entity.FsFile fsFile =
                mock(com.springairag.core.entity.FsFile.class);
        when(fsFile.getMimeType()).thenReturn("text/markdown");
        when(pdfImportService.getFile("uuid-9/default.md"))
                .thenReturn(Optional.of(fsFile));
        when(pdfImportService.loadFileAsResource("uuid-9/default.md"))
                .thenReturn(Optional.empty());

        ResponseEntity<Resource> response =
                controller.getRawFile("uuid-9/default.md");

        assertEquals(500, response.getStatusCode().value());
    }

    private ResponseEntity<SseEmitter> startEmbedding()
            throws Throwable {
        Method method = PdfImportController.class.getDeclaredMethod(
                "startPdfToRagEmbedding",
                org.springframework.web.multipart.MultipartFile.class,
                Long.class,
                String.class);
        method.setAccessible(true);
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf",
                "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8));
        try {
            return (ResponseEntity<SseEmitter>) method.invoke(
                    controller, file, null, null);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void startPdfToRagEmbeddingRethrowsSecurityException() throws Exception {
        when(pdfImportService.importPdf(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new SecurityException("collection denied"));

        assertThrows(SecurityException.class, this::startEmbedding);
    }

    @Test
    void startPdfToRagEmbeddingWrapsUnexpectedFailures() throws Exception {
        when(pdfImportService.importPdf(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new IllegalStateException("converter offline"));

        var error = assertThrows(IllegalStateException.class,
                this::startEmbedding);
        assertTrue(error.getMessage().contains("PDF-to-RAG import failed"));
    }
}
