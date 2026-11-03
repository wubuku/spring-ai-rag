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

    /**
     * 启动期校验线程池参数，避免 JDK 抛出不含配置项名的参数异常。
     */
    public void validate() {
        if (corePoolSize < 1) {
            throw new IllegalStateException(
                    "rag.async.core-pool-size must be >= 1 but was " + corePoolSize);
        }
        if (maxPoolSize < corePoolSize) {
            throw new IllegalStateException(
                    "rag.async.max-pool-size must be >= rag.async.core-pool-size (core="
                            + corePoolSize + ", max=" + maxPoolSize + ")");
        }
        if (queueCapacity < 1) {
            throw new IllegalStateException(
                    "rag.async.queue-capacity must be >= 1 but was " + queueCapacity);
        }
        if (retrievalTimeoutSeconds < 1 || retrievalTimeoutSeconds > 300) {
            throw new IllegalStateException(
                    "rag.async.retrieval-timeout-seconds must be between 1 and 300 but was "
                            + retrievalTimeoutSeconds);
        }
    }
}
