package com.springairag.core.embeddingjob;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.EmbeddingCommitRejectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EmbeddingJobExecutor} 的纯单元测试：不依赖数据库即可覆盖
 * claim、commit 门、CACHED 升级、失败标记与错误脱敏的核心决策逻辑。
 */
class EmbeddingJobExecutorTest {

    private static final UUID JOB_ID = UUID.randomUUID();
    private static final String OWNER = "sync-owner";

    private EmbeddingJobRepository repository;
    private DocumentEmbedService embedService;
    private EmbeddingProfileProvider profileProvider;
    private EmbeddingJobExecutor executor;

    @BeforeEach
    void setUp() {
        repository = mock(EmbeddingJobRepository.class);
        embedService = mock(DocumentEmbedService.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(profile(9L));
        executor = new EmbeddingJobExecutor(
                repository, embedService, profileProvider, new RagProperties());
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private EmbeddingProfile profile(long id) {
        return new EmbeddingProfile(
                id, "bge-m3", "siliconflow", "BAAI/bge-m3", null,
                1024, "cosine", "none", true);
    }

    private EmbeddingJob job(long profileId, boolean force) {
        OffsetDateTime now = OffsetDateTime.now();
        return new EmbeddingJob(
                JOB_ID,
                UUID.randomUUID(),
                42L,
                profileId,
                force,
                "hash",
                7L,
                EmbeddingJobStatus.RUNNING,
                1,
                3,
                now,
                OWNER,
                now.plusMinutes(2),
                null,
                null,
                now,
                now,
                null,
                now);
    }

    private void stubClaimed(EmbeddingJob job) {
        when(repository.claimById(eq(JOB_ID), anyString(), anyInt()))
                .thenReturn(Optional.of(job));
        when(repository.find(JOB_ID)).thenReturn(Optional.of(job));
    }

    @Test
    void executeNowThrowsWhenJobIsUnknown() {
        when(repository.claimById(eq(JOB_ID), anyString(), anyInt()))
                .thenReturn(Optional.empty());
        when(repository.find(JOB_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> executor.executeNow(JOB_ID));
        verify(embedService, never()).embedDocumentForJob(
                org.mockito.ArgumentMatchers.anyLong(), anyBoolean(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void executeNowReturnsCurrentJobWhenClaimIsNotAcquired() {
        EmbeddingJob job = job(9L, false);
        when(repository.claimById(eq(JOB_ID), anyString(), anyInt()))
                .thenReturn(Optional.empty());
        when(repository.find(JOB_ID)).thenReturn(Optional.of(job));

        EmbeddingJob result = executor.executeNow(JOB_ID);

        assertEquals(JOB_ID, result.id());
        verify(repository, never()).markProgress(eq(JOB_ID), anyString(), anyString());
    }

    @Test
    void completedJobProgressesThroughStagesAndMarksSucceeded() {
        EmbeddingJob job = job(9L, false);
        stubClaimed(job);
        when(repository.isCommitAllowed(eq(JOB_ID), anyString(), eq(9L))).thenReturn(true);
        when(embedService.embedDocumentForJob(eq(42L), eq(false), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("status", "COMPLETED"));
        when(repository.markSucceeded(eq(JOB_ID), anyString(), eq(true))).thenReturn(1);

        executor.executeNow(JOB_ID);

        verify(repository).markProgress(eq(JOB_ID), anyString(), eq("CLAIMED"));
        verify(repository).markProgress(eq(JOB_ID), anyString(), eq("EMBEDDING"));
        verify(repository).markProgress(eq(JOB_ID), anyString(), eq("COMMITTING"));
        verify(repository).markSucceeded(eq(JOB_ID), anyString(), eq(true));
        verify(repository).refreshStateFromJob(JOB_ID);
        verify(repository, never()).markFailure(
                eq(JOB_ID), anyString(), anyString(), anyInt());
    }

    @Test
    void staleProfileJobEndsInStaleWithoutEmbedding() {
        EmbeddingJob job = job(8L, false);
        stubClaimed(job);
        when(repository.isCancellationRequested(JOB_ID)).thenReturn(false);

        executor.executeNow(JOB_ID);

        verify(embedService, never()).embedDocumentForJob(
                org.mockito.ArgumentMatchers.anyLong(), anyBoolean(), org.mockito.ArgumentMatchers.any());
        verify(repository).markStale(
                eq(JOB_ID), anyString(), argThat(reason -> reason.contains("stale")));
        verify(repository).refreshStateFromJob(JOB_ID);
    }

    @Test
    void staleProfileJobIsCancelledWhenCancellationWasRequested() {
        EmbeddingJob job = job(8L, false);
        stubClaimed(job);
        when(repository.isCancellationRequested(JOB_ID)).thenReturn(true);

        executor.executeNow(JOB_ID);

        verify(repository).markCancelled(eq(JOB_ID), anyString());
        verify(repository, never()).markStale(eq(JOB_ID), anyString(), anyString());
    }

    @Test
    void commitGateRejectionEndsStaleWithoutEmbedding() {
        EmbeddingJob job = job(9L, false);
        stubClaimed(job);
        when(repository.isCommitAllowed(eq(JOB_ID), eq(OWNER), eq(9L))).thenReturn(false);

        executor.executeNow(JOB_ID);

        verify(embedService, never()).embedDocumentForJob(
                org.mockito.ArgumentMatchers.anyLong(), anyBoolean(), org.mockito.ArgumentMatchers.any());
        verify(repository).markStale(eq(JOB_ID), anyString(), anyString());
    }

    @Test
    void cachedResultMarksSucceededWithForceSatisfiedFalse() {
        EmbeddingJob job = job(9L, false);
        stubClaimed(job);
        when(repository.isCommitAllowed(eq(JOB_ID), anyString(), eq(9L))).thenReturn(true);
        when(embedService.embedDocumentForJob(eq(42L), eq(false), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("status", "CACHED"));
        when(repository.markSucceeded(eq(JOB_ID), anyString(), eq(false))).thenReturn(1);

        executor.executeNow(JOB_ID);

        verify(repository).markSucceeded(eq(JOB_ID), anyString(), eq(false));
        verify(repository).refreshStateFromJob(JOB_ID);
        verify(embedService, org.mockito.Mockito.times(1)).embedDocumentForJob(
                org.mockito.ArgumentMatchers.anyLong(), anyBoolean(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cachedResultIsUpgradedToForcedEmbeddingWhenJobDemandsForce() {
        EmbeddingJob job = job(9L, false);
        EmbeddingJob forced = job(9L, true);
        when(repository.claimById(eq(JOB_ID), anyString(), anyInt()))
                .thenReturn(Optional.of(job));
        when(repository.find(JOB_ID)).thenReturn(Optional.of(job), Optional.of(forced));
        when(repository.isCommitAllowed(eq(JOB_ID), anyString(), eq(9L))).thenReturn(true);
        when(embedService.embedDocumentForJob(eq(42L), eq(false), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("status", "CACHED"));
        when(embedService.embedDocumentForJob(eq(42L), eq(true), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("status", "COMPLETED"));
        when(repository.markSucceeded(eq(JOB_ID), anyString(), eq(false))).thenReturn(0);
        when(repository.markSucceeded(eq(JOB_ID), anyString(), eq(true))).thenReturn(1);

        executor.executeNow(JOB_ID);

        verify(embedService).embedDocumentForJob(
                eq(42L), eq(true), org.mockito.ArgumentMatchers.any());
        verify(repository).markSucceeded(eq(JOB_ID), anyString(), eq(true));
        verify(repository, never()).markStale(eq(JOB_ID), anyString(), anyString());
    }

    @Test
    void providerFailureIsReportedThroughMarkFailure() {
        EmbeddingJob job = job(9L, false);
        stubClaimed(job);
        when(repository.isCommitAllowed(eq(JOB_ID), anyString(), eq(9L))).thenReturn(true);
        when(embedService.embedDocumentForJob(eq(42L), eq(false), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("status", "FAILED", "error", "provider down"));

        executor.executeNow(JOB_ID);

        verify(repository).markFailure(eq(JOB_ID), anyString(), eq("provider down"), anyInt());
        verify(repository).refreshStateFromJob(JOB_ID);
        verify(repository, never()).markSucceeded(eq(JOB_ID), anyString(), anyBoolean());
    }

    @Test
    void runtimeFailureIsMaskedTruncatedAndPersisted() {
        EmbeddingJob job = job(9L, false);
        stubClaimed(job);
        when(repository.isCommitAllowed(eq(JOB_ID), anyString(), eq(9L))).thenReturn(true);
        String verbose = "provider exploded: password=super-secret-value-2026 ".repeat(40);
        when(embedService.embedDocumentForJob(eq(42L), eq(false), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException(verbose));

        executor.executeNow(JOB_ID);

        verify(repository).markFailure(
                eq(JOB_ID), anyString(),
                argThat(error -> error.length() <= 500
                        && !error.contains("super-secret-value-2026")),
                anyInt());
        verify(repository).refreshStateFromJob(JOB_ID);
    }

    @Test
    void commitRejectionDuringEmbeddingEndsStale() {
        EmbeddingJob job = job(9L, false);
        stubClaimed(job);
        when(repository.isCommitAllowed(eq(JOB_ID), anyString(), eq(9L))).thenReturn(true);
        when(embedService.embedDocumentForJob(eq(42L), eq(false), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new EmbeddingCommitRejectedException("lost commit eligibility"));

        executor.executeNow(JOB_ID);

        verify(repository).markStale(
                eq(JOB_ID), anyString(), argThat(reason -> reason.contains("lost commit eligibility")));
        verify(repository, never()).markFailure(
                eq(JOB_ID), anyString(), anyString(), anyInt());
    }
}
