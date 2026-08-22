package com.springairag.core.service;

import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.RagMemoryProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.repository.RagChatMemorySummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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
    private static final int SESSION_BATCH_SIZE = 50;
    private static final int ROW_BATCH_SIZE = 500;
    private static final String ACQUIRE_MAINTENANCE_LEASE_SQL = """
            INSERT INTO rag_chat_session_lease
                (owner_principal_id, session_id, owner_token, acquired_at, expires_at)
            VALUES (?, ?, ?, clock_timestamp(),
                    clock_timestamp() + (? * interval '1 millisecond'))
            ON CONFLICT (owner_principal_id, session_id)
            DO UPDATE SET
                owner_token = EXCLUDED.owner_token,
                acquired_at = EXCLUDED.acquired_at,
                expires_at = EXCLUDED.expires_at
            WHERE rag_chat_session_lease.expires_at < clock_timestamp()
            """;
    private static final String CONSUME_MAINTENANCE_LEASE_SQL = """
            DELETE FROM rag_chat_session_lease
            WHERE owner_principal_id = ?
              AND session_id = ?
              AND owner_token = ?
              AND expires_at > clock_timestamp()
            RETURNING owner_token
            """;
    private static final String RELEASE_MAINTENANCE_LEASE_SQL = """
            DELETE FROM rag_chat_session_lease
            WHERE owner_principal_id = ?
              AND session_id = ?
              AND owner_token = ?
            """;

    private final RagChatHistoryRepository chatHistoryRepository;
    private final RagMemoryProperties memoryProperties;
    private final JdbcTemplate jdbcTemplate;
    private final RagChatMemorySummaryRepository summaryRepository;
    private final TransactionTemplate transactionTemplate;
    private final RagProperties ragProperties;

    public ChatHistoryCleanupService(RagChatHistoryRepository chatHistoryRepository,
                                     RagMemoryProperties memoryProperties) {
        this(chatHistoryRepository, memoryProperties, null, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChatHistoryCleanupService(
            RagChatHistoryRepository chatHistoryRepository,
            RagMemoryProperties memoryProperties,
            JdbcTemplate jdbcTemplate,
            RagChatMemorySummaryRepository summaryRepository,
            PlatformTransactionManager transactionManager,
            RagProperties ragProperties) {
        this.chatHistoryRepository = chatHistoryRepository;
        this.memoryProperties = memoryProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.summaryRepository = summaryRepository;
        this.transactionTemplate = transactionManager != null
                ? new TransactionTemplate(transactionManager)
                : null;
        this.ragProperties = ragProperties;
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
            int deleted = cleanupOlderThan(cutoff);
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
        if (!hasCoordinatedCleanupDependencies()) {
            return chatHistoryRepository.deleteOlderThan(cutoff);
        }
        int deleted = cleanupOwnedSessions(cutoff);
        deleted += deleteLegacyRows(cutoff);
        return deleted;
    }

    private int cleanupOwnedSessions(LocalDateTime cutoff) {
        List<ExpiredSession> candidates = jdbcTemplate.query("""
                SELECT owner_principal_id, session_id
                FROM rag_chat_history
                WHERE owner_principal_id IS NOT NULL
                  AND created_at < ?
                GROUP BY owner_principal_id, session_id
                ORDER BY MIN(created_at), owner_principal_id, session_id
                LIMIT ?
                """,
                (rs, rowNum) -> new ExpiredSession(
                        rs.getString("owner_principal_id"),
                        rs.getString("session_id")),
                cutoff, SESSION_BATCH_SIZE);

        int deleted = 0;
        for (ExpiredSession candidate : candidates) {
            deleted += cleanupSession(candidate, cutoff);
        }
        return deleted;
    }

    private int cleanupSession(ExpiredSession candidate, LocalDateTime cutoff) {
        String token = UUID.randomUUID().toString();
        int acquired = jdbcTemplate.update(
                ACQUIRE_MAINTENANCE_LEASE_SQL,
                candidate.ownerPrincipalId(),
                candidate.sessionId(),
                token,
                maintenanceLeaseTtlMs());
        if (acquired != 1) {
            log.debug("Skipping active chat session during TTL cleanup: owner={}, session={}",
                    candidate.ownerPrincipalId(), candidate.sessionId());
            return 0;
        }

        try {
            Integer deleted = transactionTemplate.execute(status -> {
                List<String> consumed = jdbcTemplate.query(
                        CONSUME_MAINTENANCE_LEASE_SQL,
                        (rs, rowNum) -> rs.getString(1),
                        candidate.ownerPrincipalId(),
                        candidate.sessionId(),
                        token);
                if (consumed.size() != 1) {
                    throw new IllegalStateException(
                            "TTL maintenance lease was lost before cleanup");
                }
                int rows = jdbcTemplate.update("""
                        WITH victims AS (
                            SELECT id
                            FROM rag_chat_history
                            WHERE owner_principal_id = ?
                              AND session_id = ?
                              AND created_at < ?
                            ORDER BY created_at ASC, id ASC
                            LIMIT ?
                        )
                        DELETE FROM rag_chat_history history
                        USING victims
                        WHERE history.id = victims.id
                        """,
                        candidate.ownerPrincipalId(),
                        candidate.sessionId(),
                        cutoff,
                        ROW_BATCH_SIZE);
                if (rows > 0) {
                    ChatPrincipal principal = new ChatPrincipal(
                            candidate.ownerPrincipalId(), "TTL_CLEANUP", false);
                    summaryRepository.delete(principal, candidate.sessionId());
                    Boolean hasRemainingHistory = jdbcTemplate.queryForObject("""
                            SELECT EXISTS (
                                SELECT 1
                                FROM rag_chat_history
                                WHERE owner_principal_id = ?
                                  AND session_id = ?
                            )
                            """,
                            Boolean.class,
                            candidate.ownerPrincipalId(),
                            candidate.sessionId());
                    if (!Boolean.TRUE.equals(hasRemainingHistory)) {
                        jdbcTemplate.update(
                                "DELETE FROM spring_ai_chat_memory WHERE conversation_id = ?",
                                principal.memoryConversationId(candidate.sessionId()));
                    }
                }
                return rows;
            });
            return deleted != null ? deleted : 0;
        } finally {
            jdbcTemplate.update(
                    RELEASE_MAINTENANCE_LEASE_SQL,
                    candidate.ownerPrincipalId(),
                    candidate.sessionId(),
                    token);
        }
    }

    private int deleteLegacyRows(LocalDateTime cutoff) {
        return jdbcTemplate.update("""
                WITH victims AS (
                    SELECT id
                    FROM rag_chat_history
                    WHERE owner_principal_id IS NULL
                      AND created_at < ?
                    ORDER BY created_at ASC, id ASC
                    LIMIT ?
                )
                DELETE FROM rag_chat_history history
                USING victims
                WHERE history.id = victims.id
                """, cutoff, ROW_BATCH_SIZE);
    }

    private boolean hasCoordinatedCleanupDependencies() {
        return jdbcTemplate != null
                && summaryRepository != null
                && transactionTemplate != null
                && ragProperties != null;
    }

    private int maintenanceLeaseTtlMs() {
        int ttlSeconds = Math.max(
                10,
                ragProperties.getChat().getHistory().getLeaseTtlSeconds());
        int renewSeconds = Math.max(
                1,
                ragProperties.getChat().getHistory()
                        .getLeaseRenewIntervalSeconds());
        return Math.max(ttlSeconds, renewSeconds * 2 + 2) * 1_000;
    }

    private record ExpiredSession(
            String ownerPrincipalId,
            String sessionId) {
    }
}
