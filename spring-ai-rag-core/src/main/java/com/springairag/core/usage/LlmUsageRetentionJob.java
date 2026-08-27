package com.springairag.core.usage;

import com.springairag.core.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Bounded hourly retention maintenance for the invocation ledger.
 */
@Component
public final class LlmUsageRetentionJob {

    private static final Logger log =
            LoggerFactory.getLogger(LlmUsageRetentionJob.class);

    private final LlmUsageRepository repository;
    private final RagProperties properties;

    public LlmUsageRetentionJob(
            LlmUsageRepository repository,
            RagProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(cron = "${rag.usage.cleanup-cron:0 20 * * * *}")
    public void cleanup() {
        var usage = properties.getUsage();
        if (!usage.isEnabled() || !usage.isCleanupEnabled()) {
            return;
        }
        Instant cutoff = Instant.now()
                .minus(usage.getRetentionDays(), ChronoUnit.DAYS);
        int deleted = 0;
        try {
            for (int batch = 0; batch < usage.getCleanupMaxBatches(); batch++) {
                int current = repository.deleteExpired(
                        cutoff,
                        usage.getCleanupBatchSize(),
                        usage.getRecordTimeoutMs());
                deleted += current;
                if (current < usage.getCleanupBatchSize()) {
                    break;
                }
            }
            if (deleted > 0) {
                log.info("Deleted {} expired LLM usage events", deleted);
            }
        } catch (RuntimeException failure) {
            log.warn(
                    "LLM usage retention cleanup failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}
