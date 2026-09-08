package com.springairag.core.embeddingjob;

import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.DocumentEmbedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * 嵌入分发分支语义：活跃任务合并（合并不重置处理状态、force 重置）、
 * 持久化任务禁用拒绝、NOT_REQUESTED 标记、SKIP/SYNC/ASYNC 分发策略、
 * 同步完成的成功/失败结果映射。
 */
class EmbeddingDispatchBranchTest {

    private DocumentEmbedService embedService;
    private EmbeddingJobRepository repository;
    private EmbeddingProfileProvider profileProvider;
    private EmbeddingJobWakeupPublisher wakeupPublisher;
    private EmbeddingJobExecutor jobExecutor;
    private DocumentDerivationDescriptorProvider descriptorProvider;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        embedService = mock(DocumentEmbedService.class);
        repository = mock(EmbeddingJobRepository.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        wakeupPublisher = mock(EmbeddingJobWakeupPublisher.class);
        jobExecutor = mock(EmbeddingJobExecutor.class);
        descriptorProvider =
                new DocumentDerivationDescriptorProvider(properties = new RagProperties());
        when(profileProvider.getActiveProfile()).thenReturn(profile());
    }

    @Test
    void coalescesIntoActiveJobWithoutResettingProcessingState() {
        RagDocument document = document();
        EmbeddingJob active = job(document, EmbeddingJobStatus.QUEUED);
        when(repository.findCurrentActive(
                anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(active));
        when(repository.createOrCoalesce(
                any(UUID.class), anyLong(), anyLong(), anyString(),
                anyLong(), anyBoolean(), anyInt(), any(), any(),
                anyLong(), anyString(), anyString()))
                .thenReturn(new EmbeddingJobRepository.CreateResult(active, true));
        EmbeddingDispatchService service = service();

        EmbeddingDispatchService.Result result =
                service.enqueueInCurrentTransaction(document, true, false, "TEST");

        assertEquals(EmbeddingAction.ASYNC_COALESCED, result.action());
        assertEquals(active.batchId(), result.embeddingBatchId());
        // 合并路径不重置处理状态，也不重新分配代次。
        verify(repository, never()).updateDocumentProcessing(
                anyLong(), anyString(), any());
        verify(repository, never()).allocateGeneration(
                anyLong(), anyLong(), anyString(), anyString(), anyBoolean());
        verify(wakeupPublisher).publishAfterCommit();
    }

    @Test
    void forcedRequeueResetsProcessingState() {
        RagDocument document = document();
        EmbeddingJob active = job(document, EmbeddingJobStatus.QUEUED);
        when(repository.findCurrentActive(
                anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(active));
        when(repository.createOrCoalesce(
                any(UUID.class), anyLong(), anyLong(), anyString(),
                anyLong(), anyBoolean(), anyInt(), any(), any(),
                anyLong(), anyString(), anyString()))
                .thenReturn(new EmbeddingJobRepository.CreateResult(active, true));
        EmbeddingDispatchService service = service();

        service.enqueueInCurrentTransaction(document, false, true, "TEST");

        verify(repository).updateDocumentProcessing(
                document.getId(), "PENDING", null);
    }

    @Test
    void throwsWhenPersistentJobsAreDisabled() {
        properties.getEmbeddingJobs().setEnabled(false);
        EmbeddingDispatchService service = service();

        RagException error = assertThrows(RagException.class,
                () -> service.enqueueInCurrentTransaction(
                        document(), true, false, "TEST"));
        assertEquals(ErrorCode.EMBEDDING_JOBS_DISABLED, error.getErrorCodeEnum());
    }

    @Test
    void markNotRequestedUpdatesDocumentProcessing() {
        RagDocument document = document();
        EmbeddingDispatchService service = service();

        EmbeddingDispatchService.Result result =
                service.markNotRequestedInCurrentTransaction(document);

        assertEquals(EmbeddingAction.SKIPPED, result.action());
        assertEquals("NOT_REQUESTED", result.embeddingStatus());
        verify(repository).markNotRequested(
                eq(document.getId()), anyLong(),
                eq(document.getContentHash()), anyString());
        verify(repository).updateDocumentProcessing(
                document.getId(), "NOT_REQUESTED", null);
    }

    @Test
    void dispatchAfterCommitSkipMarksNotRequested() {
        EmbeddingDispatchService service = service();

        EmbeddingDispatchService.Result result = service.dispatchAfterCommit(
                document(), EmbeddingPolicy.SKIP, true, "TEST");

        assertEquals(EmbeddingAction.SKIPPED, result.action());
        verify(repository).updateDocumentProcessing(
                anyLong(), eq("NOT_REQUESTED"), any());
        verify(jobExecutor, never()).executeNow(any());
    }

    @Test
    void dispatchAfterCommitSyncCompletesInline() {
        RagDocument document = document();
        EmbeddingJob queued = job(document, EmbeddingJobStatus.QUEUED);
        stubFreshEnqueue(document, queued);
        EmbeddingJob done = job(document, EmbeddingJobStatus.SUCCEEDED);
        when(jobExecutor.executeNow(queued.id())).thenReturn(done);
        EmbeddingDispatchService service = service();

        EmbeddingDispatchService.Result result = service.dispatchAfterCommit(
                document, EmbeddingPolicy.SYNC, true, "TEST");

        assertEquals(EmbeddingAction.SYNC_COMPLETED, result.action());
        assertEquals("COMPLETED", result.embeddingStatus());
        assertNull(result.error());
    }

    @Test
    void completeAfterCommitReportsFailureWithLastError() {
        RagDocument document = document();
        EmbeddingJob queued = job(document, EmbeddingJobStatus.QUEUED);
        EmbeddingJob failed = job(document, EmbeddingJobStatus.FAILED);
        when(jobExecutor.executeNow(queued.id())).thenReturn(failed);
        EmbeddingDispatchService service = service();

        EmbeddingDispatchService.Result queuedResult = new EmbeddingDispatchService.Result(
                EmbeddingAction.ASYNC_QUEUED, "QUEUED", profile().profileKey(),
                queued.id(), queued.batchId(), null);

        EmbeddingDispatchService.Result result =
                service.completeAfterCommit(queuedResult);

        assertEquals(EmbeddingAction.SYNC_COMPLETED, result.action());
        assertEquals("FAILED", result.embeddingStatus());
        assertEquals(failed.lastError(), result.error());
    }

    @Test
    void completeAfterCommitReturnsQueuedWhenNoExecutor() {
        // 四参构造器没有 jobExecutor：同步完成退化为直接返回排队结果。
        EmbeddingDispatchService service = new EmbeddingDispatchService(
                embedService, repository, profileProvider, properties);
        EmbeddingDispatchService.Result queued = new EmbeddingDispatchService.Result(
                EmbeddingAction.ASYNC_QUEUED, "QUEUED", profile().profileKey(),
                UUID.randomUUID(), UUID.randomUUID(), null);

        assertSame(queued, service.completeAfterCommit(queued));
        verify(repository, never()).createOrCoalesce(
                any(), anyLong(), anyLong(), anyString(), anyLong(),
                anyBoolean(), anyInt(), any(), any(), anyLong(),
                anyString(), anyString());
    }

    @Test
    void completeAfterCommitPassesThroughWhenNoJobWasQueued() {
        EmbeddingDispatchService service = service();
        EmbeddingDispatchService.Result skipped = EmbeddingDispatchService.Result
                .skipped(profile().profileKey());

        assertSame(skipped, service.completeAfterCommit(skipped));
        verify(jobExecutor, never()).executeNow(any());
    }

    private void stubFreshEnqueue(RagDocument document, EmbeddingJob queued) {
        when(repository.findCurrentActive(
                anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(embedService.hasFreshEmbedding(document)).thenReturn(false);
        when(repository.allocateGeneration(
                anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
                .thenReturn(3L);
        when(repository.createOrCoalesce(
                any(UUID.class), anyLong(), anyLong(), anyString(),
                anyLong(), anyBoolean(), anyInt(), any(), any(),
                anyLong(), anyString(), anyString()))
                .thenReturn(new EmbeddingJobRepository.CreateResult(queued, false));
    }

    private EmbeddingDispatchService service() {
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

    private RagDocument document() {
        RagDocument document = new RagDocument();
        document.setId(7L);
        document.setVersion(4L);
        document.setEnabled(true);
        document.setContentHash(
                "0123456789abcdef0123456789abcdef"
                        + "0123456789abcdef0123456789abcdef");
        return document;
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                9L, "test", "test", "test", "v1",
                1024, "COSINE", "NONE", true);
    }

    private EmbeddingJob job(
            RagDocument document,
            EmbeddingJobStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new EmbeddingJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                document.getId(),
                profile().id(),
                false,
                document.getContentHash(),
                document.getVersion(),
                status,
                0,
                3,
                now,
                null,
                null,
                null,
                "embedding failed",
                now,
                null,
                null,
                now,
                "TEST",
                "test",
                3L,
                "TEXT",
                descriptorProvider.textDescriptor().chunkerVersion());
    }
}
