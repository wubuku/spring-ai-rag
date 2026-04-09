package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * RAG Document Entity
 * Stores original document content and metadata
 */
@Entity
@Table(name = "rag_documents", indexes = {
    @Index(name = "idx_rag_doc_created", columnList = "created_at"),
    @Index(name = "idx_rag_doc_collection", columnList = "collection_id"),
    @Index(name = "idx_rag_doc_hash", columnList = "content_hash"),
    @Index(name = "idx_rag_doc_status", columnList = "processing_status"),
    @Index(name = "idx_rag_doc_type", columnList = "document_type"),
    @Index(name = "idx_rag_doc_enabled", columnList = "enabled")
})
public class RagDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Optimistic locking version field.
     */
    @Version
    private Long version;

    /**
     * Owning collection ID (FK → rag_collection)
     */
    @Column(name = "collection_id")
    private Long collectionId;

    /**
     * Document title
     */
    @Column(nullable = false, length = 255)
    private String title;

    /**
     * Document content (raw text)
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * Document source (e.g. file_name, url, api_endpoint)
     */
    @Column(length = 255)
    private String source;

    /**
     * Document type (e.g. txt, pdf, markdown, html)
     */
    @Column(name = "document_type", length = 50)
    private String documentType;

    /**
     * Original uploaded filename
     */
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    /**
     * Content SHA-256 hash value
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /**
     * SHA-256 hash of the content at last embedding time.
     *
     * <p>Used for embedding cache: compares current contentHash with
     * embeddedContentHash — if equal, content has not changed and
     * re-embedding is skipped.
     */
    @Column(name = "embedded_content_hash", length = 64)
    private String embeddedContentHash;

    /**
     * Document size (bytes)
     */
    private Long size;

    /**
     * Processing status: PENDING, PROCESSING, COMPLETED, FAILED
     */
    @Column(name = "processing_status", length = 20)
    private String processingStatus = "COMPLETED";

    /**
     * Error message on processing failure
     */
    @Column(name = "processing_error", columnDefinition = "TEXT")
    private String processingError;

    /**
     * Document metadata (JSONB format)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /**
     * Whether document is enabled
     */
    @Column(nullable = false)
    private Boolean enabled = true;

    /**
     * Creation timestamp
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Last update timestamp
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
