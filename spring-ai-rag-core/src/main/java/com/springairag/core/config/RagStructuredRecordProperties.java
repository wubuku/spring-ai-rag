package com.springairag.core.config;

/**
 * Limits for JSON structured-record APIs.
 */
public class RagStructuredRecordProperties {

    private int maxJsonbPayloadBytes = 1_048_576;
    private int maxRetrievalTextChars = 10_000;
    private int maxBatchSize = 20;
    private int maxBatchPayloadBytes = 10_485_760;
    private int maxSearchResults = 20;

    public int getMaxJsonbPayloadBytes() {
        return maxJsonbPayloadBytes;
    }

    public void setMaxJsonbPayloadBytes(int maxJsonbPayloadBytes) {
        this.maxJsonbPayloadBytes = maxJsonbPayloadBytes;
    }

    public int getMaxRetrievalTextChars() {
        return maxRetrievalTextChars;
    }

    public void setMaxRetrievalTextChars(int maxRetrievalTextChars) {
        this.maxRetrievalTextChars = maxRetrievalTextChars;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public int getMaxBatchPayloadBytes() {
        return maxBatchPayloadBytes;
    }

    public void setMaxBatchPayloadBytes(int maxBatchPayloadBytes) {
        this.maxBatchPayloadBytes = maxBatchPayloadBytes;
    }

    public int getMaxSearchResults() {
        return maxSearchResults;
    }

    public void setMaxSearchResults(int maxSearchResults) {
        this.maxSearchResults = maxSearchResults;
    }
}
