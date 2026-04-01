package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * RAG 检索日志实体
 *
 * <p>记录每次检索的详细性能数据，用于趋势分析和性能调优。
 * 每次 HybridSearchAdvisor 执行检索时自动创建一条记录。
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

    /** 会话ID */
    @Column(name = "session_id")
    private String sessionId;

    /** 查询文本 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String query;

    /** 检索策略：hybrid/vector/fulltext */
    @Column(name = "retrieval_strategy", length = 50)
    private String retrievalStrategy;

    /** 向量检索耗时（毫秒） */
    @Column(name = "vector_search_time_ms")
    private Long vectorSearchTimeMs;

    /** 全文检索耗时（毫秒） */
    @Column(name = "fulltext_search_time_ms")
    private Long fulltextSearchTimeMs;

    /** 重排序耗时（毫秒） */
    @Column(name = "rerank_time_ms")
    private Long rerankTimeMs;

    /** 总耗时（毫秒） */
    @Column(name = "total_time_ms")
    private Long totalTimeMs;

    /** 返回结果数 */
    @Column(name = "result_count")
    private Integer resultCount;

    /** 各结果得分（文档ID → 分数） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_scores", columnDefinition = "jsonb")
    private Map<String, Object> resultScores;

    /** 扩展字段 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /** 创建时间 */
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
