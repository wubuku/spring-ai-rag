package com.springairag.core.rag;

import com.springairag.core.chat.ChatExecutionBudget;
import com.springairag.core.config.RagProperties;
import com.springairag.core.evaluation.EvaluationDiagnosticCapture;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QueryEnhancementExecutorTest {
    private ChatExecutionBudget budget(int seconds) {
        return new ChatExecutionBudget(Instant.now().plusSeconds(seconds), 10, 10, 1, 1, 1, 1000);
    }

    @Test
    void shortRemainingDeadlineSkipsBeforeCallingModel() {
        var calls = new AtomicInteger();
        try (var executor = new QueryEnhancementExecutor(new RagProperties())) {
            var outcome = executor.execute("QUERY_EXPAND", budget(2), Duration.ofSeconds(30), calls::incrementAndGet, 0);
            assertEquals("SKIPPED_DEADLINE", outcome.status());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void timedOutUninterruptibleCallKeepsWorkerOccupiedUntilActualReturn() throws Exception {
        var properties = new RagProperties();
        properties.getChat().getKnowledge().setQueryEnhancementMaxConcurrency(1);
        var started = new CountDownLatch(1);
        var release = new java.util.concurrent.CompletableFuture<Void>();
        var finished = new CountDownLatch(1);
        try (var executor = new QueryEnhancementExecutor(properties)) {
            var outcome = executor.execute("QUERY_EXPAND", budget(60), Duration.ofMillis(80), () -> {
                started.countDown();
                release.join();
                finished.countDown();
                return "late";
            }, "original");
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals("TIMED_OUT", outcome.status());
            assertEquals("original", outcome.value());
            assertEquals("SKIPPED_CAPACITY", executor.execute("QUERY_TRANSFORM", budget(60), Duration.ofMillis(50), () -> "rewrite", "original").status());
            assertEquals(1, finished.getCount());
            release.complete(null);
            assertTrue(finished.await(1, TimeUnit.SECONDS));
        } finally { release.complete(null); }
    }

    @Test
    void workersDoNotInheritPrivateDiagnosticCapture() {
        try (var executor = new QueryEnhancementExecutor(new RagProperties()); var capture = EvaluationDiagnosticCapture.open()) {
            var budget = budget(60);
            var outcome = executor.execute("QUERY_TRANSFORM", budget, Duration.ofSeconds(1), () -> {
                EvaluationDiagnosticCapture.prompt(new Prompt("private worker"));
                return "rewritten";
            }, "original");
            assertTrue(outcome.succeeded());
            assertFalse(capture.snapshot().containsKey("lastInvocationMessages"));
            assertTrue(budget.snapshot().containsKey("queryEnhancements"));
        }
    }

    @Test
    void businessRejectionsAndCancellationAreNotOptionalFailures() {
        try (var executor = new QueryEnhancementExecutor(new RagProperties())) {
            var rejected = new com.springairag.core.exception.RagException(com.springairag.api.enums.ErrorCode.BAD_REQUEST, "bounded fixture");
            assertSame(rejected, assertThrows(com.springairag.core.exception.RagException.class,
                    () -> executor.execute("QUERY_EXPAND", budget(60), Duration.ofSeconds(1), () -> { throw rejected; }, "original")));
            assertSame(rejected, assertThrows(com.springairag.core.exception.RagException.class,
                    () -> executor.execute("QUERY_EXPAND", budget(60), Duration.ofSeconds(1), () -> { throw new java.util.concurrent.CompletionException(rejected); }, "original")));
            var cancelled = new java.util.concurrent.CancellationException("cancelled fixture");
            var budget = budget(60);
            assertSame(cancelled, assertThrows(java.util.concurrent.CancellationException.class,
                    () -> executor.execute("QUERY_EXPAND", budget, Duration.ofSeconds(1), () -> { throw cancelled; }, "original")));
            assertTrue(budget.snapshot().get("queryEnhancements").toString().contains("CANCELLED"));
            var skipped = executor.execute("QUERY_EXPAND", budget(60), Duration.ofSeconds(1), () -> {
                throw new com.springairag.core.admission.AdmissionService.OptionalModelCapacityException();
            }, "original");
            assertEquals("SKIPPED_CAPACITY", skipped.status());
            assertEquals("original", skipped.value());
        }
    }
}
