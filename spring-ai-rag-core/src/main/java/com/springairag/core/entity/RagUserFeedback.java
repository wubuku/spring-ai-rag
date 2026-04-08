package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * User feedback entity
 *
 * <p>Records user feedback on RAG retrieval results and answer quality,
 * including thumbs up/down, ratings, and comments.
 * Feedback data can be used for retrieval quality analysis, model tuning,
 * and knowledge base supplementation decisions.
 */
@Entity
@Table(name = "rag_user_feedback", indexes = {
    @Index(name = "idx_rag_fb_session", columnList = "session_id"),
    @Index(name = "idx_rag_fb_type", columnList = "feedback_type"),
    @Index(name = "idx_rag_fb_created", columnList = "created_at")
})
public class RagUserFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Session ID */
    @Column(name = "session_id", nullable = false)
    private String sessionId;

    /** Query text */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String query;

    /** List of retrieved document IDs (JSON) */
    @Column(name = "retrieved_document_ids", columnDefinition = "TEXT")
    private String retrievedDocumentIds;

    /** Feedback type: THUMBS_UP / THUMBS_DOWN / RATING */
    @Column(name = "feedback_type", nullable = false, length = 50)
    private String feedbackType;

    /** Rating (1-5) */
    private Integer rating;

    /** User comment */
    @Column(columnDefinition = "TEXT")
    private String comment;

    /** List of document IDs the user found useful (JSON) */
    @Column(name = "selected_document_ids", columnDefinition = "TEXT")
    private String selectedDocumentIds;

    /** User dwell time (milliseconds) */
    @Column(name = "dwell_time_ms")
    private Long dwellTimeMs;

    /** Extension metadata */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /** Creation time */
    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    // ==================== Constructors ====================

    public RagUserFeedback() {
    }

    // ==================== Getters and Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getRetrievedDocumentIds() { return retrievedDocumentIds; }
    public void setRetrievedDocumentIds(String retrievedDocumentIds) { this.retrievedDocumentIds = retrievedDocumentIds; }
    public String getFeedbackType() { return feedbackType; }
    public void setFeedbackType(String feedbackType) { this.feedbackType = feedbackType; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getSelectedDocumentIds() { return selectedDocumentIds; }
    public void setSelectedDocumentIds(String selectedDocumentIds) { this.selectedDocumentIds = selectedDocumentIds; }
    public Long getDwellTimeMs() { return dwellTimeMs; }
    public void setDwellTimeMs(Long dwellTimeMs) { this.dwellTimeMs = dwellTimeMs; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public ZonedDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(ZonedDateTime createdAt) { this.createdAt = createdAt; }
}
