package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Document version history entity.
 *
 * <p>Automatically records a snapshot on each content change (different content_hash).
 * Supports version rollback and change auditing.
 */
@Entity
@Table(name = "rag_document_versions", indexes = {
    @Index(name = "idx_doc_version_doc_id", columnList = "document_id"),
    @Index(name = "idx_doc_version_hash", columnList = "content_hash"),
    @Index(name = "idx_doc_version_created", columnList = "created_at")
})
public class RagDocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Associated document ID
     */
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /**
     * Version number (starting from 1)
     */
    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    /**
     * Content SHA-256 hash for this version
     */
    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    /**
     * Content snapshot (full text)
     */
    @Column(name = "content_snapshot", columnDefinition = "TEXT", nullable = false)
    private String contentSnapshot;

    /**
     * Content size (bytes)
     */
    private Long size;

    /**
     * Change type: CREATE, UPDATE, EMBED
     */
    @Column(name = "change_type", length = 20, nullable = false)
    private String changeType;

    /**
     * Change description (optional)
     */
    @Column(name = "change_description", length = 500)
    private String changeDescription;

    /**
     * Version metadata snapshot (JSONB)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadataSnapshot;

    /**
     * Created at
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public RagDocumentVersion() {
    }

    // ==================== Convenient factory methods ====================

    /**
     * Create initial version snapshot from RagDocument
     */
    public static RagDocumentVersion fromDocument(RagDocument doc, String changeType, String description) {
        RagDocumentVersion version = new RagDocumentVersion();
        version.setDocumentId(doc.getId());
        version.setContentHash(doc.getContentHash());
        version.setContentSnapshot(doc.getContent());
        version.setSize(doc.getSize());
        version.setChangeType(changeType);
        version.setChangeDescription(description);
        version.setMetadataSnapshot(doc.getMetadata());
        return version;
    }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }

    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getContentSnapshot() { return contentSnapshot; }
    public void setContentSnapshot(String contentSnapshot) { this.contentSnapshot = contentSnapshot; }

    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }

    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }

    public String getChangeDescription() { return changeDescription; }
    public void setChangeDescription(String changeDescription) { this.changeDescription = changeDescription; }

    public Map<String, Object> getMetadataSnapshot() { return metadataSnapshot; }
    public void setMetadataSnapshot(Map<String, Object> metadataSnapshot) { this.metadataSnapshot = metadataSnapshot; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
