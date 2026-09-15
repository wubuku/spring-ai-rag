package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentEmbedService.batchEmbedDocuments 汇总矩阵（Batch 425）：
 * null/超限门卫、CACHED 与 FAILED（含 NOT_FOUND→skipped）状态的
 * 汇总计数。
 */
class DocumentEmbedServiceBatchTailTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            7L, "bge-m3-1024-test", "siliconflow", "BAAI/bge-m3",
            "test", 1024, "COSINE", "PROVIDER_DEFAULT", true);

    private RagDocumentRepository documentRepository;
    private EmbeddingBatchService embeddingBatchService;
    private EmbeddingPersistenceService persistenceService;
    private DocumentEmbedService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingBatchService = mock(EmbeddingBatchService.class);
        persistenceService = mock(EmbeddingPersistenceService.class);
        service = new DocumentEmbedService(
                documentRepository,
                embeddingBatchService,
                persistenceService,
                () -> PROFILE,
                new RagProperties());
    }

    private void stubDocument(long id) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setContentHash("hash-" + id);
        document.setContent("body " + id);
        when(documentRepository.findById(id)).thenReturn(Optional.of(document));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(Map<String, Object> result) {
        return (Map<String, Object>) result.get("summary");
    }

    @Test
    void batchRejectsNullAndOversizedRequests() {
        assertThrows(IllegalArgumentException.class,
                () -> service.batchEmbedDocuments(null));
        var ids = new java.util.ArrayList<Long>();
        for (int i = 0; i < 51; i++) {
            ids.add((long) i);
        }
        assertThrows(IllegalArgumentException.class,
                () -> service.batchEmbedDocuments(ids));
    }

    @Test
    void batchCountsCachedAndNotFoundSkippedStates() {
        // 文档 11 命中缓存 → CACHED；文档 99 不存在 → NOT_FOUND → skipped。
        stubDocument(11L);
        when(persistenceService.findCacheState(
                eq(11L), eq(PROFILE), anyString(), anyString()))
                .thenReturn(EmbeddingPersistenceService.CacheState.hit(2));

        Map<String, Object> result = service.batchEmbedDocuments(List.of(11L, 99L));

        Map<String, Object> summary = summary(result);
        assertEquals(2, summary.get("total"));
        assertEquals(0, summary.get("success"));
        assertEquals(1, summary.get("cached"));
        assertEquals(0, summary.get("failed"));
        assertEquals(1, summary.get("skipped"));
    }

    @Test
    void batchCountsProviderFailureAsFailed() {
        stubDocument(12L);
        when(persistenceService.findCacheState(
                eq(12L), eq(PROFILE), anyString(), anyString()))
                .thenReturn(EmbeddingPersistenceService.CacheState.miss());
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenThrow(new IllegalStateException("provider down"));

        Map<String, Object> result = service.batchEmbedDocuments(List.of(12L));

        Map<String, Object> summary = summary(result);
        assertEquals(1, summary.get("total"));
        assertEquals(1, summary.get("failed"));

        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>)
                ((List<?>) result.get("results")).getFirst();
        assertEquals("FAILED", item.get("status"));
    }
}
