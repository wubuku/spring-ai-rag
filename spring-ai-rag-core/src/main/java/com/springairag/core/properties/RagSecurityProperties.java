package com.springairag.core.properties;

/**
 * Security Authentication Configuration
 *
 * <p>Example:
 * <pre>
 * rag:
 *   security:
 *     api-key: ${RAG_API_KEY:}
 *     root-api-key: ${RAG_ROOT_API_KEY:}
 *     enabled: false
 * </pre>
 */
public class RagSecurityProperties {

    private String apiKey = "";
    private String rootApiKey = "";
    private boolean enabled = false;
    /**
     * 是否允许对远程主机使用明文 http 的上游模型地址。
     * 默认拒绝，避免把 API key 通过明文网络发送出去；仅可信内网才应打开。
     */
    private boolean allowInsecureUpstream = false;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getRootApiKey() {
        return rootApiKey;
    }

    public void setRootApiKey(String rootApiKey) {
        this.rootApiKey = rootApiKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowInsecureUpstream() {
        return allowInsecureUpstream;
    }

    public void setAllowInsecureUpstream(boolean allowInsecureUpstream) {
        this.allowInsecureUpstream = allowInsecureUpstream;
    }
}
