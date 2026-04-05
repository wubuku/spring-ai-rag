package com.springairag.core.config;

/**
 * Per-endpoint timeout configuration for LLM API calls.
 * 
 * <p>Timeouts are applied at the RestClient level when calling external LLM APIs
 * (OpenAI, Anthropic, MiniMax). Different endpoints have different timeout
 * requirements based on expected response latency.
 * 
 * <p>Default values are conservative to avoid hanging requests. Increase for
 * complex queries or slow network conditions.
 * 
 * <p>Configuration prefix: {@code rag.timeout}
 */
public class RagTimeoutProperties {

    /**
     * Default connect timeout in milliseconds.
     * Applied when endpoint-specific timeout is not configured.
     */
    private int connectTimeoutMs = 10_000;

    /**
     * Default read timeout in milliseconds (time waiting for first byte).
     * Applied when endpoint-specific timeout is not configured.
     */
    private int readTimeoutMs = 60_000;

    /**
     * Chat ask endpoint timeout (ms). Long-running LLM calls need generous timeout.
     */
    private int chatAskMs = 120_000;

    /**
     * Chat stream endpoint timeout (ms). Streams need longer timeout for token generation.
     */
    private int chatStreamMs = 180_000;

    /**
     * Search endpoint timeout (ms). Retrieval is typically faster than generation.
     */
    private int searchMs = 30_000;

    /**
     * Embed endpoint timeout (ms). Embedding is usually fast, but large docs take time.
     */
    private int embedMs = 60_000;

    /**
     * Model comparison timeout per model (ms).
     */
    private int modelCompareMs = 90_000;

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getChatAskMs() {
        return chatAskMs;
    }

    public void setChatAskMs(int chatAskMs) {
        this.chatAskMs = chatAskMs;
    }

    public int getChatStreamMs() {
        return chatStreamMs;
    }

    public void setChatStreamMs(int chatStreamMs) {
        this.chatStreamMs = chatStreamMs;
    }

    public int getSearchMs() {
        return searchMs;
    }

    public void setSearchMs(int searchMs) {
        this.searchMs = searchMs;
    }

    public int getEmbedMs() {
        return embedMs;
    }

    public void setEmbedMs(int embedMs) {
        this.embedMs = embedMs;
    }

    public int getModelCompareMs() {
        return modelCompareMs;
    }

    public void setModelCompareMs(int modelCompareMs) {
        this.modelCompareMs = modelCompareMs;
    }
}
