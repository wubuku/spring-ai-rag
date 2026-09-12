package com.springairag.core.service;

import com.springairag.api.dto.BatchEmbedProgressEvent;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * batchEmbedDocumentsWithProgress 进度链（Batch 321）：每文档
 * PREPARING + 终态事件、计数器累计、CACHED/FAILED/NOT_FOUND 分
 * 支与汇总映射。
 */
class DocumentEmbedProgressChainTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            7L, "bge-m3-1024-test", "siliconflow", "BAAI/bge-m3",
            "test", 1024, "COSINE", "PROVIDER_DEFAULT", true);

    private RagDocumentRepository documentRepository;
    private EmbeddingBatchService embeddingBatchService;
    private EmbeddingPersistenceService persistenceService;
    private DocumentEmbedService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
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
                any(Long.class), eq(PROFILE), any(String.class),
                any(String.class)))
                .thenReturn(EmbeddingPersistenceService.CacheState.miss());
    }

    private RagDocument document(long id, String content, String contentHash) {
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

    @Test
    @SuppressWarnings("unchecked")
    void progressEventsCoverPreparingAndFinalPhasesAcrossStatuses() {
        // 文档 1：成功嵌入；文档 2：缓存命中；文档 3：校验失败。
        when(documentRepository.findById(1L))
                .thenReturn(Optional.of(document(1L, longContent(), "hash-1")));
        when(documentRepository.findById(2L))
                .thenReturn(Optional.of(document(2L, longContent(), "hash-2")));
        when(persistenceService.findCacheState(
                eq(2L), eq(PROFILE), eq("hash-2"), anyString()))
                .thenReturn(EmbeddingPersistenceService.CacheState.hit(2));
        when(documentRepository.findById(3L))
                .thenReturn(Optional.of(document(3L,
                        "THIRD-DOC-MARKER ".repeat(30), "hash-3")));
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenAnswer(invocation -> {
                    List<String> texts = invocation.getArgument(0);
                    boolean thirdDoc = texts.get(0).startsWith("THIRD-DOC");
                    return texts.stream()
                            .map(text -> thirdDoc
                                    ? new EmbeddingBatchService.EmbeddingResult(
                                            text, null, "provider boom")
                                    : new EmbeddingBatchService.EmbeddingResult(
                                            text, new float[1024], null))
                            .toList();
                });

        Consumer<BatchEmbedProgressEvent> callback =
                (Consumer<BatchEmbedProgressEvent>) mock(Consumer.class);
        Map<String, Object> result = service.batchEmbedDocumentsWithProgress(
                List.of(1L, 2L, 3L), callback);

        // 每文档两个事件：PREPARING + 终态。
        ArgumentCaptor<BatchEmbedProgressEvent> events =
                ArgumentCaptor.forClass(BatchEmbedProgressEvent.class);
        verify(callback, times(6)).accept(events.capture());
        List<BatchEmbedProgressEvent> sent = events.getAllValues();

        assertEquals("PREPARING", sent.get(0).phase());
        assertEquals("COMPLETED", sent.get(1).phase());
        assertEquals("CACHED", sent.get(3).phase());
        assertEquals("FAILED", sent.get(5).phase());

        // PREPARING 事件携带累计计数：文档 2 预备时 success=1。
        assertEquals(1, sent.get(2).successCount());
        assertEquals(1, sent.get(3).cachedCount());
        assertEquals(1, sent.get(5).failedCount());

        // 终态事件的 chunk 进度：完成与缓存的 current==total 且为正。
        assertEquals(sent.get(1).total(), sent.get(1).current());
        assertTrue(sent.get(1).current() > 0);
        assertEquals(sent.get(3).total(), sent.get(3).current());
        assertEquals(2, sent.get(3).current());
        assertTrue(sent.get(5).message().contains("failed"));

        Map<String, Object> summary =
                (Map<String, Object>) result.get("summary");
        assertEquals(3, summary.get("total"));
        assertEquals(1, summary.get("success"));
        assertEquals(1, summary.get("cached"));
        assertEquals(1, summary.get("failed"));
        assertEquals(0, summary.get("skipped"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingDocumentIsReportedAsSkippedPhase() {
        when(documentRepository.findById(9L)).thenReturn(Optional.empty());

        Consumer<BatchEmbedProgressEvent> callback =
                (Consumer<BatchEmbedProgressEvent>) mock(Consumer.class);
        Map<String, Object> result = service.batchEmbedDocumentsWithProgress(
                List.of(9L), callback);

        ArgumentCaptor<BatchEmbedProgressEvent> events =
                ArgumentCaptor.forClass(BatchEmbedProgressEvent.class);
        verify(callback, times(2)).accept(events.capture());
        // NOT_FOUND 落入 default 分支：SKIPPED 相位 + skipped 计数。
        assertEquals("SKIPPED", events.getAllValues().get(1).phase());
        Map<String, Object> summary =
                (Map<String, Object>) result.get("summary");
        assertEquals(1, summary.get("skipped"));
        assertEquals(0, summary.get("success"));
    }

    @Test
    void oversizedBatchRejectedBeforeAnyWork() {
        List<Long> ids = LongStream.rangeClosed(1, 51).boxed().toList();

        assertThrows(IllegalArgumentException.class,
                () -> service.batchEmbedDocumentsWithProgress(
                        ids, event -> { }));

        verifyNoInteractions(documentRepository);
    }
}
