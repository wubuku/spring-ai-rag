package com.springairag.core.embeddingjob;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.DocumentEmbedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingDispatchServiceTest {

    private DocumentEmbedService embedService;
    private EmbeddingJobRepository repository;
    private EmbeddingProfileProvider profileProvider;
    private EmbeddingJobWakeupPublisher wakeupPublisher;
    private DocumentDerivationDescriptorProvider descriptorProvider;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        embedService = mock(DocumentEmbedService.class);
        repository = mock(EmbeddingJobRepository.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        wakeupPublisher = mock(EmbeddingJobWakeupPublisher.class);
        properties = new RagProperties();
        descriptorProvider =
                new DocumentDerivationDescriptorProvider(properties);
        when(profileProvider.getActiveProfile()).thenReturn(profile());
    }

    @Test
    void queuedJobPublishesAfterCommitWakeUp() {
        RagDocument document = document();
        EmbeddingJob job = job(document, EmbeddingJobStatus.QUEUED);
        when(repository.findCurrentActive(
                anyLong(), anyLong(), anyString(),
                anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(embedService.hasFreshEmbedding(document)).thenReturn(false);
        when(repository.allocateGeneration(
                anyLong(), anyLong(), anyString(),
                anyString(), anyBoolean()))
                .thenReturn(3L);
        when(repository.createOrCoalesce(
                any(UUID.class), anyLong(), anyLong(), anyString(),
                anyLong(), anyBoolean(), anyInt(), any(), any(),
                anyLong(), anyString(), anyString()))
                .thenReturn(new EmbeddingJobRepository.CreateResult(
                        job, false));
        EmbeddingDispatchService service = service();

        EmbeddingDispatchService.Result result =
                service.enqueueInCurrentTransaction(
                        document, true, false, "TEST");

        assertEquals(job.id(), result.embeddingJobId());
        verify(wakeupPublisher).publishAfterCommit();
    }

    @Test
    void freshDocumentDoesNotPublishWakeUp() {
        RagDocument document = document();
        when(repository.findCurrentActive(
                anyLong(), anyLong(), anyString(),
                anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(embedService.hasFreshEmbedding(document)).thenReturn(true);
        EmbeddingDispatchService service = service();

        EmbeddingDispatchService.Result result =
                service.enqueueInCurrentTransaction(
                        document, false, false, "TEST");

        assertEquals("NOT_REQUESTED", result.embeddingStatus());
        verify(wakeupPublisher, never()).publishAfterCommit();
    }

    private EmbeddingDispatchService service() {
        EmbeddingDispatchService service = new EmbeddingDispatchService(
                embedService,
                repository,
                profileProvider,
                properties,
                descriptorProvider,
                mock(EmbeddingJobExecutor.class));
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
                null,
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
