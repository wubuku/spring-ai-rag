package com.springairag.core.repository;

import com.springairag.core.util.SimpleJsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * RAG 对话历史仓储（业务审计表）
 *
 * <p>写入 rag_chat_history 表，用于业务层查询对话记录。
 * 与 Spring AI 的 spring_ai_chat_memory 表（LLM 上下文用）双表共存。
 */
@Repository
public class RagChatHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RagChatHistoryRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public RagChatHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存对话记录到业务审计表
     */
    public void save(String sessionId, String userMessage, String aiResponse) {
        save(sessionId, userMessage, aiResponse, null, null);
    }

    /**
     * 保存对话记录到业务审计表（含关联文档和元数据）
     */
    public void save(String sessionId, String userMessage, String aiResponse,
                     String relatedDocumentIds, Map<String, Object> metadata) {
        try {
            String metadataJson = metadata != null ? SimpleJsonUtil.toJson(metadata) : null;
            jdbcTemplate.update(
                    "INSERT INTO rag_chat_history (session_id, user_message, ai_response, related_document_ids, metadata, created_at) " +
                            "VALUES (?, ?, ?, ?, ?::jsonb, ?)",
                    sessionId, userMessage, aiResponse, relatedDocumentIds, metadataJson, Timestamp.from(Instant.now()));
            log.debug("Saved chat history for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to save chat history for session: {}", sessionId, e);
        }
    }

    /**
     * 查询会话的历史记录
     */
    public List<Map<String, Object>> findBySessionId(String sessionId, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT id, session_id, user_message, ai_response, related_document_ids, metadata, created_at " +
                        "FROM rag_chat_history WHERE session_id = ? ORDER BY created_at DESC LIMIT ?",
                sessionId, limit);
    }

    /**
     * 查询会话的历史记录（默认 50 条）
     */
    public List<Map<String, Object>> findBySessionId(String sessionId) {
        return findBySessionId(sessionId, 50);
    }

    /**
     * 删除会话的所有历史记录
     *
     * @return 删除的记录数
     */
    public int deleteBySessionId(String sessionId) {
        int deleted = jdbcTemplate.update("DELETE FROM rag_chat_history WHERE session_id = ?", sessionId);
        log.info("Deleted {} chat history records for session: {}", deleted, sessionId);
        return deleted;
    }
}
