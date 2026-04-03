package com.springairag.core.config;

/**
 * 缓存配置
 */
public class RagCacheProperties {

    private long maximumSize = 2000;
    private int expireAfterWriteMinutes = 30;
    private long embeddingMaximumSize = 10000;
    private int embeddingExpireAfterWriteHours = 2;

    public long getMaximumSize() {
        return maximumSize;
    }

    public void setMaximumSize(long maximumSize) {
        this.maximumSize = maximumSize;
    }

    public int getExpireAfterWriteMinutes() {
        return expireAfterWriteMinutes;
    }

    public void setExpireAfterWriteMinutes(int expireAfterWriteMinutes) {
        this.expireAfterWriteMinutes = expireAfterWriteMinutes;
    }

    public long getEmbeddingMaximumSize() {
        return embeddingMaximumSize;
    }

    public void setEmbeddingMaximumSize(long embeddingMaximumSize) {
        this.embeddingMaximumSize = embeddingMaximumSize;
    }

    public int getEmbeddingExpireAfterWriteHours() {
        return embeddingExpireAfterWriteHours;
    }

    public void setEmbeddingExpireAfterWriteHours(int embeddingExpireAfterWriteHours) {
        this.embeddingExpireAfterWriteHours = embeddingExpireAfterWriteHours;
    }
}
