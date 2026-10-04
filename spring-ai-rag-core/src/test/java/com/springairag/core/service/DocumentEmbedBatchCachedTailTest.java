package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.service.EmbeddingPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批量嵌入 CACHED 分支长尾（Batch 743，JaCoCo 驱动）：
 * batchEmbedDocuments 对缓存命中文档计入 cached 计数（264）。
 */
class DocumentEmbedBatchCachedTailTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            7L, "bge-m3-1024-test", "siliconflow", "BAAI/bge-m3",
            "test", 1024, "COSINE", "PROVIDER_DEFAULT", true);

    private com.springairag.core.repository.RagDocumentRepository documentRepository;
    private EmbeddingPersistenceService persistenceService;
    private DocumentEmbedService service;

    @BeforeEach
    void setUp() {
        documentRepository =
                mock(com.springairag.core.repository.RagDocumentRepository.class);
        persistenceService = mock(EmbeddingPersistenceService.class);
        com.springairag.core.config.EmbeddingProfileProvider profileProvider =
                () -> PROFILE;
        service = new DocumentEmbedService(
                documentRepository,
                mock(com.springairag.core.retrieval.EmbeddingBatchService.class),
                persistenceService,
                profileProvider,
                new com.springairag.core.config.RagProperties(),
                new DocumentChunkingService(
                        new com.springairag.core.config.RagProperties(), new DocumentDerivationDescriptorProvider(new com.springairag.core.config.RagProperties())));
        org.mockito.Mockito.when(documentRepository.findById(2L))
                .thenReturn(Optional.of(document(2L, "cached body", "hash-c")));
        // 先注册宽匹配 miss，再注册特定命中：Mockito 以最后匹配的桩为准。
        when(persistenceService.findCacheState(any(Long.class), any(), eq(PROFILE), any(String.class)))
                .thenReturn(EmbeddingPersistenceService.CacheState.miss());
        when(persistenceService.findCacheState(eq(2L), any(), eq(PROFILE), eq("hash-c")))
                .thenReturn(EmbeddingPersistenceService.CacheState.hit(2));
    }

    private com.springairag.core.entity.RagDocument document(
            long id, String content, String hash) {
        com.springairag.core.entity.RagDocument document =
                new com.springairag.core.entity.RagDocument();
        document.setId(id);
        document.setVersion(0L);
        document.setContent(content);
        document.setContentHash(hash);
        document.setEnabled(true);
        return document;
    }

    @Test
    void batchEmbedCountsCachedDocuments() {
        Map<String, Object> result = service.batchEmbedDocuments(
                List.of(2L));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, ((Number) summary.get("cached")).intValue());
        assertEquals(0, ((Number) summary.get("success")).intValue());
        assertEquals(1, ((List<?>) result.get("results")).size());
        assertEquals("CACHED",
                ((Map<?, ?>) ((List<?>) result.get("results")).get(0))
                        .get("status"));
    }
}
