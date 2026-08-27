package com.springairag.core.usage;

import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Fail-open recorder backed by bounded executors.
 *
 * <p>Streaming terminal events are submitted asynchronously. Non-streaming
 * events wait only up to the configured timeout so a ledger outage cannot
 * turn a successful provider response into an application failure.</p>
 */
@Component
public final class JdbcLlmUsageRecorder implements LlmUsageRecorder {

    private static final Logger log =
            LoggerFactory.getLogger(JdbcLlmUsageRecorder.class);

    private final LlmUsageRepository repository;
    private final RagProperties properties;
    private final ExecutorService asyncExecutor;
    private final ExecutorService syncExecutor;
    private final Counter lostCounter;

    public JdbcLlmUsageRecorder(
            LlmUsageRepository repository,
            RagProperties properties,
            ObjectProvider<MeterRegistry> registries) {
        this.repository = repository;
        this.properties = properties;
        MeterRegistry registry = registries.getIfAvailable();
        this.lostCounter = registry == null
                ? null
                : Counter.builder("rag.llm.usage.events.lost")
                        .description("Model invocation ledger events not confirmed in time")
                        .register(registry);
        this.asyncExecutor = executor("rag-usage-async-");
        this.syncExecutor = executor("rag-usage-sync-");
    }

    @Override
    public void record(LlmUsageEvent event) {
        if (event == null || !properties.getUsage().isEnabled()) {
            return;
        }
        int timeoutMs = properties.getUsage().getRecordTimeoutMs();
        CompletableFuture<Boolean> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> repository.insert(event, timeoutMs),
                    syncExecutor);
        } catch (RejectedExecutionException rejected) {
            lost(event, "executor_rejected");
            return;
        }
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            future.cancel(true);
            lost(event, reason(failure));
        }
    }

    @Override
    public void recordAsync(LlmUsageEvent event) {
        if (event == null || !properties.getUsage().isEnabled()) {
            return;
        }
        try {
            asyncExecutor.execute(() -> {
                try {
                    repository.insert(
                            event,
                            properties.getUsage().getRecordTimeoutMs());
                } catch (RuntimeException failure) {
                    lost(event, "repository_failure");
                    log.warn(
                            "LLM usage event {} was not recorded: {}",
                            event.invocationId(),
                            failure.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException rejected) {
            lost(event, "executor_rejected");
        }
    }

    public long lostEvents() {
        return lostCounter == null ? 0L : (long) lostCounter.count();
    }

    private ExecutorService executor(String prefix) {
        var usage = properties.getUsage();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                usage.getRecorderThreads(),
                usage.getRecorderThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(usage.getRecorderQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName(prefix + thread.getId());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    private void lost(LlmUsageEvent event, String reason) {
        if (lostCounter != null) {
            lostCounter.increment();
        }
        log.warn(
                "LLM usage event lost: invocationId={}, executionId={}, modelRef={}, "
                        + "purpose={}, outcome={}, reason={}",
                event.invocationId(),
                event.logicalExecutionId(),
                event.modelRef(),
                event.purpose(),
                event.outcome(),
                reason);
    }

    private static String reason(Throwable failure) {
        Throwable cause = failure;
        if (failure instanceof java.util.concurrent.ExecutionException
                && failure.getCause() != null) {
            cause = failure.getCause();
        }
        if (cause instanceof java.util.concurrent.TimeoutException) {
            return "timeout";
        }
        return "repository_failure";
    }

    @PreDestroy
    void shutdown() {
        asyncExecutor.shutdown();
        syncExecutor.shutdown();
        try {
            asyncExecutor.awaitTermination(2, TimeUnit.SECONDS);
            syncExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            asyncExecutor.shutdownNow();
            syncExecutor.shutdownNow();
        }
    }
}
