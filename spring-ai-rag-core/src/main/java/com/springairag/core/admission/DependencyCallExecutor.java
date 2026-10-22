package com.springairag.core.admission;

import com.springairag.core.config.RagAdmissionProperties;
import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
public class DependencyCallExecutor implements AutoCloseable {
    private final AdmissionService admission;
    private final RagAdmissionProperties properties;
    private final MeterRegistry metrics;
    private final Map<String, ThreadPoolExecutor> pools;

    public DependencyCallExecutor(AdmissionService admission, RagProperties properties, MeterRegistry metrics) {
        this.admission = admission;
        this.properties = properties.getAdmission();
        this.metrics = metrics;
        pools = Map.of("embedding", pool("embedding", this.properties.getMaxEmbeddingCalls()),
                "backgroundEmbedding", pool("backgroundEmbedding", this.properties.getMaxBackgroundEmbeddingCalls()),
                "rerank", pool("rerank", this.properties.getMaxRerankCalls()));
    }

    private ThreadPoolExecutor pool(String kind, int capacity) {
        return new ThreadPoolExecutor(capacity, capacity, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
            Thread thread = new Thread(task, "rag-dependency-" + kind);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public <Result> Result execute(String dependency, Supplier<Result> operation) {
        boolean background = DependencyCallContext.background();
        String kind = dependency.equals("embedding") && background ? "backgroundEmbedding" : dependency;
        int timeout = kind.equals("backgroundEmbedding") ? properties.getBackgroundEmbeddingTimeoutMs()
                : properties.getDependencyTimeoutMs();
        long deadline = DependencyCallContext.deadline(timeout);
        try (var scope = DependencyCallContext.open(deadline, background)) {
            DependencyCallContext.remainingMillis();
        }
        AdmissionService.Lease lease = admission.dependency(kind);
        FutureTask<Result> future = new FutureTask<>(() -> {
            try (var scope = DependencyCallContext.open(deadline, background)) {
                DependencyCallContext.remainingMillis();
                return operation.get();
            }
        }) {
            @Override
            public void run() {
                try {
                    super.run();
                } finally {
                    lease.close();
                }
            }
        };
        String outcome = "error";
        try {
            pools.get(kind).execute(future);
            Result result = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            outcome = "success";
            return result;
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            lease.close();
            outcome = "rejected";
            throw new AdmissionRejectedException();
        } catch (TimeoutException timeoutError) {
            future.cancel(true);
            outcome = "timeout";
            throw new DependencyCallException("DEPENDENCY_TIMEOUT");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            outcome = "cancelled";
            throw new DependencyCallException("DEPENDENCY_CANCELLED");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof DependencyCallException failure) {
                outcome = "DEPENDENCY_TIMEOUT".equals(failure.getMessage()) ? "timeout" : "cancelled";
            }
            if (error.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (error.getCause() instanceof Error fatal) {
                throw fatal;
            }
            throw new IllegalStateException("Dependency call failed", error.getCause());
        } finally {
            metrics.counter("rag.dependency.calls", "kind", kind, "outcome", outcome).increment();
        }
    }

    @PreDestroy
    @Override
    public void close() {
        pools.values().forEach(ThreadPoolExecutor::shutdownNow);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (var pool : pools.values()) {
            try {
                pool.awaitTermination(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
