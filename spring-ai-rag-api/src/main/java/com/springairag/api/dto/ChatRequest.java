package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * RAG 问答请求
 */
@Schema(description = "RAG 问答请求")
public class ChatRequest {

    @NotBlank(message = "Message content must not be blank")
    @Size(max = 10000, message = "Message content must not exceed 10000 characters")
    @Schema(description = "User message content", example = "What is the return policy?", requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;

    @Schema(description = "Session ID for multi-turn conversation memory. If empty for first message, a new session is auto-generated", example = "conv-123", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
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

    @Schema(description = "额外元数据（透传给领域扩展）")
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

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
