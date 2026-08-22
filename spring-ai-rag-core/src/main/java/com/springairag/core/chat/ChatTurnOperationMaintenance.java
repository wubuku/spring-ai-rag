package com.springairag.core.chat;

import com.springairag.core.config.RagChatProperties;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded cleanup for durable Chat operation rows.
 */
@Component
public class ChatTurnOperationMaintenance {

    private static final Logger log =
            LoggerFactory.getLogger(ChatTurnOperationMaintenance.class);

    private final ChatTurnOperationRepository repository;
    private final RagChatProperties properties;

    public ChatTurnOperationMaintenance(
            ChatTurnOperationRepository repository,
            RagChatProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "#{@ragChatProperties.idempotency.cleanupIntervalMs}",
            initialDelayString = "#{@ragChatProperties.idempotency.cleanupInitialDelayMs}")
    public void cleanup() {
        RagChatProperties.IdempotencyProperties config =
                properties.getIdempotency();
        int deleted = repository.deleteExpired(
                config.getCleanupBatchSize(),
                config.getRetentionHours());
        if (deleted > 0) {
            log.info("Cleaned {} expired Chat turn operation rows", deleted);
        }
    }
}
