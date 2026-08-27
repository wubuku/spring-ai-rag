package com.springairag.core.observability;

import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 有界、异步、fail-open 的 HTTP 观测记录器。
 */
@Component
public final class IntegrationObservationRecorder {

    private static final Logger log =
            LoggerFactory.getLogger(IntegrationObservationRecorder.class);

    private final IntegrationObservationRepository repository;
    private final RagProperties properties;
    private final ArrayBlockingQueue<IntegrationObservation> queue;
    private final LongAdder droppedEvents = new LongAdder();
    private final MeterRegistry meterRegistry;

    public IntegrationObservationRecorder(
            IntegrationObservationRepository repository,
            RagProperties properties,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.repository = repository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        int capacity = properties.getIntegrationObservability().getQueueCapacity();
        this.queue = new ArrayBlockingQueue<>(capacity);
        Gauge.builder(
                        "rag.integration.observation.queue.depth",
                        queue,
                        ArrayBlockingQueue::size)
                .description("Current integration observation queue depth")
                .register(meterRegistry);
    }

    public void record(IntegrationObservation observation) {
        if (observation == null
                || !properties.getIntegrationObservability().isEnabled()) {
            return;
        }
        if (!queue.offer(observation)) {
            dropped("queue_full", 1);
        }
    }

    public long droppedEvents() {
        return droppedEvents.sum();
    }

    public int queueDepth() {
        return queue.size();
    }

    @Scheduled(
            fixedDelayString = "${rag.integration-observability.flush-interval:1s}",
            initialDelayString = "${rag.integration-observability.flush-interval:1s}")
    void scheduledFlush() {
        flush();
    }

    /**
     * 同步排空一个有界批次，供定时任务、测试和停机使用。
     */
    int flush() {
        if (!properties.getIntegrationObservability().isEnabled()) {
            return 0;
        }
        int batchSize = properties.getIntegrationObservability().getFlushBatchSize();
        List<IntegrationObservation> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            repository.upsert(
                    batch,
                    Math.max(
                            100,
                            Math.min(
                            10_000,
                            properties.getUsage().getRecordTimeoutMs())));
            recordFlushMetric("success");
            return batch.size();
        } catch (RuntimeException failure) {
            dropped("repository_failure", batch.size());
            recordFlushMetric("failure");
            log.warn(
                    "Integration observation batch dropped: size={}, reason={}",
                    batch.size(),
                    failure.getClass().getSimpleName());
            return 0;
        }
    }

    @Scheduled(
            fixedDelayString = "${rag.integration-observability.cleanup-interval:1h}",
            initialDelayString = "${rag.integration-observability.cleanup-interval:1h}")
    void scheduledCleanup() {
        if (!properties.getIntegrationObservability().isEnabled()) {
            return;
        }
        try {
            repository.deleteExpired(
                    java.time.Instant.now().minus(
                            properties.getIntegrationObservability().getRetention()),
                    properties.getIntegrationObservability().getCleanupBatchSize(),
                    Math.max(
                            100,
                            Math.min(
                                    10_000,
                                    properties.getUsage().getRecordTimeoutMs())));
            recordCleanupMetric("success");
        } catch (RuntimeException failure) {
            recordCleanupMetric("failure");
            log.warn("Integration observation cleanup failed: reason=repository_failure");
        }
    }

    @PreDestroy
    void shutdown() {
        Duration timeout =
                properties.getIntegrationObservability().getShutdownDrainTimeout();
        long deadline = System.nanoTime()
                + Math.max(0, timeout.toNanos());
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            int before = queue.size();
            flush();
            if (queue.size() >= before) {
                break;
            }
        }
        if (!queue.isEmpty()) {
            dropped("shutdown_timeout", queue.size());
            queue.clear();
        }
    }

    private void dropped(String reason, int count) {
        if (count <= 0) {
            return;
        }
        droppedEvents.add(count);
        Counter.builder("rag.integration.observation.dropped")
                .description("Integration observations dropped before durable rollup")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment(count);
    }

    private void recordFlushMetric(String result) {
        Counter.builder("rag.integration.observation.flush")
                .description("Integration observation flush attempts")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }

    private void recordCleanupMetric(String result) {
        Counter.builder("rag.integration.observation.cleanup")
                .description("Expired integration observation cleanup attempts")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}
