package com.springairag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * RAG 问答请求
 */
public class ChatRequest {

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 10000, message = "消息内容不能超过 10000 字符")
    private String message;

    @NotBlank(message = "会话 ID 不能为空")
    private String sessionId;
    private int maxResults = 5;
    private boolean useHybridSearch = true;
    private boolean useRerank = true;
    private String domainId;
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

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
