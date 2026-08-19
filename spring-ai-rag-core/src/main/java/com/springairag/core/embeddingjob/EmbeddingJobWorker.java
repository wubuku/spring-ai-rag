package com.springairag.core.embeddingjob;

import com.springairag.core.config.RagEmbeddingJobProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.service.DocumentEmbedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 有界轮询、租约化的 embedding job worker。
 */
@Component
@ConditionalOnProperty(
        prefix = "rag.embedding-jobs",
        name = "enabled",
        havingValue = "true")
public class EmbeddingJobWorker {

    private static final Logger log =
            LoggerFactory.getLogger(EmbeddingJobWorker.class);
    private final EmbeddingJobRepository repository;
    private final EmbeddingJobExecutor executor;
    private final RagEmbeddingJobProperties properties;
    private final String workerId = "embed-" + UUID.randomUUID()
            .toString().replace("-", "").substring(0, 16);
    private final Semaphore slots;
    private final ExecutorService workers;

    public EmbeddingJobWorker(
            EmbeddingJobRepository repository,
            EmbeddingJobExecutor executor,
            RagProperties properties) {
        this.repository = repository;
        this.executor = executor;
        this.properties = properties.getEmbeddingJobs();
        int concurrency = this.properties.getWorkerConcurrency();
        this.slots = new Semaphore(concurrency);
        this.workers = Executors.newFixedThreadPool(concurrency, r -> {
            Thread thread = new Thread(r, "embedding-job-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public EmbeddingJobWorker(
            EmbeddingJobRepository repository,
            DocumentEmbedService documentEmbedService,
            EmbeddingProfileProvider profileProvider,
            RagProperties properties) {
        this(repository, new EmbeddingJobExecutor(
                repository, documentEmbedService, profileProvider, properties),
                properties);
    }

    @Scheduled(
            fixedDelayString = "${rag.embedding-jobs.poll-interval-ms:1000}")
    public void poll() {
        int claimLimit = Math.min(
                properties.getClaimBatchSize(), slots.availablePermits());
        for (int i = 0; i < claimLimit; i++) {
            if (!slots.tryAcquire()) {
                break;
            }
            String leaseOwner = nextLeaseOwner();
            java.util.List<EmbeddingJob> claimed;
            try {
                claimed = repository.claim(
                        leaseOwner, 1, properties.getLeaseSeconds());
            } catch (RuntimeException e) {
                slots.release();
                throw e;
            }
            if (claimed.isEmpty()) {
                slots.release();
                break;
            }
            EmbeddingJob job = claimed.getFirst();
            workers.submit(() -> {
                try {
                    process(job, leaseOwner);
                } finally {
                    slots.release();
                }
            });
        }
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    void process(EmbeddingJob job) {
        String leaseOwner = job.leaseOwner() == null
                ? nextLeaseOwner()
                : job.leaseOwner();
        process(job, leaseOwner);
    }

    private void process(EmbeddingJob job, String leaseOwner) {
        executor.processClaimed(job, leaseOwner);
    }

    private String nextLeaseOwner() {
        return workerId + "-"
                + UUID.randomUUID().toString().replace("-", "");
    }

}
