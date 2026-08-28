package com.springairag.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 文件系统导入批次的可读元数据。
 *
 * <p>UUID 和虚拟路径仍是稳定身份；文件名只用于展示和检索。
 */
@Entity
@Table(name = "fs_import_batches")
public class FsImportBatch {

    @Id
    @Column(name = "import_id", nullable = false)
    private UUID importId;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "original_filename", nullable = false, length = 512)
    private String originalFilename;

    @Column(name = "display_name", nullable = false, length = 512)
    private String displayName;

    @Column(name = "entry_path", nullable = false, unique = true)
    private String entryPath;

    @Column(name = "original_path", nullable = false, unique = true)
    private String originalPath;

    @Column(name = "file_count", nullable = false)
    private Integer fileCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public FsImportBatch() {
    }

    public FsImportBatch(UUID importId,
                         String sourceType,
                         String originalFilename,
                         String displayName,
                         String entryPath,
                         String originalPath,
                         Integer fileCount) {
        this.importId = importId;
        this.sourceType = sourceType;
        this.originalFilename = originalFilename;
        this.displayName = displayName;
        this.entryPath = entryPath;
        this.originalPath = originalPath;
        this.fileCount = fileCount;
    }

    public UUID getImportId() { return importId; }
    public void setImportId(UUID importId) { this.importId = importId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEntryPath() { return entryPath; }
    public void setEntryPath(String entryPath) { this.entryPath = entryPath; }
    public String getOriginalPath() { return originalPath; }
    public void setOriginalPath(String originalPath) { this.originalPath = originalPath; }
    public Integer getFileCount() { return fileCount; }
    public void setFileCount(Integer fileCount) { this.fileCount = fileCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
