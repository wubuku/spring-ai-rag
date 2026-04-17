package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * File System File Entity
 *
 * <p>Stores files imported from the local file system — PDF, Markdown, images, etc.
 * The {@code path} field serves as the primary key; there is no separate tree structure.
 * When restoring, split the path by "/" to reconstruct the directory hierarchy.
 *
 * <p>For PDF imports, the directory tree contains:
 * <ul>
 *   <li>The original PDF binary</li>
 *   <li>An entry Markdown file (convention: base name + ".md")</li>
 *   <li>Extracted images referenced by the Markdown</li>
 * </ul>
 */
@Entity
@Table(name = "fs_files")
public class FsFile {

    /**
     * File path, used as primary key.
     * Uses forward-slash "/" as the directory separator convention.
     * Example: "papers/skin-care-research/nicotinamide_in_cosmetics_smith.pdf"
     */
    @Id
    @Column(name = "path")
    private String path;

    /**
     * Whether this file is a text-based file (Markdown, etc.).
     * Determines which content column is populated.
     */
    @Column(name = "is_text", nullable = false)
    private Boolean isText = false;

    /**
     * Binary content. Used for all non-text files (PDF, images, etc.).
     */
    @Column(name = "content_bin", nullable = false)
    private byte[] contentBin;

    /**
     * Text content. Used for text-based files (Markdown, etc.).
     */
    @Column(name = "content_txt")
    private String contentTxt;

    /**
     * MIME type of the file (e.g., "application/pdf", "image/png", "text/markdown").
     */
    @Column(name = "mime_type")
    private String mimeType;

    /**
     * File size in bytes.
     */
    @Column(name = "file_size")
    private Long fileSize;

    /**
     * Creation timestamp.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Last update timestamp.
     */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public FsFile() {
    }

    public FsFile(String path, Boolean isText, byte[] contentBin, String contentTxt,
                  String mimeType, Long fileSize) {
        this.path = path;
        this.isText = isText;
        this.contentBin = contentBin;
        this.contentTxt = contentTxt;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
    }

    // Getters and Setters

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Boolean getIsText() { return isText; }
    public void setIsText(Boolean isText) { this.isText = isText; }

    public byte[] getContentBin() { return contentBin; }
    public void setContentBin(byte[] contentBin) { this.contentBin = contentBin; }

    public String getContentTxt() { return contentTxt; }
    public void setContentTxt(String contentTxt) { this.contentTxt = contentTxt; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
