package com.springairag.core.resilience;

import com.springairag.core.chat.BudgetedChatModel;
import com.springairag.core.chat.ChatExecutionBudget;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.LlmCircuitOpenException;
import com.springairag.core.testsupport.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class CandidateCircuitRegistryTest {
    private RagProperties properties() {
        var properties = new RagProperties();
        properties.getCircuitBreaker().setMinimumNumberOfCalls(1);
        return properties;
    }

    @Test
    void failedCandidateDoesNotBlockHealthyModelAndOpenCostsNoModelCall() {
        var registry = new CandidateCircuitRegistry(properties());
        var failed = mock(ChatModel.class);
        var healthy = mock(ChatModel.class);
        when(failed.call(any(Prompt.class))).thenThrow(new TransientAiException("temporary"));
        when(healthy.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));
        var budget = new ChatExecutionBudget(Instant.now().plusSeconds(120), 10, 10, 1, 1, 1, 1000);
        var failedWrapper = new BudgetedChatModel(failed, budget).withCircuits(registry);
        assertThrows(TransientAiException.class, () -> failedWrapper.call(new Prompt("first")));
        assertThrows(LlmCircuitOpenException.class, () -> failedWrapper.call(new Prompt("second")));
        assertEquals(1, budget.modelCalls());
        new BudgetedChatModel(healthy, budget).withCircuits(registry).call(new Prompt("healthy"));
        assertEquals(2, budget.modelCalls());
        verify(failed, times(1)).call(any(Prompt.class));
    }

    @Test
    void exactlyOneHalfOpenProbeAndIgnoredProbeReleasesItsPermit() throws Exception {
        var clock = MutableClock.startingAt(Instant.parse("2026-10-19T00:00:00Z"));
        var registry = new CandidateCircuitRegistry(properties(), clock);
        var model = mock(ChatModel.class);
        registry.acquire(model).failure(new TransientAiException("temporary"));
        clock.advance(Duration.ofSeconds(31));
        var successes = new AtomicInteger();
        var held = new java.util.concurrent.CopyOnWriteArrayList<CandidateCircuitRegistry.Permit>();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(16)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 32; index++) tasks.add(workers.submit(() -> {
                try {
                    start.await();
                    held.add(registry.acquire(model));
                    successes.incrementAndGet();
                } catch (LlmCircuitOpenException ignored) {
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }));
            start.countDown();
            for (var task : tasks) task.get();
        }
        assertEquals(1, successes.get());
        held.getFirst().close();
        var next = registry.acquire(model);
        next.success();
        next.failure(new TransientAiException("must not double count"));
        registry.acquire(model).success();
    }

    @Test
    void permanentRejectionCancellationAndBusinessResultsDoNotOpenCircuit() {
        var registry = new CandidateCircuitRegistry(properties());
        var model = mock(ChatModel.class);
        registry.acquire(model).failure(new java.util.concurrent.CancellationException());
        registry.acquire(model).failure(new org.springframework.ai.retry.NonTransientAiException("unsupported"));
        registry.acquire(model).success();
        registry.acquire(model).close();
    }

    @Test
    void streamFailureCountsOnceAndCancelledProbeCanBeRetried() {
        var registry = new CandidateCircuitRegistry(properties());
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new TransientAiException("temporary")));
        var budget = new ChatExecutionBudget(Instant.now().plusSeconds(120), 10, 10, 1, 1, 1, 1000);
        var wrapper = new BudgetedChatModel(model, budget).withCircuits(registry);
        assertThrows(TransientAiException.class, () -> wrapper.stream(new Prompt("stream")).blockLast());
        assertThrows(LlmCircuitOpenException.class, () -> wrapper.stream(new Prompt("open")).blockLast());
        assertEquals(1, budget.modelCalls());
    }

    @Test
    void registryBoundFailsClosedInsteadOfEvictingOpenStates() {
        var registry = new CandidateCircuitRegistry(properties());
        for (int index = 0; index < 512; index++) registry.acquire(mock(ChatModel.class)).close();
        assertThrows(LlmCircuitOpenException.class, () -> registry.acquire(mock(ChatModel.class)));
    }

    @Test
    void oldCompletionsCannotReleaseOrCloseAnotherGenerationProbe() {
        var clock = MutableClock.startingAt(Instant.parse("2026-10-19T00:00:00Z"));
        var registry = new CandidateCircuitRegistry(properties(), clock);
        var model = mock(ChatModel.class);
        var oldSuccess = registry.acquire(model);
        var oldIgnored = registry.acquire(model);
        var oldFailure = registry.acquire(model);
        registry.acquire(model).failure(new TransientAiException("temporary"));
        clock.advance(Duration.ofSeconds(31));
        var probe = registry.acquire(model);
        oldSuccess.success();
        oldIgnored.close();
        oldFailure.failure(new TransientAiException("late failure"));
        assertThrows(LlmCircuitOpenException.class, () -> registry.acquire(model));
        probe.success();
        registry.acquire(model).success();
    }

    @Test
    void concurrentWindowUpdatesRemainBoundedAndExact() throws Exception {
        var config = properties().getCircuitBreaker();
        config.setSlidingWindowSize(20);
        config.setMinimumNumberOfCalls(100000);
        var breaker = new LlmCircuitBreaker(config);
        try (var workers = Executors.newFixedThreadPool(16)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 2000; index++) tasks.add(workers.submit(breaker::recordSuccess));
            for (var task : tasks) task.get();
        }
        assertEquals(20, breaker.getSuccesses());
        assertEquals(0, breaker.getFailures());
    }
}
