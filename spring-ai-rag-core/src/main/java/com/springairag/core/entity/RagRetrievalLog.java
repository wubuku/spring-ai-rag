package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * RAG retrieval log entity.
 *
 * <p>Records detailed performance data per retrieval for trend analysis and tuning.
 * Auto-created each time HybridSearchAdvisor executes a retrieval.
 */
@Entity
@Table(name = "rag_retrieval_logs", indexes = {
    @Index(name = "idx_rag_log_session", columnList = "session_id"),
    @Index(name = "idx_rag_log_strategy", columnList = "retrieval_strategy"),
    @Index(name = "idx_rag_log_created", columnList = "created_at")
})
public class RagRetrievalLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Session ID */
    @Column(name = "session_id")
    private String sessionId;

    /** Query text */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String query;

    /** Retrieval strategy: hybrid/vector/fulltext */
    @Column(name = "retrieval_strategy", length = 50)
    private String retrievalStrategy;

    /** Vector search elapsed time (ms) */
    @Column(name = "vector_search_time_ms")
    private Long vectorSearchTimeMs;

    /** Fulltext search elapsed time (ms) */
    @Column(name = "fulltext_search_time_ms")
    private Long fulltextSearchTimeMs;

    /** Rerank elapsed time (ms) */
    @Column(name = "rerank_time_ms")
    private Long rerankTimeMs;

    /** Total elapsed time (ms) */
    @Column(name = "total_time_ms")
    private Long totalTimeMs;

    /** Number of results returned */
    @Column(name = "result_count")
    private Integer resultCount;

    /** Per-result scores (documentId → score) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_scores", columnDefinition = "jsonb")
    private Map<String, Object> resultScores;

    /** Extension fields */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /** Created at */
    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    // ==================== Constructors ====================

    public RagRetrievalLog() {
    }

    // ==================== Getters and Setters ====================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getRetrievalStrategy() {
        return retrievalStrategy;
    }

    public void setRetrievalStrategy(String retrievalStrategy) {
        this.retrievalStrategy = retrievalStrategy;
    }

    public Long getVectorSearchTimeMs() {
        return vectorSearchTimeMs;
    }

    public void setVectorSearchTimeMs(Long vectorSearchTimeMs) {
        this.vectorSearchTimeMs = vectorSearchTimeMs;
    }

    public Long getFulltextSearchTimeMs() {
        return fulltextSearchTimeMs;
    }

    public void setFulltextSearchTimeMs(Long fulltextSearchTimeMs) {
        this.fulltextSearchTimeMs = fulltextSearchTimeMs;
    }

    public Long getRerankTimeMs() {
        return rerankTimeMs;
    }

    public void setRerankTimeMs(Long rerankTimeMs) {
        this.rerankTimeMs = rerankTimeMs;
    }

    public Long getTotalTimeMs() {
        return totalTimeMs;
    }

    public void setTotalTimeMs(Long totalTimeMs) {
        this.totalTimeMs = totalTimeMs;
    }

    public Integer getResultCount() {
        return resultCount;
    }

    public void setResultCount(Integer resultCount) {
        this.resultCount = resultCount;
    }

    public Map<String, Object> getResultScores() {
        return resultScores;
    }

    public void setResultScores(Map<String, Object> resultScores) {
        this.resultScores = resultScores;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(ZonedDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
