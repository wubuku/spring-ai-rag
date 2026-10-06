package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评测执行 worker 处理与关闭长尾（Batch 731，JaCoCo 驱动）：
 * poll 认领运行后调度心跳并同步执行（94-101）、执行异常转 FAILED
 * 落账（102-104）、shutdown 期间中断复原（118-122）。
 */
class EvaluationSuiteWorkerProcessShutdownTailTest {

    private EvaluationSuiteRepository repository;
    private EvaluationSuiteService service;
    private EvaluationSuiteWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(EvaluationSuiteRepository.class);
        service = mock(EvaluationSuiteService.class);
        RagProperties properties = new RagProperties();
        properties.getEvaluation().setMaxConcurrentRuns(1);
        worker = new EvaluationSuiteWorker(repository, service, properties);
    }

    private EvaluationSuiteRepository.RunRow runRow() {
        return new EvaluationSuiteRepository.RunRow(
                UUID.randomUUID(), UUID.randomUUID(), "root", "PENDING",
                new ObjectMapper().nullNode(), null, null,
                new ObjectMapper().nullNode(), null,
                OffsetDateTime.now(), null, OffsetDateTime.now());
    }

    @Test
    void pollProcessesClaimedRunAndReleasesSlot() throws InterruptedException {
        UUID runId = runRow().id();
        EvaluationSuiteRepository.RunRow row = runRow();
        when(repository.claim(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(row))
                .thenReturn(List.of());

        worker.poll();

        verify(service, timeout(5_000)).executeRun(
                any(EvaluationSuiteRepository.RunRow.class), anyString());

        // 槽位已释放：再次 poll 仍会尝试领取。
        //
        // 这里原来直接 `worker.poll()` 然后 `verify(times(2))`。问题是
        // poll() 把执行丢给 workers 线程，槽位要等 process() 的 finally 才归还；
        // 而上面那个 verify(service, timeout) 只等 executeRun **被调用**，
        // 不等它**跑完**——机器一忙，第二次 poll 就扑空，整条用例红
        // （实测在本分支的某次全量跑里 0.025s 就失败）。
        // 改成有界轮询：等"槽位真的回来了"这个**事实**发生，等不到就大声失败，
        // 而不是假定它已经发生。
        assertTrue(awaitSecondClaim(),
                "槽位在 5 秒内没有归还：第二次 poll 一直扑空。"
                        + "claim 调用次数=" + claimCount());
    }

    private long claimCount() {
        return Mockito.mockingDetails(repository).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("claim"))
                .count();
    }

    /** 有界轮询 poll()，直到真的发生第二次 claim。 */
    private boolean awaitSecondClaim() throws InterruptedException {
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            worker.poll();
            if (claimCount() >= 2) {
                return true;
            }
            Thread.sleep(10);
        }
        return claimCount() >= 2;
    }

    @Test
    void pollMarksRunFailedWhenExecutionThrows() {
        EvaluationSuiteRepository.RunRow row = runRow();
        UUID runId = row.id();
        when(repository.claim(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(row))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("plan collapsed"))
                .when(service).executeRun(
                        any(EvaluationSuiteRepository.RunRow.class),
                        anyString());

        worker.poll();

        verify(repository, timeout(5_000)).finishRun(
                eq(runId), anyString(), eq("FAILED"), eq("{}"),
                org.mockito.ArgumentMatchers.contains("plan collapsed"));
    }

    @Test
    void shutdownRestoresInterruptFlagWhenInterruptedMidWait() {
        Thread.currentThread().interrupt();
        try {
            assertDoesNotThrow(() -> worker.shutdown());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
