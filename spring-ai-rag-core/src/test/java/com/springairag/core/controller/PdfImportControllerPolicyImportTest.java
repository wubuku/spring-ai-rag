package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.PdfToRagResponse;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.MarkdownRendererService;
import com.springairag.core.service.PdfImportService;
import com.springairag.core.service.PdfToRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PDF 导入到 RAG 的通用非 SSE 路径
 * （importPdfToRagWithPolicy）：全字段响应装配、collectionKey 解
 * 析到内部 ID、导入失败降级 500、空文件 400。
 */
class PdfImportControllerPolicyImportTest {

    private static final String ENTRY_MARKDOWN = "uuid-1/default.md";

    private PdfImportService pdfImportService;
    private PdfToRagService pdfToRagService;
    private CollectionIdentityResolver collectionIdentityResolver;
    private PdfImportController controller;

    @BeforeEach
    void setUp() {
        pdfImportService = mock(PdfImportService.class);
        PdfToRagService pdfToRagService = mock(PdfToRagService.class);
        this.pdfToRagService = pdfToRagService;
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        controller = new PdfImportController(
                pdfImportService,
                mock(MarkdownRendererService.class),
                pdfToRagService,
                collectionIdentityResolver);
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "PDF".getBytes());
    }

    private PdfImportService.PdfImportResult importResult() {
        return new PdfImportService.PdfImportResult(
                "uuid-1", ENTRY_MARKDOWN, 2, "test.pdf", "test.pdf");
    }

    @Test
    void withPolicyAsyncReturnsFullResponse() throws java.io.IOException {
        UUID jobId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        when(pdfImportService.importPdf(any(), isNull()))
                .thenReturn(importResult());
        when(pdfToRagService.importPdfToRag(
                eq(ENTRY_MARKDOWN), eq("test.pdf"), eq(7L),
                eq(EmbeddingPolicy.ASYNC), eq(false)))
                .thenReturn(new PdfToRagService.PdfToRagResult(
                        41L, "Doc A", true, "QUEUED", "ok", 12,
                        "ASYNC_QUEUED", jobId, batchId));

        ResponseEntity<Object> response = controller.importPdfToRagAsync(
                pdfFile(), 7L, null, null);

        assertEquals(200, response.getStatusCode().value());
        PdfToRagResponse body = (PdfToRagResponse) response.getBody();
        assertEquals(41L, body.documentId());
        assertEquals("Doc A", body.title());
        assertTrue(body.newlyCreated());
        assertEquals("QUEUED", body.embedStatus());
        assertEquals("ok", body.embedMessage());
        assertEquals(12, body.chunksCreated());
        assertEquals("uuid-1", body.uuid());
        assertEquals(ENTRY_MARKDOWN, body.entryMarkdown());
        assertEquals("ASYNC_QUEUED", body.embeddingAction());
        assertEquals(jobId, body.embeddingJobId());
        assertEquals(batchId, body.embeddingBatchId());
    }

    @Test
    void withPolicyResolvesCollectionKeyToInternalId() throws java.io.IOException {
        when(collectionIdentityResolver.resolveActiveIds(
                isNull(), eq(List.of("kb:records:v1"))))
                .thenReturn(List.of(10L));
        when(pdfImportService.importPdf(any(), isNull()))
                .thenReturn(importResult());
        when(pdfToRagService.importPdfToRag(
                any(), any(), eq(10L), eq(EmbeddingPolicy.ASYNC), eq(false)))
                .thenReturn(new PdfToRagService.PdfToRagResult(
                        41L, "Doc A", false, null, null, null));

        ResponseEntity<Object> response = controller.importPdfToRagAsync(
                pdfFile(), null, "kb:records:v1", null);

        assertEquals(200, response.getStatusCode().value());
        assertNull(((PdfToRagResponse) response.getBody()).embedStatus());
        // collectionId 参数为 null：以解析后的内部 ID 调用导入服务。
        verify(pdfToRagService).importPdfToRag(
                eq(ENTRY_MARKDOWN), eq("test.pdf"), eq(10L),
                eq(EmbeddingPolicy.ASYNC), eq(false));
    }

    @Test
    void withPolicyImportFailureReturnsInternalServerError() throws java.io.IOException {
        when(pdfImportService.importPdf(any(), isNull()))
                .thenThrow(new RuntimeException("marker single failed"));

        ResponseEntity<Object> response = controller.importPdfToRagAsync(
                pdfFile(), 7L, null, null);

        assertEquals(500, response.getStatusCode().value());
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertTrue(body.getDetail().contains("PDF-to-RAG import failed"));
        assertTrue(body.getDetail().contains("marker single failed"));
    }

    @Test
    void withPolicyEmptyFileReturnsBadRequest() {
        ResponseEntity<Object> response = controller.importPdfToRagAsync(
                new MockMultipartFile("file", new byte[0]), 7L, null, null);

        assertEquals(400, response.getStatusCode().value());
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertEquals("No file uploaded", body.getDetail());
        verify(pdfToRagService, org.mockito.Mockito.never())
                .importPdfToRag(any(String.class), any(String.class),
                        any(Long.class), any(EmbeddingPolicy.class),
                        org.mockito.ArgumentMatchers.anyBoolean());
    }
}
