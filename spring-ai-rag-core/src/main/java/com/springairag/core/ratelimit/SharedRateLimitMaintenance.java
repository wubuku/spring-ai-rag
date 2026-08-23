package com.springairag.core.ratelimit;

import com.springairag.core.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;

/** 共享配额 bucket 的有界 best-effort 清理。 */
public class SharedRateLimitMaintenance {

    private static final Logger log = LoggerFactory.getLogger(SharedRateLimitMaintenance.class);

    private final RagProperties properties;
    private final PostgresRateLimitStore store;
    private final RateLimitObservability observability;

    public SharedRateLimitMaintenance(
            RagProperties properties,
            PostgresRateLimitStore store,
            RateLimitObservability observability) {
        this.properties = properties;
        this.store = store;
        this.observability = observability;
    }

    @Scheduled(fixedDelayString = "${rag.rate-limit.cleanup-interval-seconds:300}000")
    public void cleanup() {
        var config = properties.getRateLimit();
        if (!config.isEnabled() || !"postgresql".equals(config.getBackend())) {
            return;
        }
        try {
            store.cleanup(
                    config.getBucketRetentionMinutes(),
                    config.getCleanupBatchSize());
        } catch (DataAccessException e) {
            observability.recordCleanupError();
            log.warn("Shared rate-limit bucket cleanup failed");
        }
    }
}
