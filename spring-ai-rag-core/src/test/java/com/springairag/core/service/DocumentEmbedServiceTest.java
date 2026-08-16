package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentEmbedServiceTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            7L,
            "bge-m3-1024-test",
            "siliconflow",
            "BAAI/bge-m3",
            "test",
            1024,
            "COSINE",
            "PROVIDER_DEFAULT",
            true);

    private RagDocumentRepository documentRepository;
    private EmbeddingBatchService embeddingBatchService;
    private EmbeddingPersistenceService persistenceService;
    private DocumentEmbedService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingBatchService = mock(EmbeddingBatchService.class);
        persistenceService = mock(EmbeddingPersistenceService.class);
        EmbeddingProfileProvider profileProvider = () -> PROFILE;
        service = new DocumentEmbedService(
                documentRepository,
                embeddingBatchService,
                persistenceService,
                profileProvider,
                new RagProperties());
        when(persistenceService.findCacheState(
                any(Long.class), eq(PROFILE), any(String.class), any(String.class)))
                .thenReturn(EmbeddingPersistenceService.CacheState.miss());
    }

    @Test
    void successfulEmbeddingCommitsOnlyAfterAllChunksValidate() {
        RagDocument document = document(1L, longContent(), "hash-1");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        mockSuccessfulEmbeddings();

        Map<String, Object> result = service.embedDocument(1L);

        assertEquals("COMPLETED", result.get("status"));
        assertEquals(PROFILE.profileKey(), result.get("embeddingProfileKey"));
        verify(persistenceService).replace(
                eq(1L),
                eq(0L),
                eq("hash-1"),
                eq(PROFILE),
                any(String.class),
                anyList(),
                anyList());
        verify(persistenceService, never()).recordFailureIfNoCompleted(
                any(Long.class), any(Long.class), any(String.class), any(),
                any(String.class), any(String.class));
    }

    @Test
    void partialFailureDoesNotReplaceExistingVectors() {
        RagDocument document = document(2L, longContent(), "hash-2");
        when(documentRepository.findById(2L)).thenReturn(Optional.of(document));
        when(embeddingBatchService.createEmbeddingsBatch(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream()
                    .map(text -> new EmbeddingBatchService.EmbeddingResult(
                            text, null, "provider apiKey=secret-value"))
                    .toList();
        });

        Map<String, Object> result = service.embedDocument(2L);

        assertEquals("FAILED", result.get("status"));
        assertFalse(((String) result.get("error")).contains("secret-value"));
        assertTrue(((String) result.get("error")).contains("***REDACTED***"));
        verify(persistenceService, never()).replace(
                any(Long.class), any(Long.class), any(String.class), any(),
                any(String.class), anyList(), anyList());
        verify(persistenceService).recordFailureIfNoCompleted(
                eq(2L), eq(0L), eq("hash-2"), eq(PROFILE),
                any(String.class), any(String.class));
    }

    @Test
    void dimensionMismatchIsRejectedBeforePersistence() {
        RagDocument document = document(3L, longContent(), "hash-3");
        when(documentRepository.findById(3L)).thenReturn(Optional.of(document));
        when(embeddingBatchService.createEmbeddingsBatch(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream()
                    .map(text -> new EmbeddingBatchService.EmbeddingResult(
                            text, new float[768], null))
                    .toList();
        });

        Map<String, Object> result = service.embedDocument(3L);

        assertEquals("FAILED", result.get("status"));
        assertTrue(((String) result.get("error")).contains("dimension mismatch"));
        verify(persistenceService, never()).replace(
                any(Long.class), any(Long.class), any(String.class), any(),
                any(String.class), anyList(), anyList());
    }

    @Test
    void cacheHitSkipsModelAndReturnsProfileMetadata() {
        RagDocument document = document(4L, longContent(), "hash-4");
        when(documentRepository.findById(4L)).thenReturn(Optional.of(document));
        when(persistenceService.findCacheState(
                eq(4L), eq(PROFILE), eq("hash-4"), any(String.class)))
                .thenReturn(EmbeddingPersistenceService.CacheState.hit(3));

        Map<String, Object> result = service.embedDocument(4L);

        assertEquals("CACHED", result.get("status"));
        assertEquals(3, result.get("embeddingsStored"));
        assertEquals(PROFILE.profileKey(), result.get("embeddingProfileKey"));
        verifyNoInteractions(embeddingBatchService);
    }

    @Test
    void forceEmbeddingBypassesCacheWithoutDeletingFirst() {
        RagDocument document = document(5L, longContent(), "hash-5");
        when(documentRepository.findById(5L)).thenReturn(Optional.of(document));
        when(persistenceService.findCacheState(
                eq(5L), eq(PROFILE), eq("hash-5"), any(String.class)))
                .thenReturn(EmbeddingPersistenceService.CacheState.hit(2));
        mockSuccessfulEmbeddings();

        Map<String, Object> result = service.embedDocument(5L, true);

        assertEquals("COMPLETED", result.get("status"));
        verify(persistenceService, never()).findCacheState(
                eq(5L), eq(PROFILE), any(String.class), any(String.class));
        verify(persistenceService).replace(
                eq(5L), eq(0L), eq("hash-5"), eq(PROFILE),
                any(String.class), anyList(), anyList());
    }

    @Test
    void missingContentHashIsPersistedBeforeRemoteEmbedding() {
        RagDocument document = document(6L, longContent(), null);
        when(documentRepository.findById(6L)).thenReturn(Optional.of(document));
        mockSuccessfulEmbeddings();

        service.embedDocument(6L);

        verify(persistenceService).ensureContentHash(
                eq(6L), eq(0L), any(String.class));
        verify(persistenceService).replace(
                eq(6L), eq(1L), any(String.class), eq(PROFILE),
                any(String.class), anyList(), anyList());
    }

    @Test
    void validatesInputAndKeepsBatchFailuresIsolated() {
        when(documentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class, () -> service.embedDocument(99L));
        assertThrows(NullPointerException.class, () -> service.embedDocument(null));
        assertThrows(IllegalArgumentException.class,
                () -> service.batchEmbedDocuments(null));

        Map<String, Object> batch = service.batchEmbedDocuments(List.of(99L));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) batch.get("summary");
        assertEquals(1, summary.get("skipped"));
    }

    private void mockSuccessfulEmbeddings() {
        when(embeddingBatchService.createEmbeddingsBatch(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream()
                    .map(text -> new EmbeddingBatchService.EmbeddingResult(
                            text, new float[1024], null))
                    .toList();
        });
    }

    private RagDocument document(Long id, String content, String contentHash) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setVersion(0L);
        document.setContent(content);
        document.setContentHash(contentHash);
        document.setEnabled(true);
        return document;
    }

    private String longContent() {
        return "Embedding profile integration content. ".repeat(30);
    }
}
