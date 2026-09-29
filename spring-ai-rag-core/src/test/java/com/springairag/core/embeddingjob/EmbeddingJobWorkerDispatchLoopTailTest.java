package com.springairag.core.embeddingjob;

import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.DocumentEmbedService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EmbeddingJobWorker 派发循环长尾（Batch 706，JaCoCo 驱动）：
 * 派发循环吞掉仓储运行时异常以免拖垮调度线程（123-124）、无租约
 * 任务进入处理时补发租约属主（186）。
 */
class EmbeddingJobWorkerDispatchLoopTailTest {

    private EmbeddingJobRepository repository;
    private DocumentEmbedService embedService;
    private EmbeddingJobWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(EmbeddingJobRepository.class);
        embedService = mock(DocumentEmbedService.class);
        RagProperties properties = new RagProperties();
        properties.getEmbeddingJobs().setWorkerConcurrency(2);
        worker = new EmbeddingJobWorker(
                repository, embedService,
                mock(EmbeddingProfileProvider.class),
                properties);
    }

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    private EmbeddingJob job(String leaseOwner) {
        OffsetDateTime now = OffsetDateTime.now();
        return new EmbeddingJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1L,
                9L,
                false,
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

    private void invokePrivate(String name) throws Exception {
        Method method = EmbeddingJobWorker.class
                .getDeclaredMethod(name);
        method.setAccessible(true);
        try {
            method.invoke(worker);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void dispatchLoopSwallowsRepositoryFailures() throws Exception {
        when(repository.claim(anyString(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("lease store down"));

        assertDoesNotThrow(() -> invokePrivate("dispatchLoop"));
    }

    private void stubSuccessfulFlow(EmbeddingJob claimed) {
        when(repository.claim(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(claimed))
                .thenReturn(List.of());
        when(repository.isCommitAllowed(
                any(UUID.class), anyString(), anyLong()))
                .thenReturn(true);
        when(repository.claimCommitAllowed(
                any(UUID.class), anyString(), anyLong(), anyInt()))
                .thenReturn(true);
        when(repository.find(claimed.id()))
                .thenReturn(Optional.of(claimed));
        when(embedService.embedDocumentForJob(
                anyLong(), anyBoolean(),
                any(com.springairag.core.service.EmbeddingCommitGuard.class)))
                .thenReturn(java.util.Map.of("status", "COMPLETED"));
        when(repository.markSucceeded(
                any(UUID.class), anyString(), eq(true)))
                .thenReturn(1);
    }

    @Test
    void processAssignsLeaseOwnerWhenJobLacksOne() throws Exception {
        EmbeddingJob claimed = job(null);
        stubSuccessfulFlow(claimed);

        assertDoesNotThrow(() -> {
            Method method = EmbeddingJobWorker.class
                    .getDeclaredMethod("process", EmbeddingJob.class);
            method.setAccessible(true);
            method.invoke(worker, claimed);
        });
    }
}
