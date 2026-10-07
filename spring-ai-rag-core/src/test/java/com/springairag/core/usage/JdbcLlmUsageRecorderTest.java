package com.springairag.core.usage;

import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcLlmUsageRecorderTest {

    @Test
    void synchronousRepositoryFailureIsFailOpenAndCounted() {
        LlmUsageRepository repository = mock(LlmUsageRepository.class);
        when(repository.insert(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("database unavailable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JdbcLlmUsageRecorder recorder = new JdbcLlmUsageRecorder(
                repository,
                properties(),
                provider(registry));

        assertDoesNotThrow(() -> recorder.record(event()));
        assertTrue(awaitLost(recorder));
        recorder.shutdown();
    }

    @Test
    void synchronousTimeoutIsFailOpenAndDoesNotBlockBeyondBudget() {
        LlmUsageRepository repository = mock(LlmUsageRepository.class);
        CountDownLatch started = new CountDownLatch(1);
        // Batch 951：原来是 Thread.sleep(1_000)。预算只有 100ms，1000 里除了
        // "比预算久"没有别的信息量。阻塞改由测试持有的 latch 控制。
        CountDownLatch release = new CountDownLatch(1);
        when(repository.insert(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagProperties properties = properties();
        properties.getUsage().setRecordTimeoutMs(100);
        JdbcLlmUsageRecorder recorder = new JdbcLlmUsageRecorder(
                repository,
                properties,
                provider(registry));

        try {
            assertDoesNotThrow(() -> recorder.record(event()));

            try {
                assertTrue(started.await(1, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("repository task did not start", interrupted);
            }
            // 机制钉，取代原来的 assertTrue(elapsedMs < 800, …took N ms)。
            //
            // 那条是单样本墙钟阈值：机器一忙就假失败，而它证明的是"跑得够快"，
            // 不是"没有等这次插入"。这里说的是后者——insert 仍卡在 latch 上，
            // 而 release 直到 finally 才放，所以 record() 能返回，就说明它没有
            // 等这次插入做完。如果它真的等了，会一直卡在 release.await() 上，
            // 表现为整条用例卡死而不是一条阈值断言变红。
            assertEquals(1L, release.getCount(),
                    "insert 已经结束，record 也就没有超时可言");
            assertTrue(awaitLost(recorder));
        } finally {
            release.countDown();
            recorder.shutdown();
        }
    }

    @Test
    void asynchronousRepositoryFailureIsFailOpenAndCounted() {
        LlmUsageRepository repository = mock(LlmUsageRepository.class);
        when(repository.insert(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("database unavailable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JdbcLlmUsageRecorder recorder = new JdbcLlmUsageRecorder(
                repository,
                properties(),
                provider(registry));

        assertDoesNotThrow(() -> recorder.recordAsync(event()));
        assertTrue(awaitLost(recorder));
        recorder.shutdown();
    }

    private static RagProperties properties() {
        RagProperties properties = new RagProperties();
        properties.getUsage().setEnabled(true);
        properties.getUsage().setRecorderThreads(1);
        properties.getUsage().setRecorderQueueCapacity(100);
        properties.getUsage().setRecordTimeoutMs(500);
        return properties;
    }

    private static ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider(
            io.micrometer.core.instrument.MeterRegistry registry) {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    private static LlmUsageEvent event() {
        Instant now = Instant.now();
        return new LlmUsageEvent(
                null,
                null,
                1,
                "db:test-owner",
                "session",
                null,
                "test/model",
                com.springairag.api.enums.ChatMode.PLAIN,
                LlmInvocationPurpose.CHAT,
                false,
                LlmInvocationOutcome.SUCCEEDED,
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

    private static boolean awaitLost(JdbcLlmUsageRecorder recorder) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (recorder.lostEvents() > 0) {
                return true;
            }
            Thread.yield();
        }
        return false;
    }

    @Test
    void nullEventShortCircuitsWithoutTouchingRepositoryOrCounter() {
        LlmUsageRepository repository = mock(LlmUsageRepository.class);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        JdbcLlmUsageRecorder recorder = new JdbcLlmUsageRecorder(
                repository, properties(), provider(registry));

        recorder.record(null);
        recorder.recordAsync(null);

        org.mockito.Mockito.verifyNoInteractions(repository);
        org.junit.jupiter.api.Assertions.assertEquals(0, recorder.lostEvents());
        recorder.shutdown();
    }

    @Test
    void disabledUsageShortCircuitsRecording() {
        LlmUsageRepository repository = mock(LlmUsageRepository.class);
        RagProperties properties = properties();
        properties.getUsage().setEnabled(false);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        JdbcLlmUsageRecorder recorder = new JdbcLlmUsageRecorder(
                repository, properties, provider(registry));

        recorder.record(event());
        recorder.recordAsync(event());

        org.mockito.Mockito.verifyNoInteractions(repository);
        org.junit.jupiter.api.Assertions.assertEquals(0, recorder.lostEvents());
        recorder.shutdown();
    }

    @Test
    void lostEventsIsZeroWhenNoMeterRegistryIsAvailable() {
        LlmUsageRepository repository = mock(LlmUsageRepository.class);
        when(repository.insert(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("down"));
        JdbcLlmUsageRecorder recorder = new JdbcLlmUsageRecorder(
                repository, properties(), provider(null));

        recorder.record(event());

        // 无 registry 时 lostCounter 为 null，lostEvents 安全返回 0。
        org.junit.jupiter.api.Assertions.assertEquals(0, recorder.lostEvents());
        recorder.shutdown();
    }

    @Test
    void asyncSubmissionAfterShutdownIsCountedAsLost() {
        LlmUsageRepository repository = mock(LlmUsageRepository.class);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        JdbcLlmUsageRecorder recorder = new JdbcLlmUsageRecorder(
                repository, properties(), provider(registry));
        recorder.shutdown();

        recorder.recordAsync(event());

        // 关闭后的 executor 拒绝提交，计入 lost 而非上抛。
        org.junit.jupiter.api.Assertions.assertEquals(1, recorder.lostEvents());
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void syncSubmissionAfterShutdownIsCountedAsLost() {
        LlmUsageRepository repository = mock(LlmUsageRepository.class);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        JdbcLlmUsageRecorder recorder = new JdbcLlmUsageRecorder(
                repository, properties(), provider(registry));
        recorder.shutdown();

        recorder.record(event());

        org.junit.jupiter.api.Assertions.assertEquals(1, recorder.lostEvents());
        org.mockito.Mockito.verifyNoInteractions(repository);
}
}
