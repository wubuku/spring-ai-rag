package com.springairag.core.rag;

import com.springairag.core.chat.ChatExecutionBudget;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
public final class QueryEnhancementExecutor implements AutoCloseable {
    private final ThreadPoolExecutor pool;
    private final long answerReserveMillis;

    public QueryEnhancementExecutor(RagProperties properties) {
        var knowledge = properties.getChat().getKnowledge();
        int capacity = knowledge.getQueryEnhancementMaxConcurrency();
        answerReserveMillis = TimeUnit.SECONDS.toMillis(knowledge.getQueryEnhancementAnswerReserveSeconds());
        pool = new ThreadPoolExecutor(capacity, capacity, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread worker = new Thread(task, "rag-query-enhancement");
            worker.setDaemon(true);
            return worker;
        }, new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
    }

    public <Result> Outcome<Result> execute(String stage, ChatExecutionBudget budget, Duration timeout,
                                            Supplier<Result> operation, Result fallback) {
        if (!stage.equals("QUERY_TRANSFORM") && !stage.equals("QUERY_EXPAND")) throw new IllegalArgumentException("Unknown enhancement stage");
        long started = System.nanoTime();
        long available = budget == null ? timeout.toMillis()
                : Duration.between(Instant.now(), budget.deadline()).toMillis() - answerReserveMillis;
        long wait = Math.min(timeout.toMillis(), available);
        if (wait <= 0) return finish(stage, budget, fallback, "SKIPPED_DEADLINE", started);
        long stageDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wait);
        var future = new FutureTask<>(() -> {
            if (System.nanoTime() >= stageDeadline) throw new TimeoutException();
            return operation.get();
        });
        try {
            pool.execute(future);
            Result value = future.get(Math.max(1, stageDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return finish(stage, budget, value, "SUCCEEDED", started);
        } catch (RejectedExecutionException rejected) {
            return finish(stage, budget, fallback, "SKIPPED_CAPACITY", started);
        } catch (TimeoutException timeoutError) {
            future.cancel(true);
            return finish(stage, budget, fallback, "TIMED_OUT", started);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            finish(stage, budget, fallback, "CANCELLED", started);
            throw new CancellationException("Query enhancement interrupted");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            for (int depth = 0; cause != null && depth < 20; depth++, cause = cause.getCause()) {
                if (cause instanceof com.springairag.core.admission.AdmissionService.OptionalModelCapacityException) {
                    return finish(stage, budget, fallback, "SKIPPED_CAPACITY", started);
                }
                if (cause instanceof RagException rejected) throw rejected;
                if (cause instanceof CancellationException cancelled) {
                    finish(stage, budget, fallback, "CANCELLED", started);
                    throw cancelled;
                }
                if (cause instanceof Error fatal) throw fatal;
            }
            return finish(stage, budget, fallback, "FAILED", started);
        }
    }

    private <Result> Outcome<Result> finish(String stage, ChatExecutionBudget budget, Result value, String status, long started) {
        if (budget != null) budget.recordQueryEnhancement(stage, status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return new Outcome<>(value, status);
    }

    public record Outcome<Result>(Result value, String status) {
        public boolean succeeded() { return "SUCCEEDED".equals(status); }
    }

    @PreDestroy
    @Override
    public void close() {
        pool.shutdownNow();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
