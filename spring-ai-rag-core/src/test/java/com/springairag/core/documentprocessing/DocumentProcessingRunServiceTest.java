package com.springairag.core.documentprocessing;

import com.springairag.api.dto.DocumentProcessingRunPageResponse;
import com.springairag.api.dto.DocumentProcessingRunResponse;
import com.springairag.api.dto.DocumentProcessingSummary;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.enums.DocumentProcessingRunOrigin;
import com.springairag.api.enums.DocumentProcessingRunStatus;
import com.springairag.api.enums.DocumentProcessingStageName;
import com.springairag.api.enums.DocumentProcessingStageStatus;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.documentprocessing.DocumentProcessingRunRepository.PageResult;
import com.springairag.core.embeddingjob.EmbeddingJob;
import com.springairag.core.embeddingjob.EmbeddingJobRepository;
import com.springairag.core.embeddingjob.EmbeddingJobStatus;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentProcessingRunServiceTest {

    private static final long DOCUMENT_ID = 42L;
    private static final long PROFILE_ID = 7L;
    private static final String CONTENT_HASH = "0123456789abcdef0123456789abcdef"
            + "0123456789abcdef0123456789abcdef";
    private static final String CHUNKER_VERSION = "test-chunker-v1";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-18T10:00:00Z");

    @BeforeEach
    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void disabledLedgerDoesNotAdvanceSupersededRuns() {
        DocumentProcessingRunRepository repository =
                mock(DocumentProcessingRunRepository.class);
        RagProperties properties = new RagProperties();
        properties.getDocumentProcessing().setEnabled(false);
        DocumentProcessingRunService service = new DocumentProcessingRunService(
                repository,
                mock(com.springairag.core.embeddingjob.EmbeddingJobRepository.class),
                mock(RagDocumentRepository.class),
                mock(EmbeddingProfileProvider.class),
                new DocumentDerivationDescriptorProvider(properties),
                mock(CollectionRetrievalScopeResolver.class),
                properties);

        assertEquals(0, service.markSuperseded(1L, 2L, 3L));
        verify(repository, never()).markSuperseded(1L, 2L, 3L);
    }

    @Test
    void historicalTerminalJobDoesNotCreateLegacyBridge() {
        DocumentProcessingRunRepository repository =
                mock(DocumentProcessingRunRepository.class);
        RagProperties properties = new RagProperties();
        DocumentProcessingRunService service = service(repository, properties);
        EmbeddingJob job = job(EmbeddingJobStatus.SUCCEEDED);
        when(repository.findByJobId(job.id())).thenReturn(Optional.empty());

        assertNull(service.ensureBridgeForJob(job));
        verify(repository, never()).ensureForJob(
                org.mockito.ArgumentMatchers.eq(job),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void ensureBridgeForJobReturnsExistingRunWithoutCreatingAnother() {
        DocumentProcessingRunRepository repository =
                mock(DocumentProcessingRunRepository.class);
        RagProperties properties = new RagProperties();
        DocumentProcessingRunService service = service(repository, properties);
        EmbeddingJob job = job(EmbeddingJobStatus.RUNNING);
        DocumentProcessingRun existing = run(
                UUID.randomUUID(), job.id(), DocumentProcessingRunStatus.RUNNING,
                CONTENT_HASH, null, true);
        when(repository.findByJobId(job.id())).thenReturn(Optional.of(existing));

        assertEquals(existing, service.ensureBridgeForJob(job));
        verify(repository, never()).ensureForJob(
                any(), anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void staleJobCanCreateHistoricalLegacyBridge() {
        DocumentProcessingRunRepository repository =
                mock(DocumentProcessingRunRepository.class);
        RagProperties properties = new RagProperties();
        DocumentProcessingRunService service = service(repository, properties);
        EmbeddingJob job = job(EmbeddingJobStatus.STALE);
        when(repository.findByJobId(job.id())).thenReturn(Optional.empty());

        service.ensureBridgeForJob(job);

        verify(repository).ensureForJob(
                org.mockito.ArgumentMatchers.eq(job),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void ensureBridgesForJobsKeepsExistingIdsAndBridgesOnlyActiveJobs() {
        Context context = context();
        EmbeddingJob existingJob = job(EmbeddingJobStatus.QUEUED);
        EmbeddingJob bridgeJob = job(EmbeddingJobStatus.RUNNING);
        EmbeddingJob terminalJob = job(EmbeddingJobStatus.SUCCEEDED);
        UUID existingRunId = UUID.randomUUID();
        DocumentProcessingRun bridgeRun = run(
                UUID.randomUUID(), bridgeJob.id(),
                DocumentProcessingRunStatus.RUNNING, CONTENT_HASH, null, true);
        when(context.repository.findRunIdsByJobIds(
                List.of(existingJob.id(), bridgeJob.id(), terminalJob.id())))
                .thenReturn(Map.of(existingJob.id(), existingRunId));
        when(context.repository.findByJobId(bridgeJob.id())).thenReturn(Optional.empty());
        when(context.repository.ensureForJob(
                eq(bridgeJob), eq("API"), eq(ChatPrincipal.AUTH_DISABLED_ID),
                isNull(), eq(true)))
                .thenReturn(bridgeRun);

        Map<UUID, UUID> result = context.service.ensureBridgesForJobs(
                List.of(existingJob, bridgeJob, terminalJob));

        assertEquals(Map.of(
                existingJob.id(), existingRunId,
                bridgeJob.id(), bridgeRun.id()), result);
        verify(context.repository, never()).findByJobId(existingJob.id());
        verify(context.repository, never()).ensureForJob(
                eq(terminalJob), anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void summaryForCurrentFailedRunReportsSnapshotSafeRetryability() {
        Context context = context();
        RagDocument document = document(CONTENT_HASH, true);
        DocumentProcessingRun failed = run(
                UUID.randomUUID(), UUID.randomUUID(),
                DocumentProcessingRunStatus.FAILED, CONTENT_HASH, null, false);
        when(context.jobRepository.findCurrentGeneration(DOCUMENT_ID, PROFILE_ID))
                .thenReturn(4L);
        when(context.repository.findCurrent(
                DOCUMENT_ID, PROFILE_ID, 4L, CONTENT_HASH, CHUNKER_VERSION))
                .thenReturn(Optional.of(failed));

        DocumentProcessingSummary summary = context.service.summaryForDocument(document);

        assertNotNull(summary);
        assertEquals(failed.id(), summary.runId());
        assertEquals(DocumentProcessingRunStatus.FAILED, summary.status());
        assertTrue(summary.retryable());
        assertFalse(summary.deadLettered());
        assertTrue(summary.stageHistoryComplete());
        verify(context.repository, never()).findByDocumentLatest(DOCUMENT_ID, PROFILE_ID);
    }

    @Test
    void summaryForChangedDocumentSnapshotIsNotRetryable() {
        Context context = context();
        RagDocument document = document("new-content-hash", true);
        DocumentProcessingRun failed = run(
                UUID.randomUUID(), UUID.randomUUID(),
                DocumentProcessingRunStatus.FAILED, CONTENT_HASH, null, false);
        when(context.jobRepository.findCurrentGeneration(DOCUMENT_ID, PROFILE_ID))
                .thenReturn(5L);
        when(context.repository.findCurrent(
                DOCUMENT_ID, PROFILE_ID, 5L, "new-content-hash", CHUNKER_VERSION))
                .thenReturn(Optional.empty());
        when(context.repository.findByDocumentLatest(DOCUMENT_ID, PROFILE_ID))
                .thenReturn(Optional.of(failed));

        DocumentProcessingSummary summary = context.service.summaryForDocument(document);

        assertNotNull(summary);
        assertFalse(summary.retryable());
    }

    @Test
    void summaryFailsClosedForDisabledDocumentsProviderErrorsAndMissingRuns() {
        Context context = context();
        assertNull(context.service.summaryForDocument(document(CONTENT_HASH, false)));
        verify(context.profileProvider, never()).getActiveProfile();

        when(context.profileProvider.getActiveProfile())
                .thenThrow(new IllegalStateException("profile unavailable"));
        assertNull(context.service.summaryForDocument(document(CONTENT_HASH, true)));

        Context missingContext = context();
        assertNull(missingContext.service.summaryForDocument(
                document(CONTENT_HASH, true)));
    }

    @Test
    void enabledRunLookupsAndSupersedingDelegateToRepository() {
        Context context = context();
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(context.repository.findByJobId(jobId))
                .thenReturn(Optional.of(run(
                        runId, jobId, DocumentProcessingRunStatus.FAILED,
                        CONTENT_HASH, null, false)));
        when(context.repository.markSuperseded(DOCUMENT_ID, PROFILE_ID, 9L))
                .thenReturn(2);

        assertEquals(runId, context.service.findRunIdByJobId(jobId));
        assertEquals(2, context.service.markSuperseded(DOCUMENT_ID, PROFILE_ID, 9L));
    }

    @Test
    void summaryBridgesCurrentLegacyJobAndMarksStageHistoryIncomplete() {
        Context context = context();
        RagDocument document = document(CONTENT_HASH, true);
        EmbeddingJob activeJob = job(EmbeddingJobStatus.QUEUED);
        DocumentProcessingRun legacyRun = run(
                UUID.randomUUID(), activeJob.id(),
                DocumentProcessingRunStatus.QUEUED, CONTENT_HASH, null, true);
        when(context.jobRepository.findCurrentGeneration(DOCUMENT_ID, PROFILE_ID))
                .thenReturn(6L);
        when(context.repository.findCurrent(
                DOCUMENT_ID, PROFILE_ID, 6L, CONTENT_HASH, CHUNKER_VERSION))
                .thenReturn(Optional.empty());
        when(context.repository.findByDocumentLatest(DOCUMENT_ID, PROFILE_ID))
                .thenReturn(Optional.empty());
        when(context.jobRepository.findCurrentActive(
                DOCUMENT_ID, PROFILE_ID, CONTENT_HASH, "TEXT", CHUNKER_VERSION))
                .thenReturn(Optional.of(activeJob));
        when(context.repository.findByJobId(activeJob.id())).thenReturn(Optional.empty());
        when(context.repository.ensureForJob(
                eq(activeJob), eq("API"), eq(ChatPrincipal.AUTH_DISABLED_ID),
                isNull(), eq(true)))
                .thenReturn(legacyRun);

        DocumentProcessingSummary summary = context.service.summaryForDocument(document);

        assertNotNull(summary);
        assertEquals(legacyRun.id(), summary.runId());
        assertFalse(summary.retryable());
        assertFalse(summary.stageHistoryComplete());
        verify(context.repository).ensureForJob(
                activeJob, "API", ChatPrincipal.AUTH_DISABLED_ID, null, true);
    }

    @Test
    void listClampsPaginationAndMapsStageSnapshots() {
        Context context = context();
        UUID runId = UUID.randomUUID();
        DocumentProcessingRun run = run(
                runId, UUID.randomUUID(), DocumentProcessingRunStatus.RUNNING,
                CONTENT_HASH, null, false);
        DocumentProcessingStage stage = new DocumentProcessingStage(
                runId, DocumentProcessingStageName.EMBEDDING, 3,
                DocumentProcessingStageStatus.RUNNING, 2, 4, 10,
                "embedding vectors", NOW, null, NOW, NOW, null, null);
        when(context.repository.listPage(null, null, null, null, 200, 0))
                .thenReturn(new PageResult(List.of(run), 25));
        when(context.repository.stages(runId)).thenReturn(List.of(stage));

        DocumentProcessingRunPageResponse page = context.service.list(
                null, null, null, null, -3, 500);

        assertEquals(0, page.page());
        assertEquals(200, page.size());
        assertEquals(25, page.totalElements());
        assertEquals(1, page.totalPages());
        assertEquals("embedding vectors", page.items().getFirst()
                .stages().getFirst().progressLabel());
    }

    @Test
    void listResolvesAuthorizedCollectionKeyBeforeQueryingRuns() {
        Context context = context();
        RetrievalScope selected = RetrievalScope.selectedCollections(
                List.of(91L), List.of(), null);
        when(context.scopeResolver.resolve(
                eq(CollectionScopeMode.SELECTED_COLLECTIONS), isNull(),
                eq(List.of("external-key")), isNull(), isNull(), isNull()))
                .thenReturn(selected);
        when(context.repository.listPage(
                12L, DocumentProcessingRunStatus.FAILED, true,
                List.of(91L), 10, 20))
                .thenReturn(new PageResult(List.of(), 0));

        context.service.list(
                12L, "external-key", DocumentProcessingRunStatus.FAILED,
                true, 2, 10);

        verify(context.repository).listPage(
                12L, DocumentProcessingRunStatus.FAILED, true,
                List.of(91L), 10, 20);
    }

    @Test
    void listRejectsCollectionKeyThatResolvesToNoAuthorizedScope() {
        Context context = context();
        when(context.scopeResolver.resolve(
                eq(CollectionScopeMode.SELECTED_COLLECTIONS), isNull(),
                eq(List.of("forbidden-key")), isNull(), isNull(), isNull()))
                .thenReturn(RetrievalScope.noMatches());

        assertThrows(SecurityException.class,
                () -> context.service.list(
                        null, "forbidden-key", null, null, 0, 20));
        verify(context.repository, never()).listPage(
                any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void cancelActiveRunCancelsAndMirrorsEmbeddingJob() {
        Context context = context();
        UUID runId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        DocumentProcessingRun active = run(
                runId, jobId, DocumentProcessingRunStatus.RUNNING,
                CONTENT_HASH, null, false);
        DocumentProcessingRun cancelled = run(
                runId, jobId, DocumentProcessingRunStatus.CANCELLED,
                CONTENT_HASH, null, false);
        RagDocument document = document(CONTENT_HASH, true);
        EmbeddingJob cancelledJob = job(jobId, EmbeddingJobStatus.CANCELLED);
        when(context.repository.find(runId))
                .thenReturn(Optional.of(active), Optional.of(cancelled));
        when(context.documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
        when(context.jobRepository.find(jobId)).thenReturn(Optional.of(cancelledJob));

        DocumentProcessingRunResponse response = context.service.cancel(runId);

        assertEquals(DocumentProcessingRunStatus.CANCELLED, response.status());
        verify(context.jobRepository).cancel(jobId);
        verify(context.repository).mirrorJob(cancelledJob);
    }

    @Test
    void cancelTerminalRunReturnsSnapshotWithoutCancellingJobAgain() {
        Context context = context();
        UUID runId = UUID.randomUUID();
        DocumentProcessingRun completed = run(
                runId, UUID.randomUUID(), DocumentProcessingRunStatus.COMPLETED,
                CONTENT_HASH, null, false);
        when(context.repository.find(runId)).thenReturn(Optional.of(completed));
        when(context.documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document(CONTENT_HASH, true)));

        DocumentProcessingRunResponse response = context.service.cancel(runId);

        assertEquals(DocumentProcessingRunStatus.COMPLETED, response.status());
        verify(context.jobRepository, never()).cancel(any());
    }

    @Test
    void retryCreatesNewGenerationAndSupersedesFailedRun() {
        Context context = context();
        UUID sourceId = UUID.randomUUID();
        UUID newJobId = UUID.randomUUID();
        UUID newRunId = UUID.randomUUID();
        DocumentProcessingRun source = run(
                sourceId, UUID.randomUUID(), DocumentProcessingRunStatus.FAILED,
                CONTENT_HASH, null, false);
        DocumentProcessingRun target = run(
                newRunId, newJobId, DocumentProcessingRunStatus.QUEUED,
                CONTENT_HASH, sourceId, false, 10);
        RagDocument document = document(CONTENT_HASH, true);
        EmbeddingJob newJob = job(newJobId, EmbeddingJobStatus.QUEUED);
        when(context.repository.find(sourceId)).thenReturn(Optional.of(source));
        when(context.documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
        when(context.jobRepository.findCurrentActive(
                DOCUMENT_ID, PROFILE_ID, CONTENT_HASH, "TEXT", CHUNKER_VERSION))
                .thenReturn(Optional.empty());
        when(context.jobRepository.allocateGeneration(
                DOCUMENT_ID, PROFILE_ID, CONTENT_HASH, CHUNKER_VERSION, false))
                .thenReturn(8L);
        when(context.jobRepository.createOrCoalesce(
                any(UUID.class), eq(DOCUMENT_ID), eq(PROFILE_ID),
                eq(CONTENT_HASH), eq(2L), eq(true), eq(10), eq("API"),
                eq(ChatPrincipal.AUTH_DISABLED_ID), eq(8L), eq("TEXT"),
                eq(CHUNKER_VERSION)))
                .thenReturn(new EmbeddingJobRepository.CreateResult(newJob, false));
        when(context.repository.ensureForJob(
                eq(newJob), eq("API"), eq(ChatPrincipal.AUTH_DISABLED_ID),
                eq(sourceId), eq(false)))
                .thenReturn(target);

        DocumentProcessingRunResponse response = context.service.retry(sourceId, 99);

        assertEquals(newRunId, response.runId());
        assertEquals(DocumentProcessingRunStatus.QUEUED, response.status());
        assertEquals(10, response.maxAttempts());
        assertEquals(Boolean.FALSE, response.coalesced());
        verify(context.jobRepository).cancelSuperseded(DOCUMENT_ID, PROFILE_ID, 8L);
        verify(context.repository).markSuperseded(DOCUMENT_ID, PROFILE_ID, 8L);
        verify(context.jobRepository).activateJob(
                DOCUMENT_ID, PROFILE_ID, 8L, newJobId);
    }

    @Test
    void retryWithoutAttemptOverridePreservesSourceLimit() {
        Context context = context();
        UUID sourceId = UUID.randomUUID();
        UUID newJobId = UUID.randomUUID();
        UUID newRunId = UUID.randomUUID();
        DocumentProcessingRun source = run(
                sourceId, UUID.randomUUID(), DocumentProcessingRunStatus.FAILED,
                CONTENT_HASH, null, false, 4);
        DocumentProcessingRun target = run(
                newRunId, newJobId, DocumentProcessingRunStatus.QUEUED,
                CONTENT_HASH, sourceId, false, 4);
        RagDocument document = document(CONTENT_HASH, true);
        EmbeddingJob newJob = job(newJobId, EmbeddingJobStatus.QUEUED);
        when(context.repository.find(sourceId)).thenReturn(Optional.of(source));
        when(context.documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
        when(context.jobRepository.findCurrentActive(
                DOCUMENT_ID, PROFILE_ID, CONTENT_HASH, "TEXT", CHUNKER_VERSION))
                .thenReturn(Optional.empty());
        when(context.jobRepository.allocateGeneration(
                DOCUMENT_ID, PROFILE_ID, CONTENT_HASH, CHUNKER_VERSION, false))
                .thenReturn(9L);
        when(context.jobRepository.createOrCoalesce(
                any(UUID.class), eq(DOCUMENT_ID), eq(PROFILE_ID),
                eq(CONTENT_HASH), eq(2L), eq(true), eq(4), eq("API"),
                eq(ChatPrincipal.AUTH_DISABLED_ID), eq(9L), eq("TEXT"),
                eq(CHUNKER_VERSION)))
                .thenReturn(new EmbeddingJobRepository.CreateResult(newJob, false));
        when(context.repository.ensureForJob(
                eq(newJob), eq("API"), eq(ChatPrincipal.AUTH_DISABLED_ID),
                eq(sourceId), eq(false)))
                .thenReturn(target);

        DocumentProcessingRunResponse response = context.service.retry(sourceId, null);

        assertEquals(4, response.maxAttempts());
        verify(context.jobRepository).createOrCoalesce(
                any(UUID.class), eq(DOCUMENT_ID), eq(PROFILE_ID),
                eq(CONTENT_HASH), eq(2L), eq(true), eq(4), eq("API"),
                eq(ChatPrincipal.AUTH_DISABLED_ID), eq(9L), eq("TEXT"),
                eq(CHUNKER_VERSION));
    }

    @Test
    void retryCoalescesWithExistingActiveJob() {
        Context context = context();
        UUID sourceId = UUID.randomUUID();
        UUID activeJobId = UUID.randomUUID();
        DocumentProcessingRun source = run(
                sourceId, UUID.randomUUID(), DocumentProcessingRunStatus.FAILED,
                CONTENT_HASH, null, false);
        DocumentProcessingRun activeRun = run(
                UUID.randomUUID(), activeJobId, DocumentProcessingRunStatus.QUEUED,
                CONTENT_HASH, null, true);
        RagDocument document = document(CONTENT_HASH, true);
        when(context.repository.find(sourceId)).thenReturn(Optional.of(source));
        when(context.documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
        when(context.jobRepository.findCurrentActive(
                DOCUMENT_ID, PROFILE_ID, CONTENT_HASH, "TEXT", CHUNKER_VERSION))
                .thenReturn(Optional.of(job(activeJobId, EmbeddingJobStatus.QUEUED)));
        when(context.repository.findByJobId(activeJobId))
                .thenReturn(Optional.of(activeRun));

        DocumentProcessingRunResponse response = context.service.retry(sourceId, null);

        assertEquals(activeRun.id(), response.runId());
        assertEquals(Boolean.TRUE, response.coalesced());
        verify(context.jobRepository, never()).allocateGeneration(
                anyLong(), anyLong(), anyString(), anyString(), anyBoolean());
        verify(context.repository, never()).markSuperseded(
                anyLong(), anyLong(), anyLong());
    }

    @Test
    void retryRejectsActiveRunsAndChangedSnapshots() {
        Context context = context();
        UUID runId = UUID.randomUUID();
        DocumentProcessingRun active = run(
                runId, UUID.randomUUID(), DocumentProcessingRunStatus.RUNNING,
                CONTENT_HASH, null, false);
        when(context.repository.find(runId)).thenReturn(Optional.of(active));
        when(context.documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document(CONTENT_HASH, true)));

        assertThrows(RagException.class, () -> context.service.retry(runId, null));
        verify(context.documentRepository).findById(DOCUMENT_ID);

        DocumentProcessingRun failed = run(
                runId, UUID.randomUUID(), DocumentProcessingRunStatus.FAILED,
                CONTENT_HASH, null, false);
        when(context.repository.find(runId)).thenReturn(Optional.of(failed));
        when(context.documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document("changed-content-hash", true)));
        when(context.repository.findCurrent(
                DOCUMENT_ID, PROFILE_ID, 0L, "changed-content-hash", CHUNKER_VERSION))
                .thenReturn(Optional.empty());
        when(context.repository.findByDocumentLatest(DOCUMENT_ID, PROFILE_ID))
                .thenReturn(Optional.of(failed));

        assertThrows(RagException.class, () -> context.service.retry(runId, null));
        verify(context.jobRepository, never()).allocateGeneration(
                anyLong(), anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void retryRejectsDisabledDocumentAndStaleRunWithCurrentJob() {
        Context context = context();
        UUID runId = UUID.randomUUID();
        DocumentProcessingRun failed = run(
                runId, UUID.randomUUID(), DocumentProcessingRunStatus.FAILED,
                CONTENT_HASH, null, false);
        when(context.repository.find(runId)).thenReturn(Optional.of(failed));
        when(context.documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document(CONTENT_HASH, false)));

        assertThrows(RagException.class, () -> context.service.retry(runId, null));
        verify(context.profileProvider, never()).getActiveProfile();

        DocumentProcessingRun stale = run(
                runId, UUID.randomUUID(), DocumentProcessingRunStatus.STALE,
                CONTENT_HASH, null, false);
        EmbeddingJob activeJob = job(EmbeddingJobStatus.QUEUED);
        when(context.repository.find(runId)).thenReturn(Optional.of(stale));
        when(context.documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document(CONTENT_HASH, true)));
        when(context.jobRepository.findCurrentActive(
                DOCUMENT_ID, PROFILE_ID, CONTENT_HASH, "TEXT", CHUNKER_VERSION))
                .thenReturn(Optional.of(activeJob));

        assertThrows(RagException.class, () -> context.service.retry(runId, null));
        verify(context.jobRepository, never()).allocateGeneration(
                anyLong(), anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void disabledLedgerRejectsReadActionsWithoutTouchingRepositories() {
        Context context = context(false);
        UUID runId = UUID.randomUUID();

        assertThrows(RagException.class, () -> context.service.get(runId));
        assertThrows(RagException.class,
                () -> context.service.list(null, null, null, null, 0, 20));
        assertThrows(RagException.class, () -> context.service.cancel(runId));
        assertNull(context.service.ensureForJob(
                job(EmbeddingJobStatus.QUEUED), "API", null, false));
        assertNull(context.service.ensureBridgeForJob(job(EmbeddingJobStatus.QUEUED)));
        assertTrue(context.service.ensureBridgesForJobs(List.of()).isEmpty());
        assertNull(context.service.findRunIdByJobId(UUID.randomUUID()));
        assertTrue(context.service.findRunIdsByJobIds(List.of()).isEmpty());
        assertEquals(0, context.service.markSuperseded(1L, 2L, 3L));
        verify(context.repository, never()).find(runId);
        verify(context.repository, never()).ensureForJob(
                any(), anyString(), anyString(), any(), anyBoolean());
    }

    private DocumentProcessingRunService service(
            DocumentProcessingRunRepository repository,
            RagProperties properties) {
        return new DocumentProcessingRunService(
                repository,
                mock(EmbeddingJobRepository.class),
                mock(RagDocumentRepository.class),
                mock(EmbeddingProfileProvider.class),
                new DocumentDerivationDescriptorProvider(properties),
                mock(CollectionRetrievalScopeResolver.class),
                properties);
    }

    private Context context() {
        return context(true);
    }

    private Context context(boolean enabled) {
        DocumentProcessingRunRepository repository =
                mock(DocumentProcessingRunRepository.class);
        EmbeddingJobRepository jobRepository = mock(EmbeddingJobRepository.class);
        RagDocumentRepository documentRepository = mock(RagDocumentRepository.class);
        EmbeddingProfileProvider profileProvider = mock(EmbeddingProfileProvider.class);
        CollectionRetrievalScopeResolver scopeResolver =
                mock(CollectionRetrievalScopeResolver.class);
        RagProperties properties = new RagProperties();
        properties.getDocumentProcessing().setEnabled(enabled);
        when(profileProvider.getActiveProfile()).thenReturn(profile());
        DocumentProcessingRunService service = new DocumentProcessingRunService(
                repository,
                jobRepository,
                documentRepository,
                profileProvider,
                new DocumentDerivationDescriptorProvider(properties),
                scopeResolver,
                properties);
        return new Context(service, repository, jobRepository,
                documentRepository, scopeResolver, profileProvider);
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                PROFILE_ID, "test-profile", "test", "model", "revision",
                1024, "COSINE", "NONE", true);
    }

    private RagDocument document(String contentHash, boolean enabled) {
        RagDocument document = new RagDocument();
        document.setId(DOCUMENT_ID);
        document.setCollectionId(91L);
        document.setVersion(2L);
        document.setContentHash(contentHash);
        document.setDerivationChunkerVersion(CHUNKER_VERSION);
        document.setDocumentType("text");
        document.setEnabled(enabled);
        return document;
    }

    private DocumentProcessingRun run(
            UUID runId,
            UUID jobId,
            DocumentProcessingRunStatus status,
            String contentHash,
            UUID supersedesRunId,
            boolean legacyBridge) {
        return run(runId, jobId, status, contentHash, supersedesRunId,
                legacyBridge, 3);
    }

    private DocumentProcessingRun run(
            UUID runId,
            UUID jobId,
            DocumentProcessingRunStatus status,
            String contentHash,
            UUID supersedesRunId,
            boolean legacyBridge,
            int maxAttempts) {
        OffsetDateTime startedAt = status == DocumentProcessingRunStatus.RUNNING
                ? NOW.minusMinutes(1) : null;
        OffsetDateTime finishedAt = status == DocumentProcessingRunStatus.COMPLETED
                || status == DocumentProcessingRunStatus.CANCELLED
                || status == DocumentProcessingRunStatus.FAILED
                || status == DocumentProcessingRunStatus.DEAD_LETTERED
                ? NOW : null;
        return new DocumentProcessingRun(
                runId, DOCUMENT_ID, PROFILE_ID, jobId, 4L, 2L,
                contentHash, "TEXT", CHUNKER_VERSION,
                1, 2, maxAttempts, status,
                status == DocumentProcessingRunStatus.RUNNING ? "EMBEDDING" : null,
                null, NOW,
                status == DocumentProcessingRunStatus.CANCELLED ? NOW : null,
                status == DocumentProcessingRunStatus.DEAD_LETTERED ? NOW : null,
                status == DocumentProcessingRunStatus.DEAD_LETTERED ? "attempts exhausted" : null,
                status == DocumentProcessingRunStatus.FAILED ? "MODEL_ERROR" : null,
                status == DocumentProcessingRunStatus.FAILED ? "temporary failure" : null,
                DocumentProcessingRunOrigin.API, ChatPrincipal.AUTH_DISABLED_ID,
                supersedesRunId, legacyBridge, NOW.minusMinutes(2), startedAt,
                finishedAt, NOW);
    }

    private EmbeddingJob job(UUID id, EmbeddingJobStatus status) {
        return new EmbeddingJob(
                id, UUID.randomUUID(), DOCUMENT_ID, PROFILE_ID, true,
                CONTENT_HASH, 2L, status, 1, 3, NOW, null, null, null, null,
                NOW, null, null, NOW, "API", ChatPrincipal.AUTH_DISABLED_ID,
                4L, "TEXT", CHUNKER_VERSION, null);
    }

    private record Context(
            DocumentProcessingRunService service,
            DocumentProcessingRunRepository repository,
            EmbeddingJobRepository jobRepository,
            RagDocumentRepository documentRepository,
            CollectionRetrievalScopeResolver scopeResolver,
            EmbeddingProfileProvider profileProvider) {
    }

    private EmbeddingJob job(EmbeddingJobStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new EmbeddingJob(
                UUID.randomUUID(), UUID.randomUUID(), 42L, 7L, false,
                "hash", 1L, status, 1, 3, now, null, null, null, null,
                now, null, null, now, "API", "local:auth-disabled", 1L,
                "TEXT", "legacy-compatible", null);
    }
}
