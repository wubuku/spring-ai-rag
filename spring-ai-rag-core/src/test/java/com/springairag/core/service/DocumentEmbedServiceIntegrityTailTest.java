package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentEmbedService 完整性仓库委托长尾（Batch 439）：
 * hasFreshEmbedding 在注入 DerivationIntegrityRepository 后以
 * snapshot.vectorFresh() 为准，且不再走缓存判定。
 */
class DocumentEmbedServiceIntegrityTailTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            7L, "bge-m3-1024-test", "siliconflow", "BAAI/bge-m3",
            "test", 1024, "COSINE", "PROVIDER_DEFAULT", true);

    private DerivationIntegrityRepository integrityRepository;
    private EmbeddingPersistenceService persistenceService;
    private DocumentEmbedService service;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        integrityRepository = mock(DerivationIntegrityRepository.class);
        persistenceService = mock(EmbeddingPersistenceService.class);
        service = new DocumentEmbedService(
                mock(RagDocumentRepository.class),
                mock(com.springairag.core.retrieval.EmbeddingBatchService.class),
                persistenceService,
                () -> PROFILE,
                new RagProperties());
        service.setIntegrityRepository(integrityRepository);

        document = new RagDocument();
        document.setId(41L);
        document.setContentHash("hash-1");
        document.setContent("body");
        document.setDocumentType("TEXT");
        when(integrityRepository.inspect(any(RagDocument.class)))
                .thenReturn(snapshot(true));
    }

    private DerivationIntegrityRepository.Snapshot snapshot(boolean vectorFresh) {
        return new DerivationIntegrityRepository.Snapshot(
                41L, "Doc", 1L, 1L, "hash-1", true, false, null, null,
                "hierarchical-v2", "READY", "hash-1", "hierarchical-v2",
                1L, 2, 2, null, true, false,
                "COMPLETED", "hash-1", "hierarchical-v2", 1L, 2, 2,
                null, UUID.randomUUID(), "COMPLETED",
                vectorFresh, false,
                vectorFresh ? "READY" : "INDEXING",
                "READY", vectorFresh ? "READY" : "INDEXING",
                null);
    }

    @Test
    void integrityRepositoryVectorFreshShortCircuitsCachePath() {
        assertTrue(service.hasFreshEmbedding(document));
        // 缓存路径不再被询问（即使缓存说 miss 也不影响结果）。
        verify(persistenceService, org.mockito.Mockito.never())
                .findCacheState(anyLong(), any(), any(), any());
    }

    @Test
    void integrityRepositoryStaleVectorReportsNotFresh() {
        when(integrityRepository.inspect(any(RagDocument.class)))
                .thenReturn(snapshot(false));
        assertFalse(service.hasFreshEmbedding(document));
    }
}
