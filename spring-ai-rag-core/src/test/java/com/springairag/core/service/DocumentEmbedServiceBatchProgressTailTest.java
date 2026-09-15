package com.springairag.core.service;

import com.springairag.api.dto.BatchEmbedProgressEvent;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentEmbedService.batchEmbedDocumentsWithProgress 长尾（Batch
 * 438）：超限门卫、逐文档进度事件透出、summary 计数与状态归类。
 */
class DocumentEmbedServiceBatchProgressTailTest {

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

    private void stubCached(long id) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setContentHash("hash-" + id);
        document.setContent("body " + id);
        when(documentRepository.findById(id)).thenReturn(Optional.of(document));
        when(persistenceService.findCacheState(
                eq(id), eq(PROFILE), anyString(), anyString()))
                .thenReturn(EmbeddingPersistenceService.CacheState.hit(2));
    }

    @Test
    void batchWithProgressRejectsOversizedRequests() {
        var ids = new java.util.ArrayList<Long>();
        for (int i = 0; i < 51; i++) {
            ids.add((long) i);
        }
        assertThrows(IllegalArgumentException.class,
                () -> service.batchEmbedDocumentsWithProgress(ids, event -> { }));
    }

    @Test
    void progressCallbackReceivesPerDocumentEvents() {
        stubCached(11L);
        stubCached(12L);
        List<BatchEmbedProgressEvent> events = new ArrayList<>();

        var result = service.batchEmbedDocumentsWithProgress(
                List.of(11L, 12L),
                events::add);

        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(2, summary.get("total"));
        assertEquals(2, summary.get("cached"));
        assertEquals(0, summary.get("failed"));
        assertEquals(2, ((List<?>) result.get("results")).size());
        // 每个文档两个事件：PREPARING + 终态（CACHED）。
        assertEquals(4, events.size());
    }
}
