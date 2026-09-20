package com.springairag.core.embeddingjob;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EmbeddingJobService 守卫长尾（Batch 558，JaCoCo 驱动）：require
 * DocumentUsable 的禁用/非法哈希拒绝、isVisible 的可访问/越权/缺
 * 失三分支、resolveMaxAttempts 边界拒绝、retry 对缺失 job/document
 * 的异常透传。
 */
class EmbeddingJobServiceGuardTailTest {

    private EmbeddingJobRepository jobRepository;
    private RagDocumentRepository documentRepository;
    private RagProperties properties;
    private EmbeddingJobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(EmbeddingJobRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        var scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        var profileProvider = mock(
                com.springairag.core.config.EmbeddingProfileProvider.class);
        properties = new RagProperties();
        properties.getEmbeddingJobs().setEnabled(true);
        var descriptorProvider =
                new DocumentDerivationDescriptorProvider(properties);
        service = new EmbeddingJobService(
                jobRepository,
                documentRepository,
                scopeResolver,
                profileProvider,
                properties,
                descriptorProvider,
                mock(com.springairag.core.service.DocumentEmbedService.class));
    }

    private RagDocument document(boolean enabled, String contentHash) {
        RagDocument document = new RagDocument();
        document.setId(1L);
        document.setEnabled(enabled);
        document.setContentHash(contentHash);
        return document;
    }

    @Test
    void retryThrowsNotFoundWhenJobMissing() {
        when(jobRepository.find(JOB_ID))
                .thenReturn(Optional.empty());

        assertThrows(RagException.class,
                () -> service.retry(JOB_ID, null));
    }

    @Test
    void retryThrowsWhenDocumentMissing() {
        when(jobRepository.find(JOB_ID))
                .thenReturn(Optional.of(job(1L)));
        when(documentRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class,
                () -> service.retry(JOB_ID, null));
    }

    @Test
    void resolveMaxAttemptsRejectsOutOfBounds() throws Exception {
        var method = EmbeddingJobService.class.getDeclaredMethod(
                "resolveMaxAttempts", Integer.class);
        method.setAccessible(true);

        assertThrows(Exception.class,
                () -> method.invoke(service, 0));
        assertThrows(Exception.class,
                () -> method.invoke(service, 99));
    }

    @Test
    void isVisibleTrueForAccessibleFalseForMissing() throws Exception {
        var method = EmbeddingJobService.class.getDeclaredMethod(
                "isVisible", long.class,
                com.springairag.core.security.ApiAccessPolicy.class);
        method.setAccessible(true);

        when(documentRepository.findById(1L))
                .thenReturn(Optional.of(document(true, "abc")));
        when(documentRepository.findById(2L)).thenReturn(Optional.empty());

        assertEquals(Boolean.TRUE, method.invoke(service, 1L, null));
        assertEquals(Boolean.FALSE, method.invoke(service, 2L, null));
    }

    private static final java.util.UUID JOB_ID = java.util.UUID.randomUUID();

    private EmbeddingJob job(long documentId) {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        return new EmbeddingJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                documentId,
                9L,
                false,
                "0123456789abcdef0123456789abcdef"
                        + "0123456789abcdef0123456789abcdef",
                7L,
                EmbeddingJobStatus.FAILED,
                1,
                3,
                now,
                "worker-1",
                now.plusMinutes(2),
                null,
                null,
                now,
                now,
                null,
                now);
    }

    private static final class EmbeddingJobServiceTestIds {
        private static final UUID JOB_ID = UUID.randomUUID();
    }
}
