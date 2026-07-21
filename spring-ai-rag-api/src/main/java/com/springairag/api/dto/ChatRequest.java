package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RAG chat request.
 */
@Schema(description = "RAG chat request")
public class ChatRequest {

    @NotBlank(message = "Message content must not be blank")
    @Size(max = 10000, message = "Message content must not exceed 10000 characters")
    @Schema(description = "User message content", example = "What is the return policy?", requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;

    @Size(max = 36, message = "Session ID must not exceed 36 characters")
    @Schema(description = "Session ID for multi-turn conversation memory. If empty for first message, a new session is auto-generated",
            example = "conv-123", maxLength = 36, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String sessionId;

    @Min(value = 1, message = "Max results must be at least 1")
    @Max(value = 50, message = "Max results must not exceed 50")
    @Schema(description = "Maximum number of retrieval results", example = "5", defaultValue = "5")
    private int maxResults = 5;

    @Schema(description = "Whether to use hybrid search (vector + fulltext)", example = "true", defaultValue = "true")
    private boolean useHybridSearch = true;

    @Schema(description = "Whether to use reranking", example = "true", defaultValue = "true")
    private boolean useRerank = true;

    @Schema(description = "Domain extension identifier (optional)", example = "medical")
    private String domainId;

    @Schema(description = "Specify model (optional, e.g. \"minimax\" or \"openai/deepseek-chat\", null uses default model)", example = "minimax")
    private String model;

    @Schema(description = "Limit retrieval to documents in these collection IDs (multi-collection). Empty/null = all documents.",
            example = "[1, 2]")
    private List<Long> collectionIds;

    @Schema(description = "Limit retrieval to these document IDs (optional; intersected with collectionIds when both set)",
            example = "[10, 20]")
    private List<Long> documentIds;

    @Schema(description = "Additional metadata (passed through to domain extension)")
    private Map<String, Object> metadata;

    public ChatRequest() {}

    public ChatRequest(String message, String sessionId) {
        this.message = message;
        this.sessionId = sessionId;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) { this.maxResults = maxResults; }

    public boolean isUseHybridSearch() { return useHybridSearch; }
    public void setUseHybridSearch(boolean useHybridSearch) { this.useHybridSearch = useHybridSearch; }

    public boolean isUseRerank() { return useRerank; }
    public void setUseRerank(boolean useRerank) { this.useRerank = useRerank; }

    public String getDomainId() { return domainId; }
    public void setDomainId(String domainId) { this.domainId = domainId; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<Long> getCollectionIds() { return collectionIds; }
    public void setCollectionIds(List<Long> collectionIds) { this.collectionIds = collectionIds; }

    public List<Long> getDocumentIds() { return documentIds; }
    public void setDocumentIds(List<Long> documentIds) { this.documentIds = documentIds; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatRequest that = (ChatRequest) o;
        return maxResults == that.maxResults
                && useHybridSearch == that.useHybridSearch
                && useRerank == that.useRerank
                && Objects.equals(message, that.message)
                && Objects.equals(sessionId, that.sessionId)
                && Objects.equals(domainId, that.domainId)
                && Objects.equals(model, that.model)
                && Objects.equals(collectionIds, that.collectionIds)
                && Objects.equals(documentIds, that.documentIds)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, sessionId, maxResults, useHybridSearch, useRerank,
                domainId, model, collectionIds, documentIds, metadata);
    }

    @Override
    public String toString() {
        return "ChatRequest{message=" + message + ", sessionId=" + sessionId
                + ", maxResults=" + maxResults + ", useHybridSearch=" + useHybridSearch
                + ", useRerank=" + useRerank + ", domainId=" + domainId
                + ", model=" + model + ", collectionIds=" + collectionIds
                + ", documentIds=" + documentIds + ", metadata=" + metadata + "}";
    }
}
