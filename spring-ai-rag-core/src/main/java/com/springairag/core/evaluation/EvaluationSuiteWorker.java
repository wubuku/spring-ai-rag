package com.springairag.core.evaluation;

import com.springairag.core.properties.RagEvaluationProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import com.springairag.core.tenant.TenantContext;
import com.springairag.core.tenant.TenantScopedJob;
import com.springairag.core.tenant.TenantWorkItemAssert;

@Component
@ConditionalOnProperty(
        prefix = "rag.evaluation",
        name = "managed-suites-enabled",
        havingValue = "true",
        matchIfMissing = true)
@TenantScopedJob
public class EvaluationSuiteWorker {

    private static final Logger log = LoggerFactory.getLogger(EvaluationSuiteWorker.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final EvaluationSuiteRepository repository;
    private final EvaluationSuiteService service;
    private final RagEvaluationProperties properties;
    private final String workerId = "eval-" + UUID.randomUUID()
            .toString().replace("-", "").substring(0, 16);
    private final Semaphore slots;
    private final ExecutorService workers;
    private final ScheduledExecutorService heartbeats;
    private volatile boolean accepting = true;

    public EvaluationSuiteWorker(
            EvaluationSuiteRepository repository,
            EvaluationSuiteService service,
            RagProperties ragProperties) {
        this.repository = repository;
        this.service = service;
        this.properties = ragProperties.getEvaluation();
        int concurrency = Math.max(1, properties.getMaxConcurrentRuns());
        this.slots = new Semaphore(concurrency);
        this.workers = Executors.newFixedThreadPool(concurrency, r -> {
            Thread thread = new Thread(r, "evaluation-suite-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "evaluation-suite-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${rag.evaluation.poll-interval-ms:1000}")
    public void poll() {
        if (!accepting) {
            return;
        }
        int claimLimit = slots.availablePermits();
        for (int i = 0; i < claimLimit; i++) {
            if (!slots.tryAcquire()) {
                break;
            }
            String leaseOwner = nextLeaseOwner();
            java.util.List<EvaluationSuiteRepository.RunRow> claimed;
            try {
                claimed = repository.claim(leaseOwner, 1, 120);
            } catch (RuntimeException e) {
                slots.release();
                throw e;
            }
            if (claimed.isEmpty()) {
                slots.release();
                break;
            }
            EvaluationSuiteRepository.RunRow run = claimed.getFirst();
            // WP8：poll() 已在逐租户 scope 内；内层任务显式携带快照并断言 claim 行 tenant。
            workers.execute(TenantContext.wrap(() -> {
                TenantWorkItemAssert.assertMatchesScope(
                        run.tenantId(), "evaluation run " + run.id());
                process(run, leaseOwner);
            }));
        }
    }

    private void process(
            EvaluationSuiteRepository.RunRow run,
            String leaseOwner) {
        // WP8：心跳也走独立线程池，必须逐次重绑 claim 行 tenant，不能依赖 ThreadLocal。
        var heartbeat = heartbeats.scheduleAtFixedRate(
                TenantContext.wrap(() -> {
                    TenantWorkItemAssert.assertMatchesScope(
                            run.tenantId(), "evaluation heartbeat " + run.id());
                    if (repository.heartbeat(run.id(), leaseOwner, 120) == 0) {
                        log.warn("Evaluation run {} lost lease", run.id());
                    }
                }),
                40, 40, TimeUnit.SECONDS);
        try {
            service.executeRun(run, leaseOwner);
        } catch (RuntimeException e) {
            log.warn("Evaluation run {} failed: {}", run.id(), e.getMessage());
            repository.finishRun(
                    run.id(), leaseOwner, "FAILED", "{}", safeError(e.getMessage()));
        } finally {
            heartbeat.cancel(true);
            slots.release();
        }
    }

    @PreDestroy
    public void shutdown() {
        accepting = false;
        workers.shutdown();
        heartbeats.shutdown();
        try {
            if (!workers.awaitTermination(15, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    private String safeError(String value) {
        String raw = value == null || value.isBlank()
                ? "Evaluation run failed"
                : value;
        String masked = SensitiveDataMaskingConverter.maskSensitiveData(raw);
        return masked.length() <= MAX_ERROR_LENGTH
                ? masked
                : masked.substring(0, MAX_ERROR_LENGTH);
    }

    private String nextLeaseOwner() {
        return workerId + "-"
                + UUID.randomUUID().toString().replace("-", "");
    }
}
