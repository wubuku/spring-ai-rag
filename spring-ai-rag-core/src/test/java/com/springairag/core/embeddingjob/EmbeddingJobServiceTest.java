package com.springairag.core.embeddingjob;

import com.springairag.api.dto.CollectionEmbeddingReadinessResponse;
import com.springairag.api.dto.EmbeddingJobBatchResponse;
import com.springairag.api.dto.EmbeddingJobCreateRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingJobServiceTest {

    private EmbeddingJobRepository jobRepository;
    private RagDocumentRepository documentRepository;
    private CollectionRetrievalScopeResolver scopeResolver;
    private EmbeddingProfileProvider profileProvider;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        jobRepository = mock(EmbeddingJobRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        properties = new RagProperties();
    }

    @Test
    void disabledFeatureRejectsCreateWithoutPersistingJobs() {
        EmbeddingJobService service = service();

        RagException error = assertThrows(
                RagException.class,
                () -> service.create(new EmbeddingJobCreateRequest(
                        List.of(1L), null, null, null, false, null)));

        assertEquals(ErrorCode.EMBEDDING_JOBS_DISABLED,
                error.getErrorCodeEnum());
    }

    @Test
    void createsAndCoalescesAgainstCapturedDocumentSnapshot() {
        properties.getEmbeddingJobs().setEnabled(true);
        RagDocument first = document(1L);
        RagDocument second = document(2L);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(first));
        when(documentRepository.findById(2L)).thenReturn(Optional.of(second));
        when(profileProvider.getActiveProfile()).thenReturn(profile());
        when(jobRepository.createOrCoalesce(
                any(UUID.class),
                anyLong(),
                anyLong(),
                anyString(),
                anyLong(),
                anyBoolean(),
                anyInt(),
                any(),
                any()))
                .thenAnswer(invocation -> {
                    long documentId = invocation.getArgument(1);
                    UUID batch = invocation.getArgument(0);
                    return new EmbeddingJobRepository.CreateResult(
                            job(documentId, batch),
                            documentId == 2L);
                });

        EmbeddingJobBatchResponse response = service().create(
                new EmbeddingJobCreateRequest(
                        List.of(1L, 2L),
                        null,
                        null,
                        null,
                        true,
                        4));

        assertEquals(2, response.requested());
        assertEquals(1, response.created());
        assertEquals(1, response.coalesced());
        assertTrue(response.jobs().get(1).coalesced());
    }

    @Test
    void rejectsAmbiguousDocumentAndCollectionSelectors() {
        properties.getEmbeddingJobs().setEnabled(true);

        assertThrows(IllegalArgumentException.class,
                () -> service().create(new EmbeddingJobCreateRequest(
                        List.of(1L),
                        com.springairag.api.enums.CollectionScopeMode
                                .CALLER_VISIBLE,
                        null,
                        null,
                        false,
                        null)));
    }

    @Test
    void retryReturnsExistingActiveJobAsCoalesced() {
        properties.getEmbeddingJobs().setEnabled(true);
        UUID failedId = UUID.randomUUID();
        EmbeddingJob failed = job(1L, UUID.randomUUID());
        failed = new EmbeddingJob(
                failedId,
                failed.batchId(),
                failed.documentId(),
                failed.embeddingProfileId(),
                failed.force(),
                failed.contentHash(),
                failed.documentVersion(),
                EmbeddingJobStatus.FAILED,
                failed.attemptCount(),
                failed.maxAttempts(),
                failed.availableAt(),
                null,
                null,
                null,
                "provider failed",
                failed.createdAt(),
                failed.startedAt(),
                OffsetDateTime.now(),
                OffsetDateTime.now());
        EmbeddingJob active = job(1L, UUID.randomUUID());
        when(jobRepository.find(failedId)).thenReturn(Optional.of(failed));
        when(documentRepository.findById(1L))
                .thenReturn(Optional.of(document(1L)));
        when(jobRepository.retry(failedId, failed.maxAttempts()))
                .thenReturn(Optional.empty());
        when(jobRepository.findActive(
                failed.documentId(),
                failed.embeddingProfileId(),
                failed.contentHash()))
                .thenReturn(Optional.of(active));

        var response = service().retry(failedId, null);

        assertEquals(active.id(), response.id());
        assertTrue(response.coalesced());
    }

    @Test
    void loweringMaxAttemptsAlsoClampsDefault() {
        properties.getEmbeddingJobs().setDefaultMaxAttempts(5);
        properties.getEmbeddingJobs().setMaxAttempts(2);

        assertEquals(2,
                properties.getEmbeddingJobs().getDefaultMaxAttempts());
    }

    @Test
    void readinessRequiresCollectionKeyAndDelegatesAfterAclResolve() {
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.selectedCollections(
                        List.of(5L), List.of(), null));
        when(profileProvider.getActiveProfile()).thenReturn(profile());
        CollectionEmbeddingReadinessResponse expected =
                new CollectionEmbeddingReadinessResponse(
                        "customer-42:manual:v3", "test", 3, 1, 1, 0, 0, 1);
        when(jobRepository.readiness(5L, "customer-42:manual:v3", profile()))
                .thenReturn(expected);

        assertEquals(expected, service().readiness("customer-42:manual:v3"));
        assertThrows(IllegalArgumentException.class, () -> service().readiness(" "));
    }

    @Test
    void readinessRejectsUnauthorizedCollection() {
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.noMatches());

        assertThrows(SecurityException.class,
                () -> service().readiness("hidden-collection"));
    }

    private EmbeddingJobService service() {
        return new EmbeddingJobService(
                jobRepository,
                documentRepository,
                scopeResolver,
                profileProvider,
                properties);
    }

    private RagDocument document(long id) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setVersion(7L);
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

    private EmbeddingJob job(long documentId, UUID batchId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new EmbeddingJob(
                UUID.randomUUID(),
                batchId,
                documentId,
                9L,
                true,
                document(documentId).getContentHash(),
                7L,
                EmbeddingJobStatus.QUEUED,
                0,
                4,
                now,
                null,
                null,
                null,
                null,
                now,
                null,
                null,
                now);
    }
}
