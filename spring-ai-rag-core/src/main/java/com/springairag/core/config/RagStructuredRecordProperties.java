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
    private int maxPayloadFilterBytes = 16_384;
    private int maxPayloadFilterDepth = 8;
    private boolean agentToolEnabled = false;
    private int agentToolMaxResults = 5;
    private int agentToolMaxPayloadBytes = 32_768;

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

    public int getMaxPayloadFilterBytes() {
        return maxPayloadFilterBytes;
    }

    public void setMaxPayloadFilterBytes(int maxPayloadFilterBytes) {
        this.maxPayloadFilterBytes = Math.max(1, maxPayloadFilterBytes);
    }

    public int getMaxPayloadFilterDepth() {
        return maxPayloadFilterDepth;
    }

    public void setMaxPayloadFilterDepth(int maxPayloadFilterDepth) {
        this.maxPayloadFilterDepth = Math.max(1, maxPayloadFilterDepth);
    }

    public boolean isAgentToolEnabled() {
        return agentToolEnabled;
    }

    public void setAgentToolEnabled(boolean agentToolEnabled) {
        this.agentToolEnabled = agentToolEnabled;
    }

    public int getAgentToolMaxResults() {
        return agentToolMaxResults;
    }

    public void setAgentToolMaxResults(int agentToolMaxResults) {
        this.agentToolMaxResults = Math.max(1, agentToolMaxResults);
    }

    public int getAgentToolMaxPayloadBytes() {
        return agentToolMaxPayloadBytes;
    }

    public void setAgentToolMaxPayloadBytes(int agentToolMaxPayloadBytes) {
        this.agentToolMaxPayloadBytes = Math.max(1, agentToolMaxPayloadBytes);
    }
}
