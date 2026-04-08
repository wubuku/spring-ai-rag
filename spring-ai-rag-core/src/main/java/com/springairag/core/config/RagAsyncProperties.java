package com.springairag.core.config;

/**
 * Async Thread Pool Configuration
 */
public class RagAsyncProperties {

    private int corePoolSize = 4;
    private int maxPoolSize = 16;
    private int queueCapacity = 100;
    /** Parallel retrieval timeout in seconds (for vector/full-text parallel execution) */
    private int retrievalTimeoutSeconds = 5;

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getRetrievalTimeoutSeconds() {
        return retrievalTimeoutSeconds;
    }

    public void setRetrievalTimeoutSeconds(int retrievalTimeoutSeconds) {
        this.retrievalTimeoutSeconds = retrievalTimeoutSeconds;
    }
}
