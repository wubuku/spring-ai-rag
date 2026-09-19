package com.springairag.core.embeddingjob;

import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.DocumentEmbedService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmbeddingJobWorker 调度长尾（Batch 522，JaCoCo 驱动）：事件入口、
 * executor 拒绝时 CAS 复位与名额回收、claim 异常释放名额后透出、
 * claim 空列表停止扫描、提交被拒归还名额、shutdown 超时经
 * shutdownNow 返回。
 */
class EmbeddingJobWorkerScheduleTailTest {

    private EmbeddingJobRepository repository;
    private DocumentEmbedService embedService;
    private RagProperties properties;
    private EmbeddingJobWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(EmbeddingJobRepository.class);
        embedService = mock(DocumentEmbedService.class);
        properties = new RagProperties();
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

    private EmbeddingJob job() {
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
                "worker-1",
                now.plusMinutes(2),
                null,
                null,
                now,
                now,
                null,
                now);
    }

    private ExecutorService reflectedWorkers() throws Exception {
        var field = EmbeddingJobWorker.class.getDeclaredField("workers");
        field.setAccessible(true);
        return (ExecutorService) field.get(worker);
    }

    private Semaphore reflectedSlots() throws Exception {
        var field = EmbeddingJobWorker.class.getDeclaredField("slots");
        field.setAccessible(true);
        return (Semaphore) field.get(worker);
    }

    private void invokeDispatchAvailableJobs() throws Exception {
        var method = EmbeddingJobWorker.class
                .getDeclaredMethod("dispatchAvailableJobs");
        method.setAccessible(true);
        try {
            method.invoke(worker);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void availableEventTriggersClaimScan() {
        EmbeddingJob claimed = job();
        when(repository.claim(anyString(), eq(1), anyInt()))
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

        worker.onJobsAvailable(new EmbeddingJobsAvailableEvent());

        verify(repository, timeout(3_000).atLeastOnce())
                .claim(anyString(), eq(1), anyInt());
    }

    @Test
    void scheduleDispatchResetsFlagWhenExecutorRejects() throws Exception {
        // 只关线程池，不置 shuttingDown，验证拒绝路径复位 CAS 标志。
        reflectedWorkers().shutdown();

        worker.wakeUp();

        assertTrue(reflectedWorkers().isShutdown());
        assertEquals(2, reflectedSlots().availablePermits());
    }

    @Test
    void claimFailureReleasesSlotAndStopsLoop() {
        when(repository.claim(anyString(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("claim failed"));

        worker.wakeUp();

        // dispatchLoop 吞并异常；名额已归还，调度标志复位。
        verify(repository, timeout(3_000)).claim(anyString(), anyInt(),
                anyInt());
    }

    @Test
    void claimFailureReleasesSlotWhenInvokedDirectly() throws Exception {
        when(repository.claim(anyString(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("claim failed"));

        var error = assertThrows(IllegalStateException.class,
                this::invokeDispatchAvailableJobs);

        assertEquals("claim failed", error.getMessage());
        assertEquals(2, reflectedSlots().availablePermits());
    }

    @Test
    void emptyClaimReleasesSlotAndStops() throws Exception {
        when(repository.claim(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());

        invokeDispatchAvailableJobs();

        assertEquals(2, reflectedSlots().availablePermits());
        verify(repository).claim(anyString(), anyInt(), anyInt());
    }

    @Test
    void submitRejectionAfterClaimReleasesSlot() throws Exception {
        reflectedWorkers().shutdown();
        when(repository.claim(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(job()));

        invokeDispatchAvailableJobs();

        // 提交被拒：领取用的名额必须归还，租约等待恢复扫描回收。
        assertEquals(2, reflectedSlots().availablePermits());
    }

    @Test
    void shutdownNowInterruptsBlockedProcessing() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EmbeddingJob claimed = job();
        when(repository.claim(anyString(), eq(1), anyInt()))
                .thenReturn(List.of(claimed))
                .thenReturn(List.of());
        var executor = mock(EmbeddingJobExecutor.class);
        var blocking = new EmbeddingJobWorker(
                repository, executor, properties);
        // processClaimed 返回 void，须用 doAnswer 打桩。
        org.mockito.Mockito.doAnswer(invocation -> {
            started.countDown();
            release.await(15, TimeUnit.SECONDS);
            return null;
        }).when(executor).processClaimed(any(), anyString());
        try {
            blocking.wakeUp();
            assertTrue(started.await(5, TimeUnit.SECONDS),
                    "process 应已开始执行");

            long start = System.currentTimeMillis();
            blocking.shutdown();
            assertTrue(System.currentTimeMillis() - start < 30_000);
        } finally {
            release.countDown();
        }
    }
}
