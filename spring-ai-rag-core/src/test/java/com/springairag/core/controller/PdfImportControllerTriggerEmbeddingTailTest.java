package com.springairag.core.controller;

import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.service.PdfToRagService;
import com.springairag.core.service.PdfImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PdfImportController 触发嵌入包装长尾（Batch 504，JaCoCo 驱
 * 动）：sync/sse 分支选择、IllegalArgumentException → 400、其余
 * 异常 → 500、以及 Sync 正常路径透出结果。
 */
class PdfImportControllerTriggerEmbeddingTailTest {

    private PdfToRagService pdfToRagService;
    private EmbeddingProfileProvider profileProvider;
    private PdfImportController controller;

    @BeforeEach
    void setUp() {
        pdfToRagService = mock(PdfToRagService.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                9L, "bge-m3", "vendor", "bge-m3", "rev-1",
                1024, "cosine", "normalize", true));
        controller = new PdfImportController(
                mock(PdfImportService.class),
                null,
                pdfToRagService);
    }

    private PdfToRagService.PdfToRagResult result() {
        return new PdfToRagService.PdfToRagResult(
                41L, "Manual", true, "COMPLETED", null, 12,
                "ASYNC_QUEUED", null, null);
    }

    @Test
    void syncEmbeddingParamRunsSyncPath() {
        when(pdfToRagService.triggerEmbedding(
                eq("uuid-1"), org.mockito.ArgumentMatchers.isNull(),
                eq(false)))
                .thenReturn(result());

        Object response = controller.triggerEmbedding(
                "uuid-1", null, "sync", false);

        assertTrue(response instanceof ResponseEntity);
        ResponseEntity<?> entity = (ResponseEntity<?>) response;
        assertEquals(200, entity.getStatusCode().value());
    }

    @Test
    void illegalArgumentFromServiceBecomes400() {
        when(pdfToRagService.triggerEmbedding(
                eq("uuid-1"), org.mockito.ArgumentMatchers.isNull(),
                eq(false)))
                .thenThrow(new IllegalArgumentException("unknown uuid"));

        Object response = controller.triggerEmbedding(
                "uuid-1", null, "sync", false);

        ResponseEntity<?> entity = (ResponseEntity<?>) response;
        assertEquals(400, entity.getStatusCode().value());
    }

    @Test
    void unexpectedExceptionBecomes500() {
        when(pdfToRagService.triggerEmbeddingWithProgress(
                eq("uuid-1"), any(), eq(false), any()))
                .thenThrow(new IllegalStateException("storage offline"));

        Object response = controller.triggerEmbedding(
                "uuid-1", null, "sync", false);

        ResponseEntity<?> entity = (ResponseEntity<?>) response;
        assertEquals(500, entity.getStatusCode().value());
    }

    @Test
    void sseEmbedParamReturnsEmitterResponse() {
        when(pdfToRagService.triggerEmbeddingWithProgress(
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyBoolean(), any()))
                .thenReturn(result());

        Object response = controller.triggerEmbedding(
                "uuid-1", null, "sse", false);

        assertTrue(response instanceof ResponseEntity);
        ResponseEntity<?> entity = (ResponseEntity<?>) response;
        assertEquals(200, entity.getStatusCode().value());
        assertTrue(entity.getBody() instanceof org.springframework.web.servlet.mvc.method.annotation.SseEmitter);
    }
}
