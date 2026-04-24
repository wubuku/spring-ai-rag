package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

/**
 * Batch document operation request
 *
 * <p>Supports two modes: create-only (default) and create-with-embed (embed=true).
 */
@Schema(description = "Batch document operation request")
public class BatchDocumentRequest {

    @NotEmpty(message = "Document list must not be empty")
    @Size(max = 100, message = "Batch operation must not exceed 100 items")
    @Valid
    @Schema(description = "List of documents (max 100)")
    private List<DocumentRequest> documents;

    @Schema(description = "Whether to auto-embed vectors after creation (default false: create documents only)", example = "false")
    private boolean embed = false;

    @Schema(description = "Associated collection ID (only effective when embed=true)", example = "1")
    private Long collectionId;

    @Schema(description = "Whether to force re-embedding (only effective when embed=true, true=ignore existing embeddings and regenerate)", example = "false")
    private boolean force = false;

    public BatchDocumentRequest() {}

    public BatchDocumentRequest(List<DocumentRequest> documents) {
        this.documents = documents;
    }

    public List<DocumentRequest> getDocuments() { return documents; }
    public void setDocuments(List<DocumentRequest> documents) { this.documents = documents; }
    public boolean isEmbed() { return embed; }
    public void setEmbed(boolean embed) { this.embed = embed; }
    public Long getCollectionId() { return collectionId; }
    public void setCollectionId(Long collectionId) { this.collectionId = collectionId; }
    public boolean isForce() { return force; }
    public void setForce(boolean force) { this.force = force; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchDocumentRequest that = (BatchDocumentRequest) o;
        return embed == that.embed &&
                force == that.force &&
                Objects.equals(documents, that.documents) &&
                Objects.equals(collectionId, that.collectionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documents, embed, collectionId, force);
    }

    @Override
    public String toString() {
        return "BatchDocumentRequest{" +
                "documents=" + (documents == null ? null : documents.size() + " docs") +
                ", embed=" + embed +
                ", collectionId=" + collectionId +
                ", force=" + force +
                '}';
    }
}
