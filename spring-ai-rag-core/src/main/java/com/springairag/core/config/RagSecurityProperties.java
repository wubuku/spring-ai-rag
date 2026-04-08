package com.springairag.core.config;

/**
 * Security Authentication Configuration
 *
 * <p>Example:
 * <pre>
 * rag:
 *   security:
 *     api-key: ${RAG_API_KEY:}
 *     enabled: false
 * </pre>
 */
public class RagSecurityProperties {

    private String apiKey = "";
    private boolean enabled = false;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
