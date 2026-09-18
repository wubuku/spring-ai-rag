package com.springairag.core.embeddingjob;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.DocumentEmbedService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmbeddingJobService.retry 重试长尾（Batch 514，JaCoCo 驱动）：
 * 重试成功转 QUEUED 并发布唤醒、DataIntegrityViolationException 竞
 * 争时经 activeRetryTarget 兜底、无兜底目标时异常透出。
 */
class EmbeddingJobRetryRaceTailTest {

    private RagDocumentRepository documentRepository;
    private EmbeddingJobRepository jobRepository;
    private EmbeddingJobWakeupPublisher wakeupPublisher;
    private EmbeddingJobService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        jobRepository = mock(EmbeddingJobRepository.class);
        var scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        var profileProvider = mock(com.springairag.core.config.EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(profile());
        var documentEmbedService = mock(DocumentEmbedService.class);
        wakeupPublisher = mock(EmbeddingJobWakeupPublisher.class);
        var properties = new RagProperties();
        properties.getEmbeddingJobs().setEnabled(true);
        service = new EmbeddingJobService(
                jobRepository,
                documentRepository,
                scopeResolver,
                profileProvider,
                properties,
                new DocumentDerivationDescriptorProvider(properties),
                documentEmbedService);
        service.setWakeupPublisher(wakeupPublisher);

        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/embedding-jobs");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private com.springairag.core.config.EmbeddingProfile profile() {
        return new com.springairag.core.config.EmbeddingProfile(
                9L, "bge-m3", "vendor", "bge-m3", "rev-1",
                1024, "cosine", "normalize", true);
    }

    private EmbeddingJob job(UUID id, EmbeddingJobStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new EmbeddingJob(
                id, UUID.randomUUID(), 42L, 9L, false,
                "abc123", 1L, status, 2, 5,
                now, null, null, null, null, now,
                null, null, now, "EXTERNAL", "principal-1",
                1L, "TEXT", "chunker-v1");
    }

    private RagDocument document() {
        var document = new RagDocument();
        document.setId(42L);
        document.setEnabled(Boolean.TRUE);
        document.setContentHash(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        return document;
    }

    @Test
    void retrySucceedsTransitionsToQueuedAndPublishes() {
        UUID id = UUID.randomUUID();
        EmbeddingJob failed = job(id, EmbeddingJobStatus.FAILED);
        EmbeddingJob queued = new EmbeddingJob(
                failed.id(), failed.batchId(), failed.documentId(),
                failed.embeddingProfileId(), failed.force(),
                failed.contentHash(), failed.documentVersion(),
                EmbeddingJobStatus.QUEUED, 0, failed.maxAttempts(),
                failed.availableAt(), null, null, null, null,
                failed.createdAt(), null, null, OffsetDateTime.now(),
                "EXTERNAL", "principal-1", 1L, "TEXT", "chunker-v1");
        when(jobRepository.find(id)).thenReturn(java.util.Optional.of(failed));
        when(documentRepository.findById(42L))
                .thenReturn(java.util.Optional.of(document()));
        when(jobRepository.retry(id, 5))
                .thenReturn(java.util.Optional.of(queued));

        var response = service.retry(id, null);

        assertEquals("QUEUED", response.status());
        verify(wakeupPublisher).publishAfterCommit();
    }

    @Test
    void retryRaceWithActiveTargetFallsBack() {
        UUID id = UUID.randomUUID();
        EmbeddingJob failed = job(id, EmbeddingJobStatus.FAILED);
        EmbeddingJob active = job(UUID.randomUUID(), EmbeddingJobStatus.QUEUED);
        when(jobRepository.find(id)).thenReturn(java.util.Optional.of(failed));
        when(documentRepository.findById(42L))
                .thenReturn(java.util.Optional.of(document()));
        when(jobRepository.retry(id, 5))
                .thenThrow(new DataIntegrityViolationException("race"));
        when(jobRepository.findActive(42L, 9L, "abc123"))
                .thenReturn(java.util.Optional.of(active));

        var response = service.retry(id, null);

        assertEquals("QUEUED", response.status());
    }

    @Test
    void retryRaceWithoutActiveTargetSurfacesError() {
        UUID id = UUID.randomUUID();
        EmbeddingJob failed = job(id, EmbeddingJobStatus.FAILED);
        when(jobRepository.find(id)).thenReturn(java.util.Optional.of(failed));
        when(documentRepository.findById(42L))
                .thenReturn(java.util.Optional.of(document()));
        when(jobRepository.retry(id, 5))
                .thenThrow(new DataIntegrityViolationException("race"));
        when(jobRepository.findActive(42L, 9L, "abc123"))
                .thenReturn(java.util.Optional.empty());

        DataIntegrityViolationException error = assertThrows(
                DataIntegrityViolationException.class,
                () -> service.retry(id, null));
        assertNotNull(error);
    }
}
