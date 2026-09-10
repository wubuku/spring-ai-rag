package com.springairag.core.embeddingjob;

import com.springairag.api.dto.EmbeddingJobBatchResponse;
import com.springairag.api.dto.EmbeddingJobCreateRequest;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.core.embeddingjob.EmbeddingJobStatus;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.config.EmbeddingProfileProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * resolveDocumentIds 作用域解析分支：互斥校验、documentIds 归一化
 * 边界、缺失文档、matchNone 空批、NONE/ANY_ASSIGNED/SELECTED 三种
 * 过滤器分发、超批上限拒绝。
 */
class EmbeddingJobScopeResolutionTest {

    private EmbeddingJobRepository jobRepository;
    private RagDocumentRepository documentRepository;
    private CollectionRetrievalScopeResolver scopeResolver;
    private EmbeddingProfileProvider profileProvider;
    private RagProperties properties;
    private DocumentEmbedService documentEmbedService;
    private EmbeddingJobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(EmbeddingJobRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        properties = new RagProperties();
        documentEmbedService = mock(DocumentEmbedService.class);
        service = new EmbeddingJobService(
                jobRepository,
                documentRepository,
                scopeResolver,
                profileProvider,
                properties,
                new DocumentDerivationDescriptorProvider(properties),
                documentEmbedService);
        when(profileProvider.getActiveProfile()).thenReturn(profile());
        properties.getEmbeddingJobs().setEnabled(true);
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                9L, "test", "test", "test", "v1",
                1024, "COSINE", "NONE", true);
    }

    private com.springairag.core.entity.RagDocument document(long id) {
        com.springairag.core.entity.RagDocument document =
                new com.springairag.core.entity.RagDocument();
        document.setId(id);
        document.setVersion(7L);
        document.setEnabled(true);
        document.setContentHash(
                "0123456789abcdef0123456789abcdef"
                        + "0123456789abcdef0123456789abcdef");
        return document;
    }

    private EmbeddingJobRepository jobRepositoryTyped() {
        return jobRepository;
    }

    @Test
    void bothOrNeitherOfIdsAndScopeIsRejected() {
        var neither = new EmbeddingJobCreateRequest(
                null, null, null, null, false, null);
        IllegalArgumentException neitherError = assertThrows(
                IllegalArgumentException.class, () -> service.create(neither));
        assertTrue(neitherError.getMessage().contains("Provide either"));

        var both = new EmbeddingJobCreateRequest(
                List.of(1L), CollectionScopeMode.CALLER_VISIBLE, null, null, false, null);
        assertThrows(IllegalArgumentException.class, () -> service.create(both));
    }

    @Test
    void emptyOrOversizedDocumentIdsRejected() {
        var empty = new EmbeddingJobCreateRequest(
                List.of(), null, null, null, false, null);
        IllegalArgumentException emptyError = assertThrows(
                IllegalArgumentException.class, () -> service.create(empty));
        assertTrue(emptyError.getMessage().contains("must not be empty"));

        List<Long> oversized = LongStream.rangeClosed(1, 1001)
                .mapToObj(Long::valueOf).toList();
        var oversize = new EmbeddingJobCreateRequest(
                oversized, null, null, null, false, null);
        IllegalArgumentException oversizeError = assertThrows(
                IllegalArgumentException.class, () -> service.create(oversize));
        assertTrue(oversizeError.getMessage().contains("must not contain more"));
    }

    @Test
    void idsPresentWithMissingDocumentThrowsNotFound() {
        when(documentRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class,
                () -> service.create(new EmbeddingJobCreateRequest(
                        List.of(7L), null, null, null, false, null)));
    }

    @Test
    void scopeMatchNoneYieldsEmptyBatchWithoutJobs() {
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.noMatches());

        EmbeddingJobBatchResponse response = service.create(
                new EmbeddingJobCreateRequest(
                        null, CollectionScopeMode.CALLER_VISIBLE, null,
                        List.of("hidden"), false, null));

        assertEquals(0, response.requested());
        assertTrue(response.jobs().isEmpty());
        verify(jobRepository, never()).findCurrentActive(
                anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void scopeExpandingBeyondLimitIsRejected() {
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.unscoped());
        when(documentRepository.findEnabledIds(any()))
                .thenReturn(LongStream.rangeClosed(1, 1001)
                        .mapToObj(Long::valueOf).toList());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(new EmbeddingJobCreateRequest(
                        null, CollectionScopeMode.CALLER_VISIBLE, null, null, false, null)));

        assertTrue(error.getMessage().contains("more than"));
    }

    private void stubFullCreateChain(long documentId) {
        com.springairag.core.entity.RagDocument document = document(documentId);
        String contentHash = document.getContentHash();
        String chunker = new DocumentDerivationDescriptorProvider(properties)
                .textDescriptor().chunkerVersion();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document));
        when(documentEmbedService.hasFreshEmbedding(document))
                .thenReturn(false);
        when(jobRepository.findCurrentActive(
                eq(documentId), eq(9L), eq(contentHash), eq("TEXT"),
                eq(chunker)))
                .thenReturn(Optional.empty());
        when(jobRepository.allocateGeneration(
                eq(documentId), eq(9L), eq(contentHash), eq(chunker),
                eq(false)))
                .thenReturn(1L);
        when(jobRepository.cancelSuperseded(
                eq(documentId), eq(9L), eq(1L))).thenReturn(0);
        EmbeddingJob job = new EmbeddingJob(
                UUID.randomUUID(), UUID.randomUUID(), documentId, 9L,
                false, contentHash, 7L, EmbeddingJobStatus.QUEUED, 0, 3,
                null, null, null, null, null,
                null, null, null, null, "API", "local", 1L,
                "TEXT", chunker);
        when(jobRepository.createOrCoalesce(
                any(UUID.class), eq(documentId), eq(9L), eq(contentHash),
                eq(7L), eq(false), anyInt(), eq("API"), any(String.class),
                eq(1L), eq("TEXT"), eq(chunker)))
                .thenReturn(new EmbeddingJobRepository.CreateResult(job, false));
    }

    @Test
    void scopeFilterAnyAssignedDispatchesToAssignedIds() {
        stubFullCreateChain(1L);
        when(documentRepository.findEnabledAssignedIds(any()))
                .thenReturn(List.of(1L));
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.anyAssigned(null, null));

        EmbeddingJobBatchResponse response = service.create(
                new EmbeddingJobCreateRequest(
                        null, CollectionScopeMode.CALLER_VISIBLE, null, null, false, null));

        assertEquals(1, response.requested());
        verify(documentRepository).findEnabledAssignedIds(any());
    }

    @Test
    void scopeFilterSelectedDispatchesToCollectionScopedIds() {
        stubFullCreateChain(1L);
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.selectedCollections(
                        List.of(10L), List.of(), null));
        when(documentRepository.findEnabledIdsByCollectionIds(
                any(), any())).thenReturn(List.of(1L));

        EmbeddingJobBatchResponse response = service.create(
                new EmbeddingJobCreateRequest(
                        null, CollectionScopeMode.ANY_COLLECTION, List.of(10L),
                        null, false, null));

        assertEquals(1, response.requested());
        verify(documentRepository).findEnabledIdsByCollectionIds(
                eq(List.of(10L)), any());
    }
}
