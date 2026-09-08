package com.springairag.core.controller;

import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.exception.RagException;
import com.springairag.core.service.PdfImportService;
import com.springairag.core.service.MarkdownRendererService;
import com.springairag.core.service.PdfToRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 触发嵌入的策略分派语义：ASYNC 端点拒绝 embed=sse 组合、
 * ASYNC/SKIP 策略委派与拒绝、同步端点的非 SYNC 策略转发、
 * SSE 端点的空白 UUID 防护与默认变体委托。
 */
class PdfImportEmbedPolicyTest {

    private PdfImportService pdfImportService;
    private MarkdownRendererService markdownRendererService;
    private PdfToRagService pdfToRagService;
    private PdfImportController controller;

    @BeforeEach
    void setUp() {
        pdfImportService = mock(PdfImportService.class);
        markdownRendererService = mock(MarkdownRendererService.class);
        pdfToRagService = mock(PdfToRagService.class);
        controller = new PdfImportController(
                pdfImportService, markdownRendererService, pdfToRagService);
    }

    private PdfToRagService.PdfToRagResult fullResult() {
        return new PdfToRagService.PdfToRagResult(
                42L, "Doc", true, "QUEUED", "dispatched", 9,
                "ASYNC_QUEUED", UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void asyncEndpointRejectsEmbedSseCombination() {
        RagException error = assertThrows(RagException.class,
                () -> controller.triggerEmbeddingAsync(
                        "uuid-1", null, null, "sse", false));
        assertEquals(RagException.class.getName(), error.getClass().getName());
    }

    @Test
    void asyncEndpointDelegatesToAsyncPolicyJob() {
        when(pdfToRagService.triggerEmbedding(
                eq("uuid-1"), eq(10L),
                eq(EmbeddingPolicy.ASYNC), eq(false)))
                .thenReturn(fullResult());

        ResponseEntity<Object> response = controller.triggerEmbeddingAsync(
                "uuid-1", 10L, null, "json", false);

        assertEquals(200, response.getStatusCode().value());
        assertInstanceOf(com.springairag.api.dto.PdfToRagResponse.class,
                response.getBody());
    }

    @Test
    void syncEndpointForwardsNonSyncPolicyToPolicyDispatch() {
        when(pdfToRagService.triggerEmbedding(
                eq("uuid-2"), org.mockito.ArgumentMatchers.isNull(),
                eq(EmbeddingPolicy.ASYNC), eq(false)))
                .thenReturn(fullResult());

        // embed=sync + embeddingPolicy=ASYNC → 转发到策略分派。
        ResponseEntity<Object> response = controller.triggerEmbeddingSync(
                "uuid-2", null, null, false, EmbeddingPolicy.ASYNC);

        assertEquals(200, response.getStatusCode().value());
        verifyPolicyDispatch("uuid-2");
    }

    @Test
    void policyDispatchRejectsSkipPolicyExplicitly() {
        RagException error = assertThrows(RagException.class,
                () -> controller.triggerEmbeddingSync(
                        "uuid-3", null, null, false, EmbeddingPolicy.SKIP));
        assertEquals(RagException.class.getName(), error.getClass().getName());
    }

    @Test
    void sseEndpointRejectsBlankUuid() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.triggerEmbeddingSse("  ", null, false));
    }

    @Test
    void sseDefaultVariantDelegatesWithCollectionId() {
        when(pdfToRagService.triggerEmbedding(
                any(), any(), anyBoolean()))
                .thenReturn(fullResult());

        ResponseEntity<SseEmitter> response =
                controller.triggerEmbeddingSseDefault(
                        "uuid-4", 10L, false);

        assertNotNull(response);
        assertNotNull(response.getBody());
    }

    @Test
    void getFileLookupsFallBackBetweenMarkdownVariants() {
        // previewHtmlFragment 的双查找：default.md 缺失时回退到 .pdf→.md 替换。
        when(pdfImportService.getFile("/uuid-9/default.md"))
                .thenReturn(Optional.empty());
        com.springairag.core.entity.FsFile markdown =
                new com.springairag.core.entity.FsFile();
        markdown.setPath("/uuid-9/paper.md");
        markdown.setMimeType("text/markdown");
        when(pdfImportService.getFile("/uuid-9/paper.md"))
                .thenReturn(Optional.of(markdown));
        when(markdownRendererService.renderToHtml(any()))
                .thenReturn("<p>hi</p>");

        ResponseEntity<String> response =
                controller.previewHtmlFragment("/uuid-9/paper.pdf");

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("<p>hi</p>"));
    }

    private void verifyPolicyDispatch(String uuid) {
        org.mockito.Mockito.verify(pdfToRagService).triggerEmbedding(
                eq(uuid), org.mockito.ArgumentMatchers.isNull(),
                eq(EmbeddingPolicy.ASYNC), eq(false));
    }
}
