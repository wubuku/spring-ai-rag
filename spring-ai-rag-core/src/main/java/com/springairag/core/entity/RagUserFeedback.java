package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * 用户反馈实体
 *
 * <p>记录用户对 RAG 检索结果和回答质量的反馈，包括点赞/点踩、评分、评论等。
 * 反馈数据可用于检索质量分析、模型调优依据、知识库补充决策。
 */
@Entity
@Table(name = "rag_user_feedback")
public class RagUserFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 ID */
    @Column(name = "session_id", nullable = false)
    private String sessionId;

    /** 查询文本 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String query;

    /** 检索到的文档 ID 列表（JSON） */
    @Column(name = "retrieved_document_ids", columnDefinition = "TEXT")
    private String retrievedDocumentIds;

    /** 反馈类型：THUMBS_UP / THUMBS_DOWN / RATING */
    @Column(name = "feedback_type", nullable = false, length = 50)
    private String feedbackType;

    /** 评分（1-5） */
    private Integer rating;

    /** 用户评论 */
    @Column(columnDefinition = "TEXT")
    private String comment;

    /** 用户认为有用的文档 ID 列表（JSON） */
    @Column(name = "selected_document_ids", columnDefinition = "TEXT")
    private String selectedDocumentIds;

    /** 用户停留时间（毫秒） */
    @Column(name = "dwell_time_ms")
    private Long dwellTimeMs;

    /** 扩展字段 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /** 创建时间 */
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
