package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * RAG 文档实体
 * 存储原始文档内容和元数据
 */
@Entity
@Table(name = "rag_documents", indexes = {
    @Index(name = "idx_rag_doc_created", columnList = "created_at"),
    @Index(name = "idx_rag_doc_collection", columnList = "collection_id"),
    @Index(name = "idx_rag_doc_hash", columnList = "content_hash"),
    @Index(name = "idx_rag_doc_status", columnList = "processing_status")
})
public class RagDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属集合 ID（FK → rag_collection）
     */
    @Column(name = "collection_id")
    private Long collectionId;

    /**
     * 文档标题
     */
    @Column(nullable = false, length = 255)
    private String title;

    /**
     * 文档内容（原始文本）
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * 文档来源（例如：file_name, url, api_endpoint）
     */
    @Column(length = 255)
    private String source;

    /**
     * 文档类型（例如：txt, pdf, markdown, html）
     */
    @Column(name = "document_type", length = 50)
    private String documentType;

    /**
     * 原始上传文件名
     */
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    /**
     * 内容 SHA-256 哈希值
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /**
     * 上次嵌入时的内容 SHA-256 哈希值
     *
     * <p>用于嵌入缓存：比较当前 contentHash 与 embeddedContentHash，
     * 如果一致说明内容未变更，跳过重嵌入。
     */
    @Column(name = "embedded_content_hash", length = 64)
    private String embeddedContentHash;

    /**
     * 文档大小（字节）
     */
    private Long size;

    /**
     * 处理状态: PENDING, PROCESSING, COMPLETED, FAILED
     */
    @Column(name = "processing_status", length = 20)
    private String processingStatus = "COMPLETED";

    /**
     * 处理失败时的错误信息
     */
    @Column(name = "processing_error", columnDefinition = "TEXT")
    private String processingError;

    /**
     * 文档元数据 (JSONB格式)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /**
     * 是否启用
     */
    @Column(nullable = false)
    private Boolean enabled = true;

    /**
     * 创建时间
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public RagDocument() {
    }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCollectionId() { return collectionId; }
    public void setCollectionId(Long collectionId) { this.collectionId = collectionId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getEmbeddedContentHash() { return embeddedContentHash; }
    public void setEmbeddedContentHash(String embeddedContentHash) { this.embeddedContentHash = embeddedContentHash; }

    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }

    public String getProcessingStatus() { return processingStatus; }
    public void setProcessingStatus(String processingStatus) { this.processingStatus = processingStatus; }

    public String getProcessingError() { return processingError; }
    public void setProcessingError(String processingError) { this.processingError = processingError; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
