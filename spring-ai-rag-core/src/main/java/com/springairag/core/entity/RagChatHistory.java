package com.springairag.core.entity;

import com.springairag.api.dto.ChatSource;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RAG chat history entity.
 * Stores user-AI conversation records (business audit table).
 *
 * <p>Coexists with Spring AI's spring_ai_chat_memory table (used for LLM context).
 */
@Entity
@Table(name = "rag_chat_history", indexes = {
    @Index(name = "idx_rag_chat_session", columnList = "session_id"),
    @Index(name = "idx_rag_chat_created", columnList = "created_at"),
    @Index(name = "idx_rag_chat_owner_session_created",
            columnList = "owner_principal_id,session_id,created_at,id")
})
public class RagChatHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Session ID (used to group related conversations).
     */
    @Column(name = "session_id", nullable = false, length = 255)
    private String sessionId;

    /**
     * Stable authenticated principal ID. Null is reserved for pre-V32 legacy rows.
     */
    @Column(name = "owner_principal_id", length = 128)
    private String ownerPrincipalId;

    /**
     * User message
     */
    @Column(name = "user_message", columnDefinition = "TEXT", nullable = false)
    private String userMessage;

    /**
     * AI response
     */
    @Column(name = "ai_response", columnDefinition = "TEXT")
    private String aiResponse;

    /**
     * Associated document ID list (JSON string)
     */
    @Column(name = "related_document_ids", columnDefinition = "TEXT")
    private String relatedDocumentIds;

    /**
     * Citation snapshot captured when the turn was committed.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sources", columnDefinition = "jsonb")
    private List<ChatSource> sources;

    /**
     * Durable turn state. Current values are COMPLETE and CANCELLED.
     */
    @Column(name = "turn_status", nullable = false, length = 20)
    private String turnStatus = "COMPLETE";

    @Column(name = "turn_id")
    private UUID turnId;

    /**
     * Chat metadata (JSONB format).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /**
     * Created at
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public RagChatHistory() {
    }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getOwnerPrincipalId() { return ownerPrincipalId; }
    public void setOwnerPrincipalId(String ownerPrincipalId) {
        this.ownerPrincipalId = ownerPrincipalId;
    }

    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }

    public String getAiResponse() { return aiResponse; }
    public void setAiResponse(String aiResponse) { this.aiResponse = aiResponse; }

    public String getRelatedDocumentIds() { return relatedDocumentIds; }
    public void setRelatedDocumentIds(String relatedDocumentIds) { this.relatedDocumentIds = relatedDocumentIds; }

    public List<ChatSource> getSources() { return sources; }
    public void setSources(List<ChatSource> sources) {
        this.sources = sources != null ? List.copyOf(sources) : null;
    }

    public String getTurnStatus() { return turnStatus; }
    public void setTurnStatus(String turnStatus) { this.turnStatus = turnStatus; }

    public UUID getTurnId() { return turnId; }
    public void setTurnId(UUID turnId) { this.turnId = turnId; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
