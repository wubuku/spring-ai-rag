package com.springairag.core.documentprocessing;

import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingJob;
import com.springairag.core.embeddingjob.EmbeddingJobRepository;
import com.springairag.core.embeddingjob.EmbeddingJobStatus;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentProcessingRunServiceTest {

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

    private EmbeddingJob job(EmbeddingJobStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new EmbeddingJob(
                UUID.randomUUID(), UUID.randomUUID(), 42L, 7L, false,
                "hash", 1L, status, 1, 3, now, null, null, null, null,
                now, null, null, now, "API", "local:auth-disabled", 1L,
                "TEXT", "legacy-compatible", null);
    }
}
