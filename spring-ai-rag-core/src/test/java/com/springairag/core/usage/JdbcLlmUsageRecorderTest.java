package com.springairag.core.usage;

import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
        when(repository.insert(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    try {
                        Thread.sleep(1_000);
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

        long start = System.nanoTime();
        assertDoesNotThrow(() -> recorder.record(event()));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - start);

        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("repository task did not start", interrupted);
        }
        assertTrue(elapsedMs < 800, "record must remain bounded");
        assertTrue(awaitLost(recorder));
        recorder.shutdown();
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
}
