package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * RAG 向量嵌入实体
 * 存储文档分块及其向量嵌入
 */
@Entity
@Table(name = "rag_embeddings", indexes = {
    @Index(name = "idx_rag_emb_doc_id", columnList = "document_id"),
    @Index(name = "idx_rag_emb_created", columnList = "created_at")
})
public class RagEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "chunk_text", columnDefinition = "TEXT", nullable = false)
    private String chunkText;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex = 0;

    @Column(name = "chunk_start_pos")
    private Integer chunkStartPos;

    @Column(name = "chunk_end_pos")
    private Integer chunkEndPos;

    /**
     * 向量嵌入 (1024维)
     * 使用 SiliconFlow BAAI/bge-m3 模型生成
     */
    @Column(name = "embedding", columnDefinition = "vector(1024)", nullable = false)
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 1024)
    private float[] embedding;

    /**
     * 嵌入元数据 (JSONB格式)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public RagEmbedding() {
    }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }

    public String getChunkText() { return chunkText; }
    public void setChunkText(String chunkText) { this.chunkText = chunkText; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    public Integer getChunkStartPos() { return chunkStartPos; }
    public void setChunkStartPos(Integer chunkStartPos) { this.chunkStartPos = chunkStartPos; }

    public Integer getChunkEndPos() { return chunkEndPos; }
    public void setChunkEndPos(Integer chunkEndPos) { this.chunkEndPos = chunkEndPos; }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
