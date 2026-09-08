package com.springairag.core.embeddingjob;

import com.springairag.core.embeddingjob.EmbeddingJobStatus;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.api.dto.EmbeddingJobPageResponse;
import com.springairag.api.dto.EmbeddingJobResponse;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.DocumentEmbedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 嵌入任务查询与维护面：get 的存在性与映射、listPage 的分页钳制与
 * 总页数计算、cancel 的更新回退语义。
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingJobQueryPageTest {

    @Mock EmbeddingJobRepository jobRepository;
    @Mock RagDocumentRepository documentRepository;
    @Mock CollectionRetrievalScopeResolver scopeResolver;
    @Mock EmbeddingProfileProvider profileProvider;
    @Mock DocumentEmbedService documentEmbedService;

    private RagProperties properties;
    private EmbeddingJobService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        DocumentDerivationDescriptorProvider descriptorProvider =
                new DocumentDerivationDescriptorProvider(properties);
        service = new EmbeddingJobService(
                jobRepository,
                documentRepository,
                scopeResolver,
                profileProvider,
                properties,
                descriptorProvider,
                documentEmbedService);
    }

    private EmbeddingJob job() {
        OffsetDateTime now = OffsetDateTime.now();
        return new EmbeddingJob(
                UUID.randomUUID(), UUID.randomUUID(), 7L, 9L, false,
                "0123456789abcdef0123456789abcdef"
                        + "0123456789abcdef0123456789abcdef",
                4L, EmbeddingJobStatus.QUEUED, 0, 3,
                now, null, null, null, null,
                now, null, null, now, "TEST", "test", 1L,
                "TEXT", "legacy-compatible");
    }

    private void stubAuthorized(EmbeddingJob job) {
        lenientJob(job);
        RagDocument document = new RagDocument();
        document.setId(job.documentId());
        when(documentRepository.findById(job.documentId()))
                .thenReturn(Optional.of(document));
    }

    private void lenientJob(EmbeddingJob job) {
        org.mockito.Mockito.lenient()
                .when(jobRepository.find(job.id()))
                .thenReturn(Optional.of(job));
    }

    @Test
    void getReturnsMappedResponseForExistingJob() {
        EmbeddingJob job = job();
        stubAuthorized(job);

        EmbeddingJobResponse response = service.get(job.id());

        assertEquals(job.id(), response.id());
        assertEquals(7L, response.documentId());
        assertEquals("QUEUED", response.status());
    }

    @Test
    void getThrowsNotFoundForMissingJob() {
        when(jobRepository.find(any(UUID.class)))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.get(UUID.randomUUID()));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void listPageClampsOversizedSizeAndNegativePage() {
        when(jobRepository.listPage(
                isNull(), isNull(), isNull(), isNull(), eq(200), eq(0)))
                .thenReturn(new EmbeddingJobRepository.PageResult(List.of(), 0));

        EmbeddingJobPageResponse response = service.listPage(
                null, null, null, -3, 500);

        assertEquals(0, response.page());
        assertEquals(200, response.size());
        assertEquals(0, response.totalPages());
    }

    @Test
    void listPageComputesTotalPagesFromElementCount() {
        EmbeddingJob job = job();
        when(jobRepository.listPage(
                isNull(), isNull(), isNull(), isNull(), eq(50), eq(0)))
                .thenReturn(new EmbeddingJobRepository.PageResult(
                        List.of(job), 450));

        EmbeddingJobPageResponse response = service.listPage(
                null, null, null, 0, 50);

        assertEquals(1, response.items().size());
        assertEquals(450, response.totalElements());
        // ceil(450/50) = 9 页。
        assertEquals(9, response.totalPages());
    }

    @Test
    void cancelPrefersTheUpdatedJobAndFallsBackToCurrent() {
        EmbeddingJob current = job();
        stubAuthorized(current);
        EmbeddingJob cancelled = new EmbeddingJob(
                current.id(), current.batchId(), current.documentId(),
                current.embeddingProfileId(), current.force(),
                current.contentHash(), current.documentVersion(),
                EmbeddingJobStatus.CANCELLED, current.attemptCount(),
                current.maxAttempts(), current.availableAt(),
                current.leaseOwner(), current.leaseExpiresAt(),
                current.cancelRequestedAt(), current.lastError(),
                current.createdAt(), current.startedAt(),
                current.finishedAt(), current.updatedAt(), current.origin(),
                current.requestedByPrincipalId(),
                current.requestGeneration(), current.documentKind(),
                current.chunkerVersion());
        when(jobRepository.cancel(current.id()))
                .thenReturn(Optional.of(cancelled));

        EmbeddingJobResponse response = service.cancel(current.id());

        assertEquals("CANCELLED", response.status());

        // 取消落空（例如已终态）时回传当前快照而不是抛错。
        when(jobRepository.cancel(current.id()))
                .thenReturn(Optional.empty());
        EmbeddingJobResponse fallback = service.cancel(current.id());
        assertEquals(current.id(), fallback.id());
    }
}
