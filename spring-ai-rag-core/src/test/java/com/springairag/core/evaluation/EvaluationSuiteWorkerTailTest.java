package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EvaluationSuiteWorker 调度长尾（Batch 409）：poll 的关闭短路/
* claim 异常释放槽位/空领取中止/领取后异步处理与失败落 FAILED、
 * safeError 的空值兜底/脱敏/截断。
 */
class EvaluationSuiteWorkerTailTest {

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

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    private EvaluationSuiteRepository.RunRow runRow() {
        return new EvaluationSuiteRepository.RunRow(
                UUID.randomUUID(), UUID.randomUUID(), "root", "PENDING",
                new ObjectMapper().nullNode(), null, null,
                new ObjectMapper().nullNode(), null,
                OffsetDateTime.now(), null, OffsetDateTime.now());
    }

    private Object invokeSafeError(String value) throws Exception {
        Method method = EvaluationSuiteWorker.class
                .getDeclaredMethod("safeError", String.class);
        method.setAccessible(true);
        return method.invoke(worker, value);
    }

    @Test
    void pollSkipsClaimingAfterShutdown() {
        worker.shutdown();
        worker.poll();
        verify(repository, never()).claim(anyString(), anyInt(), anyInt());
    }

    @Test
    void claimFailureReleasesSlotForSubsequentPolls() {
        when(repository.claim(anyString(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class, worker::poll);
        // 槽位已释放：下一次 poll 仍会尝试领取。
        worker.poll();
        verify(repository, Mockito.times(2)).claim(
                anyString(), eq(1), eq(120));
    }

    @Test
    void emptyClaimStopsPollingThisRound() {
        when(repository.claim(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());

        worker.poll();

        verify(repository, Mockito.times(1)).claim(
                anyString(), eq(1), eq(120));
    }

    @Test
    void claimedRunIsProcessedAsynchronously() {
        EvaluationSuiteRepository.RunRow run = runRow();
        when(repository.claim(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(run))
                .thenReturn(List.of());

        worker.poll();

        verify(service, timeout(2000)).executeRun(
                eq(run), anyString());
        verify(repository, never()).finishRun(
                any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void processingFailureFinishesRunAsFailed() {
        EvaluationSuiteRepository.RunRow run = runRow();
        when(repository.claim(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(run))
                .thenReturn(List.of());
        Mockito.doThrow(new IllegalStateException("suite exploded"))
                .when(service).executeRun(eq(run), anyString());

        worker.poll();

        verify(repository, timeout(2000)).finishRun(
                eq(run.id()), anyString(), eq("FAILED"), eq("{}"),
                contains("suite exploded"));
    }

    @Test
    void safeErrorProvidesFallbackMaskingAndTruncation() throws Exception {
        // null / 空白 → 兜底文案。
        assertEquals("Evaluation run failed", invokeSafeError(null));
        assertEquals("Evaluation run failed", invokeSafeError("   "));
        // 常规错误原样透传（无敏感信息可脱敏）。
        String plain = (String) invokeSafeError("model unavailable");
        assertTrue(plain.contains("model unavailable"));
        // 超长错误截断到 1000 字符。
        StringBuilder longError = new StringBuilder();
        longError.append("x".repeat(1500));
        String truncated = (String) invokeSafeError(longError.toString());
        assertEquals(1000, truncated.length());
    }
}
