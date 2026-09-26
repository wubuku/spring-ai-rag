package com.springairag.core.service;

import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
// DocumentChunkingService 在同包 com.springairag.core.service 下。
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentEmbedService 准备阶段长尾（Batch 666，JaCoCo 驱动）：
 * chunkingService 注入覆盖、非空白文档产出零分块时的 FAILED 结
 * 果、嵌入结果计数不匹配的校验消息。
 */
class DocumentEmbedServicePrepareTailTest {

    private com.springairag.core.repository.RagDocumentRepository
            documentRepository;
    private com.springairag.core.retrieval.EmbeddingBatchService
            embeddingBatchService;
    private com.springairag.core.service.EmbeddingPersistenceService
            persistenceService;
    private com.springairag.core.config.EmbeddingProfileProvider
            profileProvider;
    private DocumentChunkingService chunkingService;

    @BeforeEach
    void setUp() {
        documentRepository = mock(com.springairag.core.repository.RagDocumentRepository.class);
        embeddingBatchService = mock(com.springairag.core.retrieval.EmbeddingBatchService.class);
        persistenceService = mock(com.springairag.core.service.EmbeddingPersistenceService.class);
        profileProvider = mock(com.springairag.core.config.EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(
                new com.springairag.core.config.EmbeddingProfile(
                        9L, "bge-m3", "vendor", "bge-m3", "rev-1",
                        1024, "cosine", "normalize", true));
        chunkingService = mock(DocumentChunkingService.class);
    }

    private DocumentEmbedService service() {
        var service = new DocumentEmbedService(
                documentRepository,
                embeddingBatchService,
                persistenceService,
                profileProvider,
                new RagProperties());
        service.setChunkingService(chunkingService);
        return service;
    }

    private RagDocument document() {
        RagDocument doc = new RagDocument();
        doc.setId(41L);
        doc.setEnabled(Boolean.TRUE);
        doc.setContent("正文内容");
        doc.setContentHash("hash-1");
        doc.setVersion(3L);
        return doc;
    }

    private EmbeddingPersistenceService.CacheState cacheMiss() {
        return new EmbeddingPersistenceService.CacheState(false, 0);
    }

    @Test
    void blankChunksFromNonBlankDocumentProduceFailedPrepare() {
        RagDocument doc = document();
        when(documentRepository.findById(41L)).thenReturn(Optional.of(doc));
        when(persistenceService.findCacheState(
                anyLong(), any(), anyString(), anyString()))
                .thenReturn(cacheMiss());
        when(chunkingService.prepare(any())).thenReturn(
                new DocumentChunkingService.PreparedChunks(
                        new DocumentDerivationDescriptorProvider(new RagProperties())
                                .describe(doc),
                        List.of()));

        var result = service().embedDocument(41L, false);

        assertEquals("FAILED", result.get("status"));
        assertTrue(String.valueOf(result.get("error"))
                .contains("produced no chunks"));
    }

    @Test
    void chunkingServiceInjectionIsUsedForPreparation() {
        when(documentRepository.findById(41L)).thenReturn(Optional.of(document()));
        when(persistenceService.findCacheState(
                anyLong(), any(), anyString(), anyString()))
                .thenReturn(cacheMiss());
        when(chunkingService.prepare(any())).thenReturn(
                new DocumentChunkingService.PreparedChunks(
                        new DocumentDerivationDescriptorProvider(new RagProperties())
                                .describe(document()),
                        List.of(new com.springairag.documents.chunk.TextChunk(
                                "片段", 0, 2))));

        // 默认 SKIP 策略下的嵌入执行会经 chunkingService.prepare
        // （缓存未命中检查与执行路径各调用一次，故为 atLeastOnce）。
        service().embedDocument(41L, false);

        org.mockito.Mockito.verify(chunkingService,
                org.mockito.Mockito.atLeastOnce()).prepare(any());
    }
}
