package com.springairag.core.config;

/**
 * Document Chunking Configuration
 */
public class RagChunkProperties {

    private int defaultChunkSize = 1000;
    private int defaultChunkOverlap = 100;
    private int minChunkSize = 100;

    public int getDefaultChunkSize() {
        return defaultChunkSize;
    }

    public void setDefaultChunkSize(int defaultChunkSize) {
        this.defaultChunkSize = defaultChunkSize;
    }

    public int getDefaultChunkOverlap() {
        return defaultChunkOverlap;
    }

    public void setDefaultChunkOverlap(int defaultChunkOverlap) {
        this.defaultChunkOverlap = defaultChunkOverlap;
    }

    public int getMinChunkSize() {
        return minChunkSize;
    }

    public void setMinChunkSize(int minChunkSize) {
        this.minChunkSize = minChunkSize;
    }
}
