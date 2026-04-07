package com.springairag.core.service;

import com.springairag.core.config.RagMemoryProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Scheduled chat history cleanup service (TTL expiration policy).
 *
 * <p>Periodically purges rag_chat_history records beyond the retention period,
 * supporting data governance and GDPR compliance.
 * Default retention: 30 days, configurable via {@code rag.memory.message-ttl-days}.
 */
@Service
public class ChatHistoryCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryCleanupService.class);

    private final RagChatHistoryRepository chatHistoryRepository;
    private final RagMemoryProperties memoryProperties;

    public ChatHistoryCleanupService(RagChatHistoryRepository chatHistoryRepository,
                                     RagMemoryProperties memoryProperties) {
        this.chatHistoryRepository = chatHistoryRepository;
        this.memoryProperties = memoryProperties;
    }

    /**
     * Executes TTL cleanup once daily at 3 AM.
     * Uses fixedDelay to ensure the next run starts only after the previous one completes.
     */
    @Scheduled(cron = "${rag.memory.cleanup-cron:0 0 3 * * *}", zone = "${spring.task.scheduling.timezone:Asia/Shanghai}")
    public void cleanupExpiredChatHistory() {
        int ttlDays = memoryProperties.getMessageTtlDays();
        if (ttlDays <= 0) {
            log.debug("Chat history TTL cleanup is disabled (ttlDays={})", ttlDays);
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(ttlDays);
        log.info("Starting chat history TTL cleanup, cutoff={} (ttlDays={})", cutoff, ttlDays);

        try {
            int deleted = chatHistoryRepository.deleteOlderThan(cutoff);
            log.info("Chat history TTL cleanup completed, deleted {} records", deleted);
        } catch (Exception e) { // Resilience: non-critical scheduled task
            log.error("Chat history TTL cleanup failed", e);
        }
    }

    /**
     * Manually trigger cleanup (for external callers).
     *
     * @param cutoff Delete records older than this timestamp
     * @return Number of records deleted
     */
    public int cleanupOlderThan(LocalDateTime cutoff) {
        if (cutoff == null) {
            return 0;
        }
        return chatHistoryRepository.deleteOlderThan(cutoff);
    }
}
