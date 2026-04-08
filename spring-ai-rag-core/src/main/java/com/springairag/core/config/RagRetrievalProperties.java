package com.springairag.core.config;

import java.util.List;
import java.util.Map;

/**
 * Retrieval Configuration
 */
public class RagRetrievalProperties {

    private float vectorWeight = 0.5f;
    private float fulltextWeight = 0.5f;
    private int defaultLimit = 10;
    private float minScore = 0.3f;
    /** Whether to enable full-text search (falls back to pure vector search if unavailable) */
    private boolean fulltextEnabled = true;
    /** Full-text search strategy: auto (auto-detect) / pg_jieba / pg_trgm / none */
    private String fulltextStrategy = "auto";

    public float getVectorWeight() {
        return vectorWeight;
    }

    public void setVectorWeight(float vectorWeight) {
        this.vectorWeight = vectorWeight;
    }

    public float getFulltextWeight() {
        return fulltextWeight;
    }

    public void setFulltextWeight(float fulltextWeight) {
        this.fulltextWeight = fulltextWeight;
    }

    public boolean isFulltextEnabled() {
        return fulltextEnabled;
    }

    public void setFulltextEnabled(boolean fulltextEnabled) {
        this.fulltextEnabled = fulltextEnabled;
    }

    public String getFulltextStrategy() {
        return fulltextStrategy;
    }

    public void setFulltextStrategy(String fulltextStrategy) {
        this.fulltextStrategy = fulltextStrategy;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public float getMinScore() {
        return minScore;
    }

    public void setMinScore(float minScore) {
        this.minScore = minScore;
    }
}
