package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.JsonNode;
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

    @Column(name = "source_revision_snapshot", length = 255)
    private String sourceRevisionSnapshot;

    @Column(name = "title_snapshot", length = 255)
    private String titleSnapshot;

    @Column(name = "source_snapshot", length = 255)
    private String sourceSnapshot;

    @Column(name = "document_type_snapshot", length = 50)
    private String documentTypeSnapshot;

    @Column(name = "original_filename_snapshot", length = 255)
    private String originalFilenameSnapshot;

    @Column(name = "collection_id_snapshot")
    private Long collectionIdSnapshot;

    @Column(name = "source_namespace_snapshot", length = 128)
    private String sourceNamespaceSnapshot;

    @Column(name = "enabled_snapshot")
    private Boolean enabledSnapshot;

    @Column(name = "disabled_at_snapshot")
    private LocalDateTime disabledAtSnapshot;

    @Column(name = "source_deleted_at_snapshot")
    private LocalDateTime sourceDeletedAtSnapshot;

    @Column(name = "snapshot_completeness", nullable = false, length = 40)
    private String snapshotCompleteness = "CONTENT_AND_METADATA_ONLY";

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
     * JSON payload snapshot for structured records.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "jsonb_payload_snapshot", columnDefinition = "jsonb")
    private JsonNode jsonbPayloadSnapshot;

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
        version.setSourceRevisionSnapshot(doc.getSourceRevision());
        version.setTitleSnapshot(doc.getTitle());
        version.setSourceSnapshot(doc.getSource());
        version.setDocumentTypeSnapshot(doc.getDocumentType());
        version.setOriginalFilenameSnapshot(doc.getOriginalFilename());
        version.setCollectionIdSnapshot(doc.getCollectionId());
        version.setSourceNamespaceSnapshot(doc.getSourceNamespace());
        version.setEnabledSnapshot(doc.getEnabled());
        version.setDisabledAtSnapshot(doc.getDisabledAt());
        version.setSourceDeletedAtSnapshot(doc.getSourceDeletedAt());
        version.setSnapshotCompleteness("FULL");
        version.setContentSnapshot(doc.getContent());
        version.setSize(doc.getSize());
        version.setChangeType(changeType);
        version.setChangeDescription(description);
        version.setMetadataSnapshot(doc.getMetadata());
        version.setJsonbPayloadSnapshot(doc.getJsonbPayload() == null
                ? null : doc.getJsonbPayload().deepCopy());
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

    public String getSourceRevisionSnapshot() { return sourceRevisionSnapshot; }
    public void setSourceRevisionSnapshot(String sourceRevisionSnapshot) {
        this.sourceRevisionSnapshot = sourceRevisionSnapshot;
    }

    public String getTitleSnapshot() { return titleSnapshot; }
    public void setTitleSnapshot(String titleSnapshot) { this.titleSnapshot = titleSnapshot; }

    public String getSourceSnapshot() { return sourceSnapshot; }
    public void setSourceSnapshot(String sourceSnapshot) { this.sourceSnapshot = sourceSnapshot; }

    public String getDocumentTypeSnapshot() { return documentTypeSnapshot; }
    public void setDocumentTypeSnapshot(String documentTypeSnapshot) {
        this.documentTypeSnapshot = documentTypeSnapshot;
    }

    public String getOriginalFilenameSnapshot() { return originalFilenameSnapshot; }
    public void setOriginalFilenameSnapshot(String originalFilenameSnapshot) {
        this.originalFilenameSnapshot = originalFilenameSnapshot;
    }

    public Long getCollectionIdSnapshot() { return collectionIdSnapshot; }
    public void setCollectionIdSnapshot(Long collectionIdSnapshot) {
        this.collectionIdSnapshot = collectionIdSnapshot;
    }

    public String getSourceNamespaceSnapshot() { return sourceNamespaceSnapshot; }
    public void setSourceNamespaceSnapshot(String sourceNamespaceSnapshot) {
        this.sourceNamespaceSnapshot = sourceNamespaceSnapshot;
    }

    public Boolean getEnabledSnapshot() { return enabledSnapshot; }
    public void setEnabledSnapshot(Boolean enabledSnapshot) {
        this.enabledSnapshot = enabledSnapshot;
    }

    public LocalDateTime getDisabledAtSnapshot() { return disabledAtSnapshot; }
    public void setDisabledAtSnapshot(LocalDateTime disabledAtSnapshot) {
        this.disabledAtSnapshot = disabledAtSnapshot;
    }

    public LocalDateTime getSourceDeletedAtSnapshot() { return sourceDeletedAtSnapshot; }
    public void setSourceDeletedAtSnapshot(LocalDateTime sourceDeletedAtSnapshot) {
        this.sourceDeletedAtSnapshot = sourceDeletedAtSnapshot;
    }

    public String getSnapshotCompleteness() { return snapshotCompleteness; }
    public void setSnapshotCompleteness(String snapshotCompleteness) {
        this.snapshotCompleteness = snapshotCompleteness;
    }

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

    public JsonNode getJsonbPayloadSnapshot() { return jsonbPayloadSnapshot; }
    public void setJsonbPayloadSnapshot(JsonNode jsonbPayloadSnapshot) {
        this.jsonbPayloadSnapshot = jsonbPayloadSnapshot;
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
