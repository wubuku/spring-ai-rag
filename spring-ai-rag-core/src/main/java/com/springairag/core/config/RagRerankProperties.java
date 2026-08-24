package com.springairag.core.config;

/**
 * Re-ranking configuration under {@code rag.rerank}.
 */
public class RagRerankProperties {

    private boolean enabled = false;

    /**
     * Provider: {@code heuristic} (default keyword/diversity), {@code http} (SiliconFlow-style API),
     * or {@code none}.
     */
    private String provider = "heuristic";

    private float diversityWeight = 0.2f;

    /** Final result fallback when callers do not provide a positive limit. */
    private int topN = 5;

    /**
     * Maximum number of candidates retrieved before reranking.
     *
     * <p>The value is bounded so a configuration mistake cannot create an
     * unbounded database query or rerank request.</p>
     */
    private int candidateLimit = 20;

    /**
     * Preferred first-pass chunk count per nonblank document identity.
     *
     * <p>Zero disables document diversification. The selector backfills skipped
     * chunks when distinct documents cannot fill the final result limit.</p>
     */
    private int preferredMaxChunksPerDocument = 2;

    private String baseUrl = "https://api.siliconflow.cn";

    private String apiKey = "";

    private String model = "BAAI/bge-reranker-v2-m3";

    private int timeoutMs = 10000;

    /**
     * When HTTP rerank fails, fall back to heuristic instead of original order.
     */
    private boolean fallbackToHeuristic = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public float getDiversityWeight() {
        return diversityWeight;
    }

    public void setDiversityWeight(float diversityWeight) {
        this.diversityWeight = diversityWeight;
    }

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = Math.min(100, Math.max(1, candidateLimit));
    }

    public int getPreferredMaxChunksPerDocument() {
        return preferredMaxChunksPerDocument;
    }

    public void setPreferredMaxChunksPerDocument(
            int preferredMaxChunksPerDocument) {
        this.preferredMaxChunksPerDocument =
                Math.min(100, Math.max(0, preferredMaxChunksPerDocument));
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isFallbackToHeuristic() {
        return fallbackToHeuristic;
    }

    public void setFallbackToHeuristic(boolean fallbackToHeuristic) {
        this.fallbackToHeuristic = fallbackToHeuristic;
    }
}
