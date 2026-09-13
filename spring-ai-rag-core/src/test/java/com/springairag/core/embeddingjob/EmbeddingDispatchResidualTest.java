package com.springairag.core.embeddingjob;

import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.KeywordIndexPersistenceService;
import com.springairag.core.service.DocumentEmbedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 嵌入分发残余（Batch 367）：ASYNC 直返、keyword 索引钩子、
 * null 版本文档回退 0、新建合并结果动作、取消委派、同步完成对
 * 活跃状态原样返回、无描述符提供者时的文档类型回退。
 */
class EmbeddingDispatchResidualTest {

    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private DocumentEmbedService embedService;
    private EmbeddingJobRepository repository;
    private EmbeddingProfileProvider profileProvider;
    private EmbeddingJobWakeupPublisher wakeupPublisher;
    private EmbeddingJobExecutor jobExecutor;
    private KeywordIndexPersistenceService keywordIndexPersistenceService;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        embedService = mock(DocumentEmbedService.class);
        repository = mock(EmbeddingJobRepository.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        wakeupPublisher = mock(EmbeddingJobWakeupPublisher.class);
        jobExecutor = mock(EmbeddingJobExecutor.class);
        keywordIndexPersistenceService = mock(KeywordIndexPersistenceService.class);
        properties = new RagProperties();
        when(profileProvider.getActiveProfile()).thenReturn(profile());
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                9L, "test", "test", "test", "v1",
                1024, "COSINE", "NONE", true);
    }

    private RagDocument document(String documentType) {
        RagDocument document = new RagDocument();
        document.setId(7L);
        document.setVersion(4L);
        document.setEnabled(true);
        document.setDocumentType(documentType);
        document.setContentHash(HASH);
        return document;
    }

    private EmbeddingJob job(UUID id, EmbeddingJobStatus status) {
        return new EmbeddingJob(id, UUID.randomUUID(), 7L, 9L, false, HASH,
                1L, status, 0, 3,
                OffsetDateTime.now(), null, null, null, null,
                OffsetDateTime.now(), null, null, OffsetDateTime.now(),
                "TEST", null, 1L, "TEXT", "legacy-compatible");
    }

    private EmbeddingDispatchService service(
            DocumentDerivationDescriptorProvider descriptorProvider) {
        EmbeddingDispatchService service = new EmbeddingDispatchService(
                embedService,
                repository,
                profileProvider,
                properties,
                descriptorProvider,
                jobExecutor);
        service.setWakeupPublisher(wakeupPublisher);
        return service;
    }

    private EmbeddingJobRepository.CreateResult created(
            EmbeddingJob job, boolean coalesced) {
        return new EmbeddingJobRepository.CreateResult(job, coalesced);
    }

    @Test
    void asyncDispatchReturnsQueuedResultWithoutSyncCompletion() {
        EmbeddingJob createdJob = job(UUID.randomUUID(), EmbeddingJobStatus.QUEUED);
        when(repository.findCurrentActive(anyLong(), anyLong(),
                anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.allocateGeneration(anyLong(), anyLong(),
                anyString(), anyString(), anyBoolean())).thenReturn(1L);
        when(repository.createOrCoalesce(any(UUID.class), anyLong(),
                anyLong(), anyString(), anyLong(), anyBoolean(), anyInt(),
                any(), any(), anyLong(), anyString(), anyString()))
                .thenReturn(created(createdJob, false));

        EmbeddingDispatchService.Result result = service(null)
                .dispatchAfterCommit(document("text"),
                        EmbeddingPolicy.ASYNC, true, "TEST");

        assertEquals(EmbeddingAction.ASYNC_QUEUED, result.action());
        // ASYNC 不触发同步执行。
        verify(jobExecutor, org.mockito.Mockito.never()).executeNow(any());
    }

    @Test
    void keywordIndexHookRunsOnEnqueueAndMarkNotRequested() {
        EmbeddingJob createdJob = job(UUID.randomUUID(), EmbeddingJobStatus.QUEUED);
        when(repository.findCurrentActive(anyLong(), anyLong(),
                anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.allocateGeneration(anyLong(), anyLong(),
                anyString(), anyString(), anyBoolean())).thenReturn(1L);
        when(repository.createOrCoalesce(any(UUID.class), anyLong(),
                anyLong(), anyString(), anyLong(), anyBoolean(), anyInt(),
                any(), any(), anyLong(), anyString(), anyString()))
                .thenReturn(created(createdJob, false));
        EmbeddingDispatchService service = service(null);
        service.setKeywordIndexPersistenceService(keywordIndexPersistenceService);
        RagDocument textDocument = document("text");

        service.enqueueInCurrentTransaction(textDocument, true, false, "TEST");
        verify(keywordIndexPersistenceService).ensureCurrent(textDocument);

        service.markNotRequestedInCurrentTransaction(textDocument);
        verify(keywordIndexPersistenceService).markNotRequested(textDocument);
    }

    @Test
    void nullVersionDocumentDefaultsToZeroInCreateCalls() {
        RagDocument document = document("text");
        document.setVersion(null);
        EmbeddingJob createdJob = job(UUID.randomUUID(), EmbeddingJobStatus.QUEUED);
        when(repository.findCurrentActive(anyLong(), anyLong(),
                anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.allocateGeneration(anyLong(), anyLong(),
                anyString(), anyString(), anyBoolean())).thenReturn(1L);
        ArgumentCaptor<Long> versionCaptor = ArgumentCaptor.forClass(Long.class);
        when(repository.createOrCoalesce(any(UUID.class), anyLong(),
                anyLong(), anyString(), versionCaptor.capture(),
                anyBoolean(), anyInt(), any(), any(), anyLong(),
                anyString(), anyString()))
                .thenReturn(created(createdJob, false));

        service(null).enqueueInCurrentTransaction(document, true, false, "TEST");

        assertEquals(0L, versionCaptor.getValue());
    }

    @Test
    void coalescedFreshCreateReportsAsyncCoalesced() {
        EmbeddingJob createdJob = job(UUID.randomUUID(), EmbeddingJobStatus.QUEUED);
        when(repository.findCurrentActive(anyLong(), anyLong(),
                anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.allocateGeneration(anyLong(), anyLong(),
                anyString(), anyString(), anyBoolean())).thenReturn(1L);
        when(repository.createOrCoalesce(any(UUID.class), anyLong(),
                anyLong(), anyString(), anyLong(), anyBoolean(), anyInt(),
                any(), any(), anyLong(), anyString(), anyString()))
                .thenReturn(created(createdJob, true));

        EmbeddingDispatchService.Result result = service(null)
                .enqueueInCurrentTransaction(document("text"), true, false, "TEST");

        assertEquals(EmbeddingAction.ASYNC_COALESCED, result.action());
    }

    @Test
    void cancelActiveDelegatesToRepository() {
        when(repository.cancelActiveForDocument(7L)).thenReturn(2);

        assertEquals(2, service(null).cancelActiveInCurrentTransaction(7L));
    }

    @Test
    void syncCompletionPassesThroughActiveStatusResult() {
        EmbeddingJob runningJob = job(UUID.randomUUID(), EmbeddingJobStatus.RUNNING);
        EmbeddingDispatchService service = service(null);
        when(repository.findCurrentActive(anyLong(), anyLong(),
                anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.allocateGeneration(anyLong(), anyLong(),
                anyString(), anyString(), anyBoolean())).thenReturn(1L);
        when(repository.createOrCoalesce(any(UUID.class), anyLong(),
                anyLong(), anyString(), anyLong(), anyBoolean(), anyInt(),
                any(), any(), anyLong(), anyString(), anyString()))
                .thenReturn(created(runningJob, false));
        when(jobExecutor.executeNow(any())).thenReturn(runningJob);

        EmbeddingDispatchService.Result queued = service
                .enqueueInCurrentTransaction(document("text"), true, false, "TEST");
        EmbeddingDispatchService.Result completed =
                service.completeAfterCommit(queued);

        // 任务仍在活跃状态：同步完成原样返回排队结果。
        assertSame(queued, completed);
    }

    @Test
    void descriptorFallbackUsesDocumentTypeWithoutProvider() {
        EmbeddingDispatchService service = service(null);

        service.markNotRequestedInCurrentTransaction(document("text"));
        verify(repository).markNotRequested(7L, 9L, HASH, "legacy-compatible");

        service.markNotRequestedInCurrentTransaction(document("json-record"));
        verify(repository).markNotRequested(7L, 9L, HASH, "json-record-v1:single");
    }
}
