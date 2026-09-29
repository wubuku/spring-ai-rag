package com.springairag.core.usage;

import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LLM 用量记录器关闭与拒绝长尾（Batch 719，JaCoCo 驱动）：
 * record / recordAsync 在执行器关闭后经 RejectedExecutionException
 * 走 lost 计数（67-70 / 94）、null 事件直接忽略（74）、shutdown 期
 * 间线程中断被复原且关闭完成（158-159）。
 */
class JdbcLlmUsageRecorderShutdownTailTest {

    private LlmUsageRepository repository;
    private JdbcLlmUsageRecorder recorder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(LlmUsageRepository.class);
        recorder = new JdbcLlmUsageRecorder(
                repository,
                properties(),
                provider(null));
    }

    @AfterEach
    void tearDown() {
        Thread.interrupted();
        recorder.shutdown();
    }

    private static RagProperties properties() {
        RagProperties properties = new RagProperties();
        properties.getUsage().setEnabled(true);
        properties.getUsage().setRecorderThreads(1);
        properties.getUsage().setRecorderQueueCapacity(1);
        properties.getUsage().setRecordTimeoutMs(500);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<io.micrometer.core.instrument.MeterRegistry>
    provider(io.micrometer.core.instrument.MeterRegistry registry) {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    private LlmUsageEvent event() {
        java.time.Instant now = java.time.Instant.now();
        return new LlmUsageEvent(
                null,
                null,
                1,
                "db:test-owner",
                "session-shutdown",
                null,
                "test/model",
                com.springairag.api.enums.ChatMode.PLAIN,
                com.springairag.core.usage.LlmInvocationPurpose.CHAT,
                false,
                com.springairag.core.usage.LlmInvocationOutcome.SUCCEEDED,
                LlmUsageSnapshot.unavailable(),
                null,
                null,
                false,
                null,
                false,
                "CONFIGURED_MODEL_COST",
                1,
                now,
                now);
    }

    @Test
    void recordAfterShutdownRoutesThroughExecutorRejectedLost() {
        recorder.shutdown();

        assertDoesNotThrow(() -> recorder.record(event()));
    }

    @Test
    void recordAsyncAfterShutdownIsSilentlyDropped() {
        recorder.shutdown();

        assertDoesNotThrow(() -> recorder.recordAsync(event()));
    }

    @Test
    void recordAsyncNullEventIsIgnored() {
        assertDoesNotThrow(() -> recorder.recordAsync(null));
    }

    @Test
    void shutdownRestoresInterruptFlagWhenInterruptedMidWait() {
        // 预置中断位 → awaitTermination 立即抛 InterruptedException →
        // shutdown 复原中断位并完成两级 shutdownNow。
        Thread.currentThread().interrupt();
        try {
            assertDoesNotThrow(() -> recorder.shutdown());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
