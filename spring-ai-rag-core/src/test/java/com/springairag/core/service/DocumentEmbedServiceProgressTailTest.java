package com.springairag.core.service;

import com.springairag.api.dto.EmbedProgressEvent;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentEmbedService 进度嵌入长尾（Batch 440）：空内容守卫、
 * 缓存命中时直接发出 COMPLETED 进度事件且不触发 provider。
 */
class DocumentEmbedServiceProgressTailTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            7L, "bge-m3-1024-test", "siliconflow", "BAAI/bge-m3",
            "test", 1024, "COSINE", "PROVIDER_DEFAULT", true);

    private EmbeddingPersistenceService persistenceService;
    private EmbeddingBatchService embeddingBatchService;
    private RagDocumentRepository documentRepository;
    private DocumentEmbedService service;

    @BeforeEach
    void setUp() {
        persistenceService = mock(EmbeddingPersistenceService.class);
        embeddingBatchService = mock(EmbeddingBatchService.class);
        documentRepository = mock(RagDocumentRepository.class);
        service = new DocumentEmbedService(
                documentRepository,
                embeddingBatchService,
                persistenceService,
                () -> PROFILE,
                new RagProperties());
    }

    @Test
    void emptyContentIsRejectedBeforeCacheLookup() {
        RagDocument empty = new RagDocument();
        empty.setId(41L);
        empty.setContentHash("hash-1");
        empty.setContent("  ");
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(empty));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.embedDocumentWithProgress(41L, false, null));
        assertEquals("Document content is empty: documentId=41",
                error.getMessage());
        // 守卫先于缓存查询。
        org.mockito.Mockito.verify(persistenceService,
                org.mockito.Mockito.never()).findCacheState(
                anyLong(), eq(PROFILE), anyString(), anyString());
    }

    @Test
    void cachedDocumentEmitsCompletedEventWithoutProviderCall() {
        RagDocument document = new RagDocument();
        document.setId(41L);
        document.setContentHash("hash-1");
        document.setContent("body");
        when(((RagDocumentRepository) mock(RagDocumentRepository.class))
                .findById(41L)).thenReturn(Optional.of(document));

        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document));
        when(persistenceService.findCacheState(
                eq(41L), eq(PROFILE), anyString(), anyString()))
                .thenReturn(EmbeddingPersistenceService.CacheState.hit(2));
        List<EmbedProgressEvent> events = new ArrayList<>();
        var result = service.embedDocumentWithProgress(41L, false, events::add);

        assertEquals("CACHED", result.get("status"));
        // PREPARING + COMPLETED 两个进度事件。
        assertEquals(2, events.size());
        assertEquals("PREPARING", events.get(0).phase());
        assertEquals("COMPLETED", events.get(1).phase());
    }
}
