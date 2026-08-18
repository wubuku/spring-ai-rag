package com.springairag.core.embeddingjob;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.EmbeddingCommitGuard;
import com.springairag.core.service.EmbeddingCommitRejectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;

class EmbeddingJobWorkerTest {

    private EmbeddingJobRepository repository;
    private DocumentEmbedService embedService;
    private EmbeddingProfileProvider profileProvider;
    private EmbeddingJobWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(EmbeddingJobRepository.class);
        embedService = mock(DocumentEmbedService.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        RagProperties properties = new RagProperties();
        worker = new EmbeddingJobWorker(
                repository, embedService, profileProvider, properties);
        when(profileProvider.getActiveProfile()).thenReturn(
                new EmbeddingProfile(
                        9L, "test", "test", "test", "v1",
                1024, "COSINE", "NONE", true));
    }

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    @Test
    void concurrentClaimsUseDistinctLeaseOwners() {
        RagProperties properties = new RagProperties();
        properties.getEmbeddingJobs().setWorkerConcurrency(2);
        properties.getEmbeddingJobs().setClaimBatchSize(2);
        worker.shutdown();
        worker = new EmbeddingJobWorker(
                repository, embedService, profileProvider, properties);
        EmbeddingJob first = job(UUID.randomUUID(), null);
        EmbeddingJob second = job(UUID.randomUUID(), null);
        when(repository.claim(anyString(), eq(1), anyInt()))
                .thenReturn(List.of(first), List.of(second));
        when(repository.isCommitAllowed(
                any(UUID.class), anyString(), anyLong()))
                .thenReturn(true);
        when(repository.claimCommitAllowed(
                any(UUID.class), anyString(), anyLong(), anyInt()))
                .thenReturn(true);
        when(repository.find(any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return java.util.Optional.of(
                    first.id().equals(id) ? first : second);
        });
        when(embedService.embedDocumentForJob(
                anyLong(), anyBoolean(), any(EmbeddingCommitGuard.class)))
                .thenReturn(Map.of("status", "COMPLETED"));

        worker.poll();

        ArgumentCaptor<String> owners = ArgumentCaptor.forClass(String.class);
        verify(repository, timeout(2000).times(2))
                .claim(owners.capture(), eq(1), anyInt());
        assertEquals(2, owners.getAllValues().size());
        assertNotEquals(
                owners.getAllValues().get(0),
                owners.getAllValues().get(1));
    }

    @Test
    void verifiesCommitGateAfterProviderBeforeSuccess() {
        EmbeddingJob job = job();
        when(repository.isCommitAllowed(
                any(UUID.class), anyString(), anyLong()))
                .thenReturn(true);
        when(repository.claimCommitAllowed(
                any(UUID.class), anyString(), anyLong(), anyInt()))
                .thenReturn(true);
        when(repository.find(job.id())).thenReturn(
                java.util.Optional.of(job));
        when(embedService.embedDocumentForJob(
                anyLong(), anyBoolean(), any(EmbeddingCommitGuard.class)))
                .thenAnswer(invocation -> {
                    EmbeddingCommitGuard guard = invocation.getArgument(2);
                    guard.verify();
                    return Map.of("status", "COMPLETED");
                });

        worker.process(job);

        verify(repository).markSucceeded(
                any(UUID.class), anyString(), eq(true));
    }

    @Test
    void staleSnapshotNeverCallsEmbeddingProvider() {
        EmbeddingJob job = job();
        when(repository.isCommitAllowed(
                any(UUID.class), anyString(), anyLong()))
                .thenReturn(false);
        when(repository.isCancellationRequested(job.id()))
                .thenReturn(false);

        worker.process(job);

        verify(embedService, never()).embedDocumentForJob(
                anyLong(), anyBoolean(), any());
        verify(repository).markStale(
                any(UUID.class), anyString(), anyString());
    }

    @Test
    void cancellationWonDuringProviderCallSkipsCommit() {
        EmbeddingJob job = job();
        when(repository.isCommitAllowed(
                any(UUID.class), anyString(), anyLong()))
                .thenReturn(true);
        when(repository.claimCommitAllowed(
                any(UUID.class), anyString(), anyLong(), anyInt()))
                .thenReturn(false);
        when(repository.isCancellationRequested(job.id()))
                .thenReturn(true);
        when(embedService.embedDocumentForJob(
                anyLong(), anyBoolean(), any(EmbeddingCommitGuard.class)))
                .thenAnswer(invocation -> {
                    EmbeddingCommitGuard guard = invocation.getArgument(2);
                    guard.verify();
                    throw new AssertionError("guard should reject");
                });

        worker.process(job);

        verify(repository).markCancelled(
                any(UUID.class), anyString());
        verify(repository, never()).markSucceeded(
                any(UUID.class), anyString(), anyBoolean());
    }

    @Test
    void forceUpgradeDuringCachedAttemptTriggersForcedEmbedding() {
        EmbeddingJob original = job();
        EmbeddingJob upgraded = job(true);
        when(repository.isCommitAllowed(
                any(UUID.class), anyString(), anyLong()))
                .thenReturn(true);
        when(repository.claimCommitAllowed(
                any(UUID.class), anyString(), anyLong(), anyInt()))
                .thenReturn(true);
        when(repository.find(original.id()))
                .thenReturn(
                        java.util.Optional.of(original),
                        java.util.Optional.of(upgraded));
        when(embedService.embedDocumentForJob(
                eq(original.documentId()),
                eq(false),
                any(EmbeddingCommitGuard.class)))
                .thenReturn(Map.of("status", "CACHED"));
        when(embedService.embedDocumentForJob(
                eq(original.documentId()),
                eq(true),
                any(EmbeddingCommitGuard.class)))
                .thenReturn(Map.of("status", "COMPLETED"));
        when(repository.markSucceeded(
                any(UUID.class), anyString(), eq(false)))
                .thenReturn(0);
        when(repository.markSucceeded(
                any(UUID.class), anyString(), eq(true)))
                .thenReturn(1);

        worker.process(original);

        verify(embedService).embedDocumentForJob(
                eq(original.documentId()), eq(false), any());
        verify(embedService).embedDocumentForJob(
                eq(original.documentId()), eq(true), any());
    }

    private EmbeddingJob job() {
        return job(false);
    }

    private EmbeddingJob job(boolean force) {
        return job(JOB_ID, "worker", force);
    }

    private EmbeddingJob job(UUID id, String leaseOwner) {
        return job(id, leaseOwner, false);
    }

    private EmbeddingJob job(
            UUID id,
            String leaseOwner,
            boolean force) {
        OffsetDateTime now = OffsetDateTime.now();
        return new EmbeddingJob(
                id,
                UUID.randomUUID(),
                1L,
                9L,
                force,
                "0123456789abcdef0123456789abcdef"
                        + "0123456789abcdef0123456789abcdef",
                7L,
                EmbeddingJobStatus.RUNNING,
                1,
                3,
                now,
                leaseOwner,
                now.plusMinutes(2),
                null,
                null,
                now,
                now,
                null,
                now);
    }

    private static final UUID JOB_ID = UUID.randomUUID();
}
