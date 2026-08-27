package com.springairag.core.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.entity.RagChatHistory;
import com.springairag.core.exception.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
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
            entity.setContentReferenceIndexComplete(
                    relatedDocumentIds == null || relatedDocumentIds.isBlank());
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
        DurableContentReferences references =
                reserveDurableContentReferences(relatedDocumentIds, sources);
        return saveDurable(
                principal,
                sessionId,
                userMessage,
                aiResponse,
                relatedDocumentIds,
                sources,
                turnStatus,
                metadata,
                references);
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
            DurableContentReferences references) {
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
        return saveAndIndex(entity, requireReferences(references));
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
        DurableContentReferences references =
                reserveDurableContentReferences(relatedDocumentIds, sources);
        return saveDurable(
                principal,
                sessionId,
                userMessage,
                aiResponse,
                relatedDocumentIds,
                sources,
                turnStatus,
                metadata,
                turnId,
                references);
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
            UUID turnId,
            DurableContentReferences references) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userMessage, "userMessage must not be null");
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
        return saveAndIndex(entity, requireReferences(references));
    }

    /**
     * Reserves every active source Collection before operation/history/memory
     * rows are written. The caller must invoke this inside its commit transaction.
     */
    public DurableContentReferences reserveDurableContentReferences(
            String relatedDocumentIds,
            List<ChatSource> sources) {
        List<Long> documentIds = normalizeDocumentIds(
                relatedDocumentIds, sources);
        if (documentIds.isEmpty()) {
            return new DurableContentReferences(List.of(), Map.of());
        }

        Map<Long, Long> initialCollections = loadActiveDocumentCollections(
                documentIds);
        TreeSet<Long> collectionIds = new TreeSet<>(
                initialCollections.values());
        for (Long collectionId : collectionIds) {
            int updated = jdbcTemplate.update("""
                    UPDATE rag_collection
                    SET chat_commit_fence_version =
                            chat_commit_fence_version + 1
                    WHERE id = ?
                      AND deleted = FALSE
                      AND enabled = TRUE
                      AND purged_at IS NULL
                    """, collectionId);
            if (updated != 1) {
                throw staleSourceReference();
            }
        }

        Map<Long, Long> currentCollections = loadActiveDocumentCollections(
                documentIds);
        if (!initialCollections.equals(currentCollections)) {
            throw staleSourceReference();
        }
        return new DurableContentReferences(
                List.copyOf(documentIds),
                Map.copyOf(currentCollections));
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

    private RagChatHistory saveAndIndex(
            RagChatHistory entity,
            DurableContentReferences references) {
        entity.setContentReferenceIndexComplete(false);
        RagChatHistory saved = jpaRepository.saveAndFlush(entity);
        if (!references.documentIds().isEmpty()) {
            List<Object[]> rows = references.documentIds().stream()
                    .map(documentId -> new Object[] { saved.getId(), documentId })
                    .toList();
            int[] inserted = jdbcTemplate.batchUpdate("""
                    INSERT INTO rag_chat_history_source_document(
                        history_id, document_id
                    ) VALUES (?, ?)
                    """, rows);
            for (int count : inserted) {
                if (count != 1) {
                    throw new IllegalStateException(
                            "Failed to index a Chat document reference");
                }
            }
        }
        int marked = jdbcTemplate.update("""
                UPDATE rag_chat_history
                SET content_reference_index_complete = TRUE
                WHERE id = ?
                  AND content_reference_index_complete = FALSE
                """, saved.getId());
        if (marked != 1) {
            throw new IllegalStateException(
                    "Failed to mark Chat content references complete");
        }
        saved.setContentReferenceIndexComplete(true);
        return saved;
    }

    private DurableContentReferences requireReferences(
            DurableContentReferences references) {
        return Objects.requireNonNull(
                references, "durable content references must not be null");
    }

    private List<Long> normalizeDocumentIds(
            String relatedDocumentIds,
            List<ChatSource> sources) {
        TreeSet<Long> ids = new TreeSet<>();
        if (sources != null) {
            for (ChatSource source : sources) {
                if (source != null) {
                    addPositiveLong(ids, source.getDocumentId());
                }
            }
        }
        if (relatedDocumentIds != null && !relatedDocumentIds.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(relatedDocumentIds);
                if (root == null || !root.isArray()) {
                    throw new IllegalArgumentException(
                            "relatedDocumentIds must be a JSON array");
                }
                for (JsonNode item : root) {
                    if (item.isIntegralNumber()) {
                        if (item.canConvertToLong() && item.longValue() > 0) {
                            ids.add(item.longValue());
                        }
                    } else if (item.isTextual()) {
                        addPositiveLong(ids, item.textValue());
                    } else {
                        throw new IllegalArgumentException(
                                "relatedDocumentIds contains an unsupported value");
                    }
                }
            } catch (RagException error) {
                throw error;
            } catch (Exception error) {
                throw new RagException(
                        ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                        "Chat document references are invalid",
                        error);
            }
        }
        return List.copyOf(ids);
    }

    private void addPositiveLong(TreeSet<Long> target, String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            return;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) {
                target.add(parsed);
            }
        } catch (NumberFormatException ignored) {
            // Values outside BIGINT cannot identify a database document.
        }
    }

    private Map<Long, Long> loadActiveDocumentCollections(
            List<Long> documentIds) {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (Long documentId : documentIds) {
            List<DocumentReferenceRow> rows = jdbcTemplate.query("""
                    SELECT d.id, d.collection_id
                    FROM rag_documents d
                    JOIN rag_collection c ON c.id = d.collection_id
                    WHERE d.id = ?
                      AND d.enabled = TRUE
                      AND c.deleted = FALSE
                      AND c.enabled = TRUE
                      AND c.purged_at IS NULL
                    """,
                    (resultSet, rowNum) -> new DocumentReferenceRow(
                            resultSet.getLong("id"),
                            resultSet.getLong("collection_id")),
                    documentId);
            if (rows.size() != 1) {
                throw staleSourceReference();
            }
            result.put(documentId, rows.get(0).collectionId());
        }
        return Map.copyOf(result);
    }

    private RagException staleSourceReference() {
        return new RagException(
                ErrorCode.COLLECTION_PURGE_CONFLICT,
                "Chat source documents changed before the turn could be committed");
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

    public record DurableContentReferences(
            List<Long> documentIds,
            Map<Long, Long> collectionIdsByDocument) {
        public DurableContentReferences {
            documentIds = documentIds == null
                    ? List.of() : List.copyOf(documentIds);
            collectionIdsByDocument = collectionIdsByDocument == null
                    ? Map.of() : Map.copyOf(collectionIdsByDocument);
        }
    }

    private record DocumentReferenceRow(long documentId, long collectionId) {
    }
}
