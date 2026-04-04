package com.springairag.core.service;

import com.springairag.core.config.RagMemoryProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 聊天历史定时清理服务（TTL 过期策略）
 *
 * <p>定期清理超过保留期限的 rag_chat_history 记录，支持数据治理和 GDPR 合规。
 * 默认保留 30 天，可通过 {@code rag.memory.message-ttl-days} 配置。
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
     * 每天凌晨 3 点执行一次 TTL 清理
     * 使用 fixedDelay 保证上一次执行完成后才开始下一次
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
     * 手动触发清理（供外部调用）
     *
     * @param cutoff 删除此时间之前的记录
     * @return 删除的记录数
     */
    public int cleanupOlderThan(LocalDateTime cutoff) {
        if (cutoff == null) {
            return 0;
        }
        return chatHistoryRepository.deleteOlderThan(cutoff);
    }
}
