package com.springairag.core.repository;

import com.springairag.core.entity.RagChatHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 对话历史仓储（业务审计表）
 *
 * <p>写入 rag_chat_history 表，用于业务层查询对话记录。
 * 与 Spring AI 的 spring_ai_chat_memory 表（LLM 上下文用）双表共存。
 *
 * <p>内部使用 Spring Data JPA 实现，保持与原 JdbcTemplate 版本相同的公共 API。
 */
@Repository
public class RagChatHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RagChatHistoryRepository.class);

    private final RagChatHistoryJpaRepository jpaRepository;
    private final JdbcTemplate jdbcTemplate;

    public RagChatHistoryRepository(RagChatHistoryJpaRepository jpaRepository,
                                   JdbcTemplate jdbcTemplate) {
        this.jpaRepository = jpaRepository;
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
            RagChatHistory entity = new RagChatHistory();
            entity.setSessionId(sessionId);
            entity.setUserMessage(userMessage);
            entity.setAiResponse(aiResponse);
            entity.setRelatedDocumentIds(relatedDocumentIds);
            entity.setMetadata(metadata);
            jpaRepository.save(entity);
            log.debug("Saved chat history for session: {}", sessionId);
        } catch (Exception e) { // Resilience: chat history is non-critical
            log.error("Failed to save chat history for session: {}", sessionId, e);
        }
    }

    /**
     * 查询会话的历史记录
     */
    public List<Map<String, Object>> findBySessionId(String sessionId, int limit) {
        List<RagChatHistory> results = jpaRepository.findBySessionIdOrderByCreatedAtDesc(
                sessionId, PageRequest.of(0, limit));
        return results.stream()
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    /**
     * 查询会话的历史记录（默认 50 条）
     */
    public List<Map<String, Object>> findBySessionId(String sessionId) {
        return findBySessionId(sessionId, 50);
    }

    /**
     * 删除会话的所有历史记录（同时清理 Spring AI ChatMemory）
     *
     * @return 删除的记录数（仅 rag_chat_history）
     */
    @Transactional
    public int deleteBySessionId(String sessionId) {
        int deleted = jpaRepository.deleteBySessionId(sessionId);
        log.info("Deleted {} chat history records for session: {}", deleted, sessionId);
        try {
            jdbcTemplate.update(
                    "DELETE FROM spring_ai_chat_memory WHERE conversation_id = ?",
                    sessionId);
            log.info("Cleared Spring AI ChatMemory for session: {}", sessionId);
        } catch (Exception e) { // Resilience: non-critical cleanup (table might not exist in test)
            log.debug("Failed to clear Spring AI ChatMemory for session {}: {}", sessionId, e.getMessage());
        }
        return deleted;
    }

    /**
     * 删除指定时间之前的聊天历史（TTL 清理）
     *
     * @return 删除的记录数
     */
    @Transactional
    public int deleteOlderThan(java.time.LocalDateTime cutoff) {
        if (cutoff == null) {
            return 0;
        }
        int deleted = jpaRepository.deleteOlderThan(cutoff);
        log.info("TTL cleanup: deleted {} chat history records older than {}", deleted, cutoff);
        return deleted;
    }

    /**
     * 将实体转换为 Map（保持向后兼容）
     */
    private Map<String, Object> toMap(RagChatHistory entity) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("session_id", entity.getSessionId());
        map.put("user_message", entity.getUserMessage());
        map.put("ai_response", entity.getAiResponse());
        if (entity.getRelatedDocumentIds() != null) {
            map.put("related_document_ids", entity.getRelatedDocumentIds());
        }
        if (entity.getMetadata() != null) {
            map.put("metadata", entity.getMetadata());
        }
        map.put("created_at", entity.getCreatedAt());
        return map;
    }
}
