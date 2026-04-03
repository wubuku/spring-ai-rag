package com.springairag.core.config;

/**
 * 异步线程池配置
 */
public class RagAsyncProperties {

    private int corePoolSize = 4;
    private int maxPoolSize = 16;
    private int queueCapacity = 100;
    /** 并行检索超时秒数（向量/全文并行执行时） */
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
