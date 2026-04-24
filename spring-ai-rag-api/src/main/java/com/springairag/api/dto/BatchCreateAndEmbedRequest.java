package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

/**
 * Batch create and embed documents request
 *
 * <p>One-step: create document + chunk + embed vector.
 */
@Schema(description = "Batch create and embed documents request (one-step)")
public class BatchCreateAndEmbedRequest {

    @NotNull(message = "Collection ID must not be null")
    @Schema(description = "Target collection ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long collectionId;

    @NotEmpty(message = "Document list must not be empty")
    @Size(max = 100, message = "Batch operation must not exceed 100 items")
    @Valid
    @Schema(description = "List of documents (max 100)", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<DocumentRequest> documents;

    @Schema(description = "Whether to force re-embedding (ignore existing embeddings)", example = "false")
    private boolean force = false;

    public BatchCreateAndEmbedRequest() {}

    public BatchCreateAndEmbedRequest(Long collectionId, List<DocumentRequest> documents) {
        this.collectionId = collectionId;
        this.documents = documents;
    }

    public Long getCollectionId() { return collectionId; }
    public void setCollectionId(Long collectionId) { this.collectionId = collectionId; }

    public List<DocumentRequest> getDocuments() { return documents; }
    public void setDocuments(List<DocumentRequest> documents) { this.documents = documents; }

    public boolean isForce() { return force; }
    public void setForce(boolean force) { this.force = force; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchCreateAndEmbedRequest that = (BatchCreateAndEmbedRequest) o;
        return force == that.force &&
                Objects.equals(collectionId, that.collectionId) &&
                Objects.equals(documents, that.documents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(collectionId, documents, force);
    }

    @Override
    public String toString() {
        return "BatchCreateAndEmbedRequest{" +
                "collectionId=" + collectionId +
                ", documents=" + (documents == null ? null : documents.size() + " docs") +
                ", force=" + force +
                '}';
    }
}
