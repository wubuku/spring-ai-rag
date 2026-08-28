package com.springairag.core.alertdelivery;

import com.springairag.core.config.NotificationConfig;
import com.springairag.core.config.RagNotificationDeliveryProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Event-driven、lease 化且有界的 durable notification worker。 */
@Component
@ConditionalOnProperty(
        prefix = "rag.notifications.delivery",
        name = "enabled",
        havingValue = "true")
public class AlertNotificationDeliveryWorker {

    private static final Logger log =
            LoggerFactory.getLogger(AlertNotificationDeliveryWorker.class);

    private final AlertNotificationDeliveryRepository repository;
    private final AlertNotificationOutboxService outboxService;
    private final RagNotificationDeliveryProperties properties;
    private final Semaphore slots;
    private final ExecutorService executor;
    private final AtomicBoolean wakeRequested = new AtomicBoolean();
    private final AtomicBoolean dispatchScheduled = new AtomicBoolean();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public AlertNotificationDeliveryWorker(
            AlertNotificationDeliveryRepository repository,
            AlertNotificationOutboxService outboxService,
            NotificationConfig notificationConfig) {
        this.repository = repository;
        this.outboxService = outboxService;
        this.properties = notificationConfig.getDelivery();
        int concurrency = properties.getWorkerConcurrency();
        this.slots = new Semaphore(concurrency);
        this.executor = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getClaimBatchSize()),
                threadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @EventListener
    public void onAvailable(AlertNotificationsAvailableEvent event) {
        wakeUp();
    }

    @Scheduled(
            fixedDelayString =
                    "${rag.notifications.delivery.fallback-scan-interval:PT1M}")
    public void fallbackScan() {
        repository.recoverExhaustedLeases(
                properties.getClaimBatchSize());
        wakeUp();
    }

    @Scheduled(
            fixedDelayString =
                    "${rag.notifications.delivery.cleanup-interval:PT1H}")
    public void cleanup() {
        repository.cleanup(
                properties.getDeliveredRetention(),
                properties.getFailedRetention(),
                properties.getCleanupBatchSize());
    }

    public void wakeUp() {
        if (shuttingDown.get()) {
            return;
        }
        wakeRequested.set(true);
        scheduleDispatch();
    }

    private void scheduleDispatch() {
        if (!dispatchScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::dispatchLoop);
        } catch (RejectedExecutionException error) {
            dispatchScheduled.set(false);
            if (!shuttingDown.get()) {
                log.warn("Alert notification wake-up was rejected; "
                        + "the fallback scan will retry");
            }
        }
    }

    private void dispatchLoop() {
        try {
            do {
                wakeRequested.set(false);
                dispatchAvailable();
            } while (wakeRequested.get() && !shuttingDown.get());
        } catch (RuntimeException error) {
            log.warn("Alert notification dispatch failed; "
                    + "the fallback scan will retry", error);
        } finally {
            dispatchScheduled.set(false);
            if (wakeRequested.get() && !shuttingDown.get()) {
                scheduleDispatch();
            }
        }
    }

    private void dispatchAvailable() {
        int available = slots.availablePermits();
        if (available <= 0) {
            return;
        }
        int limit = Math.min(properties.getClaimBatchSize(), available);
        List<UUID> candidates = repository.findCandidateIds(limit);
        for (UUID id : candidates) {
            if (!slots.tryAcquire()) {
                break;
            }
            UUID leaseToken = UUID.randomUUID();
            AlertNotificationDeliveryRecord claimed =
                    repository.claim(id, leaseToken, properties.getLeaseDuration())
                            .orElse(null);
            if (claimed == null) {
                slots.release();
                continue;
            }
            try {
                executor.execute(() -> {
                    try {
                        process(claimed, leaseToken);
                    } finally {
                        slots.release();
                        wakeUp();
                    }
                });
            } catch (RejectedExecutionException error) {
                slots.release();
                log.warn("Claimed alert notification could not be submitted; "
                        + "its lease will be recovered");
                break;
            }
        }
    }

    void process(
            AlertNotificationDeliveryRecord delivery,
            UUID leaseToken) {
        if (delivery.managedCondition()
                && !repository.isManagedStateCurrent(
                        delivery.alertId(),
                        delivery.notificationVersion())) {
            repository.markSuperseded(delivery.id(), leaseToken);
            return;
        }
        AlertNotificationProvider provider =
                outboxService.provider(delivery.provider());
        if (provider == null || !provider.isCurrentlyAvailable()) {
            repository.markPermanentFailure(
                    delivery.id(), leaseToken,
                    "PERMANENT_CONFIGURATION", null);
            return;
        }

        AlertNotificationAttemptResult result;
        try {
            result = provider.deliver(delivery.payload());
        } catch (RuntimeException error) {
            result = AlertNotificationAttemptResult.transientFailure(
                    "TRANSIENT_NETWORK", null, null);
        }
        switch (result.outcome()) {
            case SUCCESS -> repository.markDelivered(
                    delivery.id(), leaseToken);
            case PERMANENT_FAILURE -> repository.markPermanentFailure(
                    delivery.id(), leaseToken,
                    result.errorCode(), result.httpStatus());
            case TRANSIENT_FAILURE -> repository.markTransientFailure(
                    delivery, leaseToken,
                    result.errorCode(), result.httpStatus(),
                    retryDelay(delivery.attemptCount(), result.retryAfter()));
        }
    }

    private Duration retryDelay(int attempt, Duration retryAfter) {
        long initial = properties.getInitialBackoff().toMillis();
        int exponent = Math.max(0, Math.min(20, attempt - 1));
        long scaled;
        try {
            scaled = Math.multiplyExact(initial, 1L << exponent);
        } catch (ArithmeticException overflow) {
            scaled = Long.MAX_VALUE;
        }
        long capped = Math.min(scaled, properties.getMaxBackoff().toMillis());
        long jitter = Math.max(1, capped / 5);
        long randomized = Math.max(0, capped
                + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1));
        if (retryAfter != null) {
            randomized = Math.max(
                    randomized,
                    Math.min(retryAfter.toMillis(),
                            properties.getMaxBackoff().toMillis()));
        }
        return Duration.ofMillis(randomized);
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown.set(true);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "alert-notification-worker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
