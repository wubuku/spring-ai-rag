package com.springairag.core.repository;

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
            String metadataJson = metadata != null ? toJson(metadata) : null;
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
     * 简单的 Map → JSON 字符串转换（避免引入 Jackson 依赖）
     */
    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append("\"").append(escapeJson(value.toString())).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
