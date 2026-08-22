package com.springairag.core.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.entity.RagChatHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;


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
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userMessage, "userMessage must not be null");
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
     * Durable production write. Failures propagate so the coordinator can roll back
     * history, shared ChatMemory, and lease release as one transaction.
     */
    public RagChatHistory saveDurable(
            ChatPrincipal principal,
            String sessionId,
            String userMessage,
            String aiResponse,
            String relatedDocumentIds,
            List<ChatSource> sources,
            String turnStatus,
            Map<String, Object> metadata) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userMessage, "userMessage must not be null");
        RagChatHistory entity = new RagChatHistory();
        entity.setOwnerPrincipalId(principal.id());
        entity.setSessionId(sessionId);
        entity.setUserMessage(userMessage);
        entity.setAiResponse(aiResponse);
        entity.setRelatedDocumentIds(relatedDocumentIds);
        entity.setSources(sources);
        entity.setTurnStatus(turnStatus != null ? turnStatus : "COMPLETE");
        entity.setMetadata(metadata);
        return jpaRepository.saveAndFlush(entity);
    }

    public RagChatHistory saveDurable(
            ChatPrincipal principal,
            String sessionId,
            String userMessage,
            String aiResponse,
            String relatedDocumentIds,
            List<ChatSource> sources,
            String turnStatus,
            Map<String, Object> metadata,
            UUID turnId) {
        RagChatHistory entity = new RagChatHistory();
        entity.setTurnId(turnId);
        entity.setOwnerPrincipalId(principal.id());
        entity.setSessionId(sessionId);
        entity.setUserMessage(userMessage);
        entity.setAiResponse(aiResponse);
        entity.setRelatedDocumentIds(relatedDocumentIds);
        entity.setSources(sources);
        entity.setTurnStatus(turnStatus != null ? turnStatus : "COMPLETE");
        entity.setMetadata(metadata);
        return jpaRepository.saveAndFlush(entity);
    }

    /**
     * Query chat history by session ID.
     */
    public List<ChatHistoryResponse> findBySessionId(String sessionId, int limit) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
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
     * Principal-scoped history read used by production endpoints.
     */
    public List<ChatHistoryResponse> findByPrincipalAndSession(
            ChatPrincipal principal,
            String sessionId,
            int limit) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return jpaRepository.findVisibleByOwnerAndSessionNewestFirst(
                        principal.id(),
                        sessionId,
                        canReadLegacy(principal),
                        PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Canonical committed baseline. Legacy null-owner rows are intentionally excluded.
     */
    public List<ChatHistoryResponse> findOwnedBaseline(
            ChatPrincipal principal,
            String sessionId,
            int limit) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        List<RagChatHistory> newestFirst =
                jpaRepository.findOwnedBySessionNewestFirst(
                        principal.id(),
                        sessionId,
                        PageRequest.of(0, safeLimit));
        List<RagChatHistory> chronological = new java.util.ArrayList<>(
                newestFirst);
        java.util.Collections.reverse(chronological);
        return chronological.stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Bounded oldest-first source rows for durable conversation compaction.
     */
    public List<ChatHistoryResponse> findOwnedAfterHistoryId(
            ChatPrincipal principal,
            String sessionId,
            long afterHistoryId,
            int limit) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (afterHistoryId < 0) {
            throw new IllegalArgumentException(
                    "afterHistoryId must not be negative");
        }
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return jpaRepository.findOwnedAfterHistoryId(
                        principal.id(),
                        sessionId,
                        afterHistoryId,
                        PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Delete all history for a session (also clears Spring AI ChatMemory).
     *
     * @return Number of records deleted (rag_chat_history only)
     */
    @Transactional
    public int deleteBySessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
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
     * Deletes only rows owned by the current principal. Legacy null-owner rows are
     * never removed through the ordinary session endpoint.
     */
    @Transactional
    public int deleteByPrincipalAndSession(
            ChatPrincipal principal,
            String sessionId) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return jpaRepository.deleteOwnedBySession(principal.id(), sessionId);
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
                entity.getSources(),
                entity.getTurnStatus(),
                enumValue(entity.getMetadata(), "mode", ChatMode.class, ChatMode.KNOWLEDGE),
                stringValue(entity.getMetadata(), "requestedModel"),
                stringValue(entity.getMetadata(), "resolvedModel"),
                entity.getCreatedAt()
        );
    }

    private boolean canReadLegacy(ChatPrincipal principal) {
        return principal.id().equals("root:environment-root")
                || principal.id().equals("legacy:static")
                || principal.id().equals("local:auth-disabled");
    }

    private String stringValue(Map<String, Object> metadata, String key) {
        Object value = metadata != null ? metadata.get(key) : null;
        return value != null ? String.valueOf(value) : null;
    }

    private <E extends Enum<E>> E enumValue(
            Map<String, Object> metadata,
            String key,
            Class<E> type,
            E fallback) {
        String value = stringValue(metadata, key);
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
