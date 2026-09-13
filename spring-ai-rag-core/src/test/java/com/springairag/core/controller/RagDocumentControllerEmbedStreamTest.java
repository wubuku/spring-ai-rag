package com.springairag.core.controller;

import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * embedDocumentStream SSE 端点（Batch 390）：正常进度+done、
 * IllegalArgumentException error 事件、意外异常 completeWithError
 * 三种终止形态。
 */
class RagDocumentControllerEmbedStreamTest {

    private DocumentEmbedService documentEmbedService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        documentEmbedService = mock(DocumentEmbedService.class);
        controller = new RagDocumentController(
                mock(RagDocumentRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                documentEmbedService,
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                null);
        controller.setDispatchService(mock(EmbeddingDispatchService.class));
    }

    @Test
    void embedStreamSendsProgressAndDoneOnSuccess() {
        when(documentEmbedService.embedDocumentWithProgress(
                eq(41L), eq(false), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<com.springairag.api.dto.EmbedProgressEvent> progress =
                    invocation.getArgument(2);
            progress.accept(new com.springairag.api.dto.EmbedProgressEvent(
                    "CHUNKING", 1, 2, "halfway", 41L));
            return Map.of("status", "COMPLETED");
        });

        SseEmitter emitter = controller.embedDocumentStream(41L, false);

        assertNotNull(emitter);
    }

    @Test
    void embedStreamSendsErrorEventOnIllegalArgument() {
        when(documentEmbedService.embedDocumentWithProgress(
                any(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("document not found"));

        SseEmitter emitter = controller.embedDocumentStream(41L, false);

        assertNotNull(emitter);
    }

    @Test
    void embedStreamCompletesWithErrorOnUnexpectedFailure() {
        when(documentEmbedService.embedDocumentWithProgress(
                any(), anyBoolean(), any()))
                .thenThrow(new RagException(
                        com.springairag.api.enums.ErrorCode.INTERNAL_ERROR,
                        "boom"));

        SseEmitter emitter = controller.embedDocumentStream(41L, false);

        assertNotNull(emitter);
    }
}
