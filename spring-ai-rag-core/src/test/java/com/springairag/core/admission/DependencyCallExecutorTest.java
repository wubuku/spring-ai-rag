package com.springairag.core.admission;

import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DependencyCallExecutorTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"DEPENDENCY_TIMEOUT", "DEPENDENCY_CANCELLED"})
    void providerDeadlineAndCancellationKeepDedicatedMetricOutcomes(String code) {
        RagProperties properties = properties();
        var registry = new SimpleMeterRegistry();
        var admission = new AdmissionService(properties, registry);
        try (var executor = new DependencyCallExecutor(admission, properties, registry)) {
            assertThrows(DependencyCallException.class, () -> executor.execute("embedding", () -> {
                throw new DependencyCallException(code);
            }));
            String outcome = code.equals("DEPENDENCY_TIMEOUT") ? "timeout" : "cancelled";
            assertEquals(1, registry.counter("rag.dependency.calls", "kind", "embedding", "outcome", outcome).count());
            awaitEmpty(admission, "embedding");
        }
    }

    @Test
    void advisorWorkloadUsesServerOwnedBudgetAcrossAsyncThreads() throws Exception {
        com.springairag.core.chat.ChatExecutionBudget budget;
        try (var background = BackgroundWorkScope.open()) {
            budget = new com.springairag.core.chat.ChatExecutionBudget(java.time.Instant.now().plusSeconds(2),
                    1, 1, 1, 1, 1, 1000);
        }
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertTrue(worker.submit(() -> {
                assertFalse(BackgroundWorkScope.isActive());
                try (var scope = DependencyCallContext.forBudget(budget)) {
                    assertTrue(DependencyCallContext.remainingMillis() <= 2000);
                    return DependencyCallContext.background();
                }
            }).get());
            assertFalse(worker.submit(DependencyCallContext::background).get());
        }
    }
    @Test
    void primaryCacheHitsDoNotConsumeUpstreamCapacity() {
        RagProperties properties = properties();
        var registry = new SimpleMeterRegistry();
        var admission = new AdmissionService(properties, registry);
        try (var executor = new DependencyCallExecutor(admission, properties, registry)) {
            EmbeddingModel delegate = mock(EmbeddingModel.class);
            when(delegate.embed("cached")).thenReturn(new float[]{3});
            var config = new com.springairag.core.config.PerformanceConfig();
            org.springframework.test.util.ReflectionTestUtils.setField(config, "dependencyExecutor", executor);
            EmbeddingModel cached = config.cachedEmbeddingModel(delegate,
                    new org.springframework.cache.concurrent.ConcurrentMapCacheManager("embeddings"), registry);
            assertArrayEquals(new float[]{3}, cached.embed("cached"));
            awaitEmpty(admission, "embedding");
            try (var busy = admission.dependency("embedding")) {
                assertArrayEquals(new float[]{3}, cached.embed("cached"));
                assertThrows(AdmissionRejectedException.class, () -> cached.embed("uncached"));
            }
            verify(delegate, times(1)).embed("cached");
        }
    }

    @Test
    void isolatedPoolsAndAllEmbeddingEntrypointsRelease() {
        RagProperties properties = properties();
        var registry = new SimpleMeterRegistry();
        var admission = new AdmissionService(properties, registry);
        try (var executor = new DependencyCallExecutor(admission, properties, registry)) {
            EmbeddingModel delegate = mock(EmbeddingModel.class);
            when(delegate.embed(any(String.class))).thenReturn(new float[]{1});
            when(delegate.embed(any(Document.class))).thenReturn(new float[]{2});
            when(delegate.dimensions()).thenReturn(1024);
            var model = new GovernedEmbeddingModel(delegate, executor);
            try (var busy = admission.dependency("embedding")) {
                assertThrows(AdmissionRejectedException.class, () -> model.embed("no capacity"));
                verifyNoInteractions(delegate);
                try (var background = BackgroundWorkScope.open()) {
                    assertArrayEquals(new float[]{1}, model.embed("background"));
                }
                assertEquals("rerank", executor.execute("rerank", () -> "rerank"));
            }
            assertArrayEquals(new float[]{2}, model.embed(new Document("document")));
            assertEquals(1024, model.dimensions());
            model.call(new EmbeddingRequest(java.util.List.of("batch"), null));
            verify(delegate).call(any(EmbeddingRequest.class));
            awaitEmpty(admission, "embedding");
            awaitEmpty(admission, "backgroundEmbedding");
            awaitEmpty(admission, "rerank");
        }
    }

    @Test
    void timeoutRetainsActualCapacityUntilNonInterruptibleProviderEnds() throws Exception {
        RagProperties properties = properties();
        properties.getAdmission().setDependencyTimeoutMs(100);
        var registry = new SimpleMeterRegistry();
        var admission = new AdmissionService(properties, registry);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = new DependencyCallExecutor(admission, properties, registry);
             var caller = Executors.newSingleThreadExecutor()) {
            var pending = caller.submit(() -> assertThrows(DependencyCallException.class,
                    () -> executor.execute("embedding", () -> {
                        started.countDown();
                        while (release.getCount() != 0) {
                            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                            Thread.interrupted();
                        }
                        return "late";
                    })));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals("DEPENDENCY_TIMEOUT", pending.get(2, TimeUnit.SECONDS).getMessage());
            assertEquals(1, admission.active("embedding"));
            assertThrows(AdmissionRejectedException.class, () -> executor.execute("embedding", () -> "overflow"));
            assertEquals("available", executor.execute("rerank", () -> "available"));
            release.countDown();
            awaitEmpty(admission, "embedding");
            assertEquals("recovered", executor.execute("embedding", () -> "recovered"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellationPreservesInterruptAndDoesNotLeakScopeOrLease() throws Exception {
        RagProperties properties = properties();
        var registry = new SimpleMeterRegistry();
        var admission = new AdmissionService(properties, registry);
        var interrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        try (var executor = new DependencyCallExecutor(admission, properties, registry)) {
            Thread caller = new Thread(() -> {
                try {
                    executor.execute("embedding", () -> {
                        started.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }
                        return "cancelled";
                    });
                } catch (DependencyCallException expected) {
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            caller.start();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2000);
            assertFalse(caller.isAlive());
            assertTrue(interrupted.get());
            awaitEmpty(admission, "embedding");
            assertFalse(DependencyCallContext.background());
            assertEquals("ok", executor.execute("embedding", () -> "ok"));
        }
    }

    @Test
    void capturedQueueDeadlineAndBackgroundClassificationArePreserved() throws Exception {
        var calls = new AtomicInteger();
        java.util.function.Supplier<Boolean> captured;
        try (var background = BackgroundWorkScope.open()) {
            captured = DependencyCallContext.capture(DependencyCallContext::background, 1000);
        }
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertTrue(worker.submit(captured::get).get());
            assertFalse(worker.submit(DependencyCallContext::background).get());
            var expired = DependencyCallContext.capture(calls::incrementAndGet, 1);
            TimeUnit.MILLISECONDS.sleep(10);
            assertThrows(DependencyCallException.class, expired::get);
            assertEquals(0, calls.get());
        }
    }

    @Test
    void failuresAndShutdownDoNotLeakCapacity() {
        RagProperties properties = properties();
        var registry = new SimpleMeterRegistry();
        var admission = new AdmissionService(properties, registry);
        var executor = new DependencyCallExecutor(admission, properties, registry);
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute("rerank", () -> { throw new IllegalArgumentException("failure"); }));
        awaitEmpty(admission, "rerank");
        executor.close();
        assertThrows(AdmissionRejectedException.class, () -> executor.execute("embedding", () -> "closed"));
        assertEquals(0, admission.active("embedding"));
    }

    private RagProperties properties() {
        var properties = new RagProperties();
        properties.getAdmission().setMaxEmbeddingCalls(1);
        properties.getAdmission().setMaxBackgroundEmbeddingCalls(1);
        properties.getAdmission().setMaxRerankCalls(1);
        return properties;
    }

    private void awaitEmpty(AdmissionService service, String kind) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (service.active(kind) != 0 && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(100000);
        }
        assertEquals(0, service.active(kind));
    }
}
