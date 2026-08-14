package com.springairag.core.config;

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
}
