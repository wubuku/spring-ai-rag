package com.springairag.core.embeddingjob;

import com.springairag.api.dto.EmbeddingJobCreateRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.DocumentEmbedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmbeddingJobService 创建与重试长尾（Batch 599，JaCoCo 驱动）：
 * null 请求体、作用域与 documentIds 二选一、ID 清单校验、禁用或
 * 无有效哈希文档拒绝、QUEUED 任务触发唤醒发布、readiness 对未授权
 * 集合拒绝、retry 省略 maxAttempts 时沿用当前值且活动任务冲突时
 * 拒绝、非 QUEUED 目标不发布唤醒。
 */
class EmbeddingJobServiceCreateRetryTailTest {

    private static final UUID JOB_ID = UUID.randomUUID();

    private EmbeddingJobRepository jobRepository;
    private RagDocumentRepository documentRepository;
    private CollectionRetrievalScopeResolver scopeResolver;
    private EmbeddingProfileProvider profileProvider;
    private DocumentEmbedService documentEmbedService;
    private EmbeddingJobWakeupPublisher wakeupPublisher;
    private RagProperties properties;
    private EmbeddingJobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(EmbeddingJobRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        wakeupPublisher = mock(EmbeddingJobWakeupPublisher.class);
        properties = new RagProperties();
        properties.getEmbeddingJobs().setEnabled(true);
        properties.getEmbeddingJobs().setMaxDocumentsPerBatch(10);

        service = new EmbeddingJobService(
                jobRepository,
                documentRepository,
                scopeResolver,
                profileProvider,
                properties,
                new DocumentDerivationDescriptorProvider(properties),
                documentEmbedService);
        service.setWakeupPublisher(wakeupPublisher);

        when(profileProvider.getActiveProfile()).thenReturn(profile());
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                9L, "profile", "test", "model", "v1",
                1024, "COSINE", "NONE", true);
    }

    private RagDocument document(Long id, boolean enabled, String hash) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setEnabled(enabled);
        document.setContentHash(hash);
        return document;
    }

    private EmbeddingJob job(EmbeddingJobStatus status) {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        return new EmbeddingJob(
                JOB_ID, UUID.randomUUID(), 42L, 9L, false,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                1L, status, 2, 5,
                now, null, null, null, null, now,
                null, null, now, "EXTERNAL", "principal-1",
                1L, "TEXT", "chunker-v1");
    }

    @Test
    void createRejectsNullRequestBody() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> service.create(null));
        assertEquals("request body is required", error.getMessage());
    }

    @Test
    void createRejectsBothAndNeitherScopeSources() {
        IllegalArgumentException both = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(new EmbeddingJobCreateRequest(
                        List.of(1L), null, null, List.of("key"), false, null)));
        assertEquals("Provide either documentIds or a Collection scope",
                both.getMessage());

        IllegalArgumentException neither = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(new EmbeddingJobCreateRequest(
                        null, null, null, null, false, null)));
        assertEquals("Provide either documentIds or a Collection scope",
                neither.getMessage());
    }

    @Test
    void createRejectsEmptyAndNonPositiveDocumentIds() {
        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(new EmbeddingJobCreateRequest(
                        List.of(), null, null, null, false, null)));
        assertEquals("documentIds must not be empty", empty.getMessage());

        IllegalArgumentException nonPositive = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(new EmbeddingJobCreateRequest(
                        List.of(0L), null, null, null, false, null)));
        assertEquals("documentIds must contain positive IDs",
                nonPositive.getMessage());
    }

    @Test
    void createRejectsDisabledAndHashlessDocuments() {
        when(documentRepository.findById(1L))
                .thenReturn(Optional.of(document(1L, false, "abc")));

        IllegalArgumentException disabled = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(new EmbeddingJobCreateRequest(
                        List.of(1L), null, null, null, false, null)));
        assertTrue(disabled.getMessage().contains("Document is disabled"));

        when(documentRepository.findById(1L))
                .thenReturn(Optional.of(document(1L, true, "not-a-hash")));
        IllegalStateException hashless = assertThrows(
                IllegalStateException.class,
                () -> service.create(new EmbeddingJobCreateRequest(
                        List.of(1L), null, null, null, false, null)));
        assertTrue(hashless.getMessage().contains("no valid contentHash"));
    }

    @Test
    void createQueuesJobAndPublishesWakeup() {
        when(documentRepository.findById(1L))
                .thenReturn(Optional.of(document(1L, true,
                        "e3b0c44298fc1c149afbf4c8996fb924"
                                + "27ae41e4649b934ca495991b7852b855")));
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(false);
        when(jobRepository.findCurrentActive(anyLong(), anyLong(),
                anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(jobRepository.allocateGeneration(anyLong(), anyLong(),
                anyString(), anyString(), anyBoolean())).thenReturn(11L);
        when(jobRepository.createOrCoalesce(
                any(UUID.class), anyLong(), anyLong(), anyString(),
                anyLong(), anyBoolean(), anyInt(), any(), any(),
                anyLong(), anyString(), anyString()))
                .thenAnswer(invocation -> new EmbeddingJobRepository.CreateResult(
                        job(EmbeddingJobStatus.QUEUED), false));

        var response = service.create(new EmbeddingJobCreateRequest(
                List.of(1L), null, null, null, false, null));

        assertEquals(1, response.requested());
        verify(jobRepository).updateDocumentProcessing(
                1L, "PENDING", null);
        verify(jobRepository).activateJob(
                eq(1L), eq(9L), eq(11L), any(UUID.class));
        verify(wakeupPublisher).publishAfterCommit();
    }

    @Test
    void readinessRejectsUnauthorizedCollectionScope() {
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.noMatches());

        assertThrows(SecurityException.class,
                () -> service.readiness("secret-key"));
    }

    @Test
    void retryWithoutRequestedAttemptsUsesCurrentAndRejectsDuplicate() {
        EmbeddingJob failed = job(EmbeddingJobStatus.FAILED);
        when(jobRepository.find(JOB_ID)).thenReturn(Optional.of(failed));
        when(documentRepository.findById(42L))
                .thenReturn(Optional.of(document(42L, true, failed.contentHash())));
        when(jobRepository.retry(JOB_ID, 5)).thenReturn(Optional.empty());
        when(jobRepository.findActive(42L, 9L, failed.contentHash()))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.retry(JOB_ID, null));
        assertEquals(ErrorCode.DUPLICATE_RESOURCE, error.getErrorCodeEnum());
    }

    @Test
    void retryDoesNotPublishWakeupForNonQueuedTarget() {
        EmbeddingJob failed = job(EmbeddingJobStatus.FAILED);
        when(jobRepository.find(JOB_ID)).thenReturn(Optional.of(failed));
        when(documentRepository.findById(42L))
                .thenReturn(Optional.of(document(42L, true, failed.contentHash())));
        when(jobRepository.retry(JOB_ID, 5))
                .thenReturn(Optional.of(failed));

        service.retry(JOB_ID, 5);

        verify(wakeupPublisher, never()).publishAfterCommit();
    }
}
