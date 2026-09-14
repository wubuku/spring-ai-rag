package com.springairag.core.controller;

import com.springairag.api.dto.ReembedMissingResponse;
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
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * reembedMissing 端点层（Batch 387 补充）：无缺失时空响应、有缺
 * 失时逐文档结果聚合（COMPLETED/QUEUED 计成功，其余计失败）。
 */
class RagDocumentControllerReembedEndpointTest {

    private RagDocumentRepository documentRepository;
    private DocumentEmbedService documentEmbedService;
    private EmbeddingProfileProvider profileProvider;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        var activeProfile = mock(com.springairag.core.config.EmbeddingProfile.class);
        when(activeProfile.id()).thenReturn(9L);
        when(profileProvider.getActiveProfile()).thenReturn(activeProfile);
        controller = new RagDocumentController(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                documentEmbedService,
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                profileProvider,
                mock(CollectionIdentityResolver.class),
                null);
    }

    private RagDocument document(Long id) {
        RagDocument value = new RagDocument();
        value.setId(id);
        value.setTitle("Doc " + id);
        return value;
    }

    @Test
    void reembedMissingReturnsEmptyResponseWithoutCandidates() {
        when(documentRepository.findDocumentsWithoutEmbeddings(9L))
                .thenReturn(List.of());

        ResponseEntity<ReembedMissingResponse> response =
                controller.reembedMissing(false, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().total());
        assertEquals(0, response.getBody().results().size());
    }

    @Test
    void reembedMissingAggregatesSuccessAndFailureCounts() {
        when(documentRepository.findDocumentsWithoutEmbeddings(9L))
                .thenReturn(List.of(document(1L), document(2L), document(3L)));
        when(documentEmbedService.embedDocument(any(Long.class), anyBoolean()))
                .thenReturn(Map.of("status", "COMPLETED", "chunksCreated", 2))
                .thenReturn(Map.of("status", "QUEUED", "message", "queued"))
                .thenThrow(new RuntimeException("embed down"));

        ResponseEntity<ReembedMissingResponse> response =
                controller.reembedMissing(true, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(3, response.getBody().total());
        assertEquals(2, response.getBody().success());
        assertEquals(1, response.getBody().failed());
        assertEquals("error", response.getBody().results().get(2).status());
    }

    @Test
    void reembedMissingForceTrueBypassesCache() {
        when(documentRepository.findDocumentsWithoutEmbeddings(9L))
                .thenReturn(List.of(document(1L)));
        when(documentEmbedService.embedDocument(1L, true))
                .thenReturn(Map.of("status", "COMPLETED", "chunksCreated", 5));

        ResponseEntity<ReembedMissingResponse> response =
                controller.reembedMissing(true, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().success());
    }
}
