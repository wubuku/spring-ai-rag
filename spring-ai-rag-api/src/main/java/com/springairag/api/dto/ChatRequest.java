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

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 10000, message = "消息内容不能超过 10000 字符")
    @Schema(description = "用户消息内容", example = "退货政策是什么？", requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;

    @Schema(description = "会话 ID，用于多轮对话记忆。首次对话可为空，服务端自动生成新会话", example = "conv-123", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String sessionId;

    @Min(value = 1, message = "最大检索结果数量最小为 1")
    @Max(value = 50, message = "最大检索结果数量不超过 50")
    @Schema(description = "最大检索结果数量", example = "5", defaultValue = "5")
    private int maxResults = 5;

    @Schema(description = "是否使用混合检索（向量 + 全文）", example = "true", defaultValue = "true")
    private boolean useHybridSearch = true;

    @Schema(description = "是否使用重排序", example = "true", defaultValue = "true")
    private boolean useRerank = true;

    @Schema(description = "领域扩展标识（可选）", example = "medical")
    private String domainId;

    @Schema(description = "指定模型（可选，如 \"minimax\" 或 \"openai/deepseek-chat\"，null 使用默认模型）", example = "minimax")
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
