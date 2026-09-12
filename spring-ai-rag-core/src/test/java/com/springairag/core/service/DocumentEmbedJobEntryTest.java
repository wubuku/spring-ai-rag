package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * embedDocumentForJob（worker 专用入口，Batch 342）：自定义提交
 * 门在成功替换向量前生效、null 门拒绝、provider 调用失败与校验
 * 失败均不改写文档版本也不记录 provider 失败（由 job 状态机负
 * 责）、缓存命中不触发提交门。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentEmbedJobEntryTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            7L, "bge-m3-1024-test", "siliconflow", "BAAI/bge-m3",
            "test", 1024, "COSINE", "PROVIDER_DEFAULT", true);

    @Mock RagDocumentRepository documentRepository;
    @Mock EmbeddingBatchService embeddingBatchService;
    @Mock EmbeddingPersistenceService persistenceService;

    private DocumentEmbedService service;

    @BeforeEach
    void setUp() {
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
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
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

    private void mockSuccessfulEmbeddings() {
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenAnswer(invocation -> {
                    List<String> texts = invocation.getArgument(0);
                    return texts.stream()
                            .map(text -> new EmbeddingBatchService
                                    .EmbeddingResult(
                                    text, new float[1024], null))
                            .toList();
                });
    }

    @Test
    void jobEntryThreadsCustomCommitGateIntoPersistence() {
        when(documentRepository.findById(1L))
                .thenReturn(Optional.of(document(1L, longContent(), "hash-1")));
        mockSuccessfulEmbeddings();
        AtomicBoolean gateVerified = new AtomicBoolean(false);
        EmbeddingCommitGuard gate = () -> gateVerified.set(true);
        // 自定义提交门实例被透传到持久层（由持久层在事务内执行门校验）。
        org.mockito.Mockito.doAnswer(invocation -> {
                    ((EmbeddingCommitGuard) invocation.getArgument(7))
                            .verify();
                    return null;
                })
                .when(persistenceService).replace(
                        eq(1L), eq(0L), eq("hash-1"), eq(PROFILE),
                        any(String.class), anyList(), anyList(),
                        org.mockito.ArgumentMatchers.same(gate));

        Map<String, Object> result = service.embedDocumentForJob(
                1L, false, gate);

        assertEquals("COMPLETED", result.get("status"));
        assertTrue(gateVerified.get(), "提交门应在持久层事务内被校验");
        org.mockito.Mockito.verify(persistenceService).replace(
                eq(1L), eq(0L), eq("hash-1"), eq(PROFILE),
                any(String.class), anyList(), anyList(),
                org.mockito.ArgumentMatchers.same(gate));
    }

    @Test
    void jobEntryRejectsNullCommitGuard() {
        assertThrows(NullPointerException.class,
                () -> service.embedDocumentForJob(1L, false, null));
    }

    @Test
    void jobEntryProviderCallFailureSkipsFailureRecording() {
        when(documentRepository.findById(2L))
                .thenReturn(Optional.of(document(2L, longContent(), "hash-2")));
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenThrow(new IllegalStateException("provider offline"));

        Map<String, Object> result = service.embedDocumentForJob(
                2L, false, () -> {
                });

        // worker 语义：provider 失败由 job 状态机记录，这里不落失败快照。
        assertEquals("FAILED", result.get("status"));
        verify(persistenceService, never()).recordFailureIfNoCompleted(
                anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString());
        verify(persistenceService, never()).replace(
                any(Long.class), any(Long.class), any(String.class),
                any(), any(String.class), anyList(), anyList(),
                any(EmbeddingCommitGuard.class));
    }

    @Test
    void jobEntryValidationFailureSkipsFailureRecordingAndRedacts() {
        when(documentRepository.findById(3L))
                .thenReturn(Optional.of(document(3L, longContent(), "hash-3")));
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenAnswer(invocation -> {
                    List<String> texts = invocation.getArgument(0);
                    return texts.stream()
                            .map(text -> new EmbeddingBatchService
                                    .EmbeddingResult(
                                    text, null,
                                    "provider apiKey=secret-value"))
                            .toList();
                });

        Map<String, Object> result = service.embedDocumentForJob(
                3L, false, () -> { });

        assertEquals("FAILED", result.get("status"));
        String error = (String) result.get("error");
        assertNotNull(error);
        assertFalse(error.contains("secret-value"));
        assertTrue(error.contains("***REDACTED***"));
        verify(persistenceService, never()).recordFailureIfNoCompleted(
                anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString());
    }

    @Test
    void jobEntryCacheHitSkipsCommitGate() {
        when(documentRepository.findById(4L))
                .thenReturn(Optional.of(document(4L, longContent(), "hash-4")));
        when(persistenceService.findCacheState(
                eq(4L), eq(PROFILE), eq("hash-4"), any(String.class)))
                .thenReturn(EmbeddingPersistenceService.CacheState.hit(3));
        AtomicBoolean gateVerified = new AtomicBoolean(false);

        Map<String, Object> result = service.embedDocumentForJob(
                4L, false, () -> gateVerified.set(true));

        assertEquals("CACHED", result.get("status"));
        assertEquals(3, result.get("embeddingsStored"));
        // 缓存命中不替换向量：提交门不应被触发。
        assertFalse(gateVerified.get());
        verify(persistenceService, never()).replace(
                any(Long.class), any(Long.class), any(String.class),
                any(), any(String.class), anyList(), anyList(),
                any(EmbeddingCommitGuard.class));
    }
}
