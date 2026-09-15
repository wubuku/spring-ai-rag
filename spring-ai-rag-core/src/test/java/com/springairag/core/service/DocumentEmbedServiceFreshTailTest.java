package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentEmbedService.hasFreshEmbedding 长尾（Batch 423）：
 * 入参守卫、关键词索引新鲜度短路、缓存命中/未命中判定。
 */
class DocumentEmbedServiceFreshTailTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            7L, "bge-m3-1024-test", "siliconflow", "BAAI/bge-m3",
            "test", 1024, "COSINE", "PROVIDER_DEFAULT", true);

    private EmbeddingPersistenceService persistenceService;
    private KeywordIndexPersistenceService keywordIndexPersistenceService;
    private DocumentEmbedService service;

    @BeforeEach
    void setUp() {
        persistenceService = mock(EmbeddingPersistenceService.class);
        keywordIndexPersistenceService = mock(KeywordIndexPersistenceService.class);
        service = new DocumentEmbedService(
                mock(RagDocumentRepository.class),
                mock(EmbeddingBatchService.class),
                persistenceService,
                () -> PROFILE,
                new RagProperties());
        when(persistenceService.findCacheState(
                any(Long.class), eq(PROFILE), any(String.class), any(String.class)))
                .thenReturn(EmbeddingPersistenceService.CacheState.miss());
    }

    private RagDocument document(String contentHash) {
        RagDocument document = new RagDocument();
        document.setId(41L);
        document.setContentHash(contentHash);
        // buildChunkerVersion 需要非空 content 才能派生分块器版本。
        document.setContent("body text");
        return document;
    }

    @Test
    void invalidInputsAreNeverFresh() {
        assertFalse(service.hasFreshEmbedding(null));
        assertFalse(service.hasFreshEmbedding(new RagDocument()));
        assertFalse(service.hasFreshEmbedding(document(null)));
        assertFalse(service.hasFreshEmbedding(document("   ")));
    }

    @Test
    void keywordIndexStalenessShortCircuitsBeforeCache() {
        service.setKeywordIndexPersistenceService(keywordIndexPersistenceService);
        when(keywordIndexPersistenceService.hasFreshLocalIndex(
                any(RagDocument.class))).thenReturn(false);
        when(persistenceService.findCacheState(
                anyLong(), eq(PROFILE), anyString(), anyString()))
                .thenReturn(EmbeddingPersistenceService.CacheState.hit(3));

        // 关键词索引过期 → 直接判不新鲜，不再查缓存。
        assertFalse(service.hasFreshEmbedding(document("hash-1")));
    }

    @Test
    void cacheHitMakesEmbeddingFreshAndMissKeepsItStale() {
        when(persistenceService.findCacheState(
                any(Long.class), eq(PROFILE), any(String.class), any(String.class)))
                .thenReturn(EmbeddingPersistenceService.CacheState.hit(2));
        assertTrue(service.hasFreshEmbedding(document("hash-1")));

        when(persistenceService.findCacheState(
                any(Long.class), eq(PROFILE), any(String.class), any(String.class)))
                .thenReturn(EmbeddingPersistenceService.CacheState.miss());
        assertFalse(service.hasFreshEmbedding(document("hash-1")));
    }
}
