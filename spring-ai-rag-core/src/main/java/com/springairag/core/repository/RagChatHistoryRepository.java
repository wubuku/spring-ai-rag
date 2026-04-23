package com.springairag.core.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.core.entity.RagChatHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;


/**
 * RAG chat history repository (business audit table).
 *
 * <p>Writes to rag_chat_history table for business-layer chat record queries.
 * Coexists with Spring AI's spring_ai_chat_memory table (used for LLM context).
 *
 * <p>Internally uses Spring Data JPA, maintaining the same public API as the original JdbcTemplate version.
 */
@Repository
public class RagChatHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RagChatHistoryRepository.class);

    private final RagChatHistoryJpaRepository jpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RagChatHistoryRepository(RagChatHistoryJpaRepository jpaRepository,
                                   JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper) {
        this.jpaRepository = jpaRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Save chat record to business audit table.
     */
    public void save(String sessionId, String userMessage, String aiResponse) {
        save(sessionId, userMessage, aiResponse, null, null);
    }

    /**
     * Save chat record to business audit table (with related documents and metadata).
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
     * Query chat history by session ID.
     */
    public List<ChatHistoryResponse> findBySessionId(String sessionId, int limit) {
        List<RagChatHistory> results = jpaRepository.findBySessionIdOrderByCreatedAtDesc(
                sessionId, PageRequest.of(0, limit));
        return results.stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Query chat history by session ID (default 50 records).
     */
    public List<ChatHistoryResponse> findBySessionId(String sessionId) {
        return findBySessionId(sessionId, 50);
    }

    /**
     * Delete all history for a session (also clears Spring AI ChatMemory).
     *
     * @return Number of records deleted (rag_chat_history only)
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
     * Delete chat history older than the given cutoff (TTL cleanup).
     *
     * @return Number of records deleted
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
     * Convert entity to DTO.
     */
    private ChatHistoryResponse toDto(RagChatHistory entity) {
        List<Long> docIds = null;
        if (entity.getRelatedDocumentIds() != null && !entity.getRelatedDocumentIds().isBlank()) {
            try {
                docIds = objectMapper.readValue(entity.getRelatedDocumentIds(),
                        new TypeReference<List<Long>>() {});
            } catch (Exception e) { // Resilience: malformed JSON should not break chat history retrieval
                log.debug("Failed to parse relatedDocumentIds JSON: {}", entity.getRelatedDocumentIds());
            }
        }
        return new ChatHistoryResponse(
                entity.getId(),
                entity.getSessionId(),
                entity.getUserMessage(),
                entity.getAiResponse(),
                docIds,
                entity.getMetadata(),
                entity.getCreatedAt()
        );
    }
}
