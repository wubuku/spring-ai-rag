package com.springairag.core.config;

/**
 * 持久化 embedding job 配置。
 */
public class RagEmbeddingJobProperties {

    private boolean enabled;
    private int pollIntervalMs = 1000;
    private int claimBatchSize = 4;
    private int leaseSeconds = 120;
    private int defaultMaxAttempts = 3;
    private int maxAttempts = 5;
    private int maxDocumentsPerBatch = 1000;
    private int retryBackoffSeconds = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(int value) { pollIntervalMs = Math.max(250, value); }
    public int getClaimBatchSize() { return claimBatchSize; }
    public void setClaimBatchSize(int value) { claimBatchSize = Math.max(1, Math.min(32, value)); }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int value) { leaseSeconds = Math.max(30, value); }
    public int getDefaultMaxAttempts() { return defaultMaxAttempts; }
    public void setDefaultMaxAttempts(int value) {
        defaultMaxAttempts = Math.max(1, Math.min(maxAttempts, value));
    }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) {
        maxAttempts = Math.max(1, Math.min(10, value));
        defaultMaxAttempts = Math.min(defaultMaxAttempts, maxAttempts);
    }
    public int getMaxDocumentsPerBatch() { return maxDocumentsPerBatch; }
    public void setMaxDocumentsPerBatch(int value) {
        maxDocumentsPerBatch = Math.max(1, Math.min(1000, value));
    }
    public int getRetryBackoffSeconds() { return retryBackoffSeconds; }
    public void setRetryBackoffSeconds(int value) {
        retryBackoffSeconds = Math.max(1, Math.min(3600, value));
    }
}
