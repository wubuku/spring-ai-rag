package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 文档版本历史实体
 *
 * <p>每次文档内容变更（content_hash 不同）时自动记录快照。
 * 支持版本回溯和变更审计。
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
     * 关联的文档 ID
     */
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /**
     * 版本号（从 1 开始递增）
     */
    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    /**
     * 该版本的内容 SHA-256 哈希值
     */
    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    /**
     * 内容快照（完整文本）
     */
    @Column(name = "content_snapshot", columnDefinition = "TEXT", nullable = false)
    private String contentSnapshot;

    /**
     * 内容大小（字节）
     */
    private Long size;

    /**
     * 变更类型：CREATE, UPDATE, EMBED
     */
    @Column(name = "change_type", length = 20, nullable = false)
    private String changeType;

    /**
     * 变更描述（可选）
     */
    @Column(name = "change_description", length = 500)
    private String changeDescription;

    /**
     * 版本元数据快照 (JSONB)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadataSnapshot;

    /**
     * 创建时间
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public RagDocumentVersion() {
    }

    // ==================== 便捷工厂方法 ====================

    /**
     * 从 RagDocument 创建初始版本快照
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
