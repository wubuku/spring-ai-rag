package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * RAG chat history entity.
 * Stores user-AI conversation records (business audit table).
 *
 * <p>Coexists with Spring AI's spring_ai_chat_memory table (used for LLM context).
 */
@Entity
@Table(name = "rag_chat_history", indexes = {
    @Index(name = "idx_rag_chat_session", columnList = "session_id"),
    @Index(name = "idx_rag_chat_created", columnList = "created_at")
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

    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }

    public String getAiResponse() { return aiResponse; }
    public void setAiResponse(String aiResponse) { this.aiResponse = aiResponse; }

    public String getRelatedDocumentIds() { return relatedDocumentIds; }
    public void setRelatedDocumentIds(String relatedDocumentIds) { this.relatedDocumentIds = relatedDocumentIds; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
