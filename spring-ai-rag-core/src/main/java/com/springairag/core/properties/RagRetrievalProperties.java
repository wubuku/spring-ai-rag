package com.springairag.core.properties;

import java.util.List;
import java.util.Map;

/**
 * Retrieval Configuration
 */
public class RagRetrievalProperties {

    /**
     * pgvector 0.8+ 迭代扫描模式（白名单，杜绝将外部输入拼入 SET LOCAL）。
     */
    public enum HnswIterativeScan {
        OFF("off"),
        RELAXED_ORDER("relaxed_order"),
        STRICT_ORDER("strict_order");

        private final String gucValue;

        HnswIterativeScan(String gucValue) {
            this.gucValue = gucValue;
        }

        /** 返回可安全拼入 SET LOCAL 的固定字面量。 */
        public String gucValue() {
            return gucValue;
        }

        /** 解析环境变量/文本配置；大小写与连字符/下划线不敏感，未知值回退到默认。 */
        public static HnswIterativeScan parse(String value) {
            if (value == null || value.isBlank()) {
                return RELAXED_ORDER;
            }
            String normalized = value.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
            for (HnswIterativeScan mode : values()) {
                if (mode.name().equals(normalized)) {
                    return mode;
                }
            }
            return RELAXED_ORDER;
        }
    }

    private float vectorWeight = 0.5f;
    private float fulltextWeight = 0.5f;
    private int defaultLimit = 10;
    private float minScore = 0.3f;
    /** Whether to enable full-text search (falls back to pure vector search if unavailable) */
    private boolean fulltextEnabled = true;
    /** Full-text search strategy: auto (auto-detect) / pg_jieba / pg_trgm / none */
    private String fulltextStrategy = "auto";
    /** K value for precision@k and recall@k metrics (default: 10) */
    private int evaluationK = 10;
    /** Timeout in seconds for LLM-based answer quality evaluation (default: 30) */
    private int answerQualityTimeoutSeconds = 30;
    private int maxEmbeddingProfileFanout = 4;
    /** HNSW 迭代扫描模式；默认 relaxed_order，OFF 可一键降级。 */
    private HnswIterativeScan hnswIterativeScan =
            HnswIterativeScan.parse(System.getenv("RAG_RETRIEVAL_HNSW_ITERATIVE_SCAN"));
    /** HNSW 迭代扫描允许检查的最大 tuple 数；必须为正整数。 */
    private int hnswMaxScanTuples = 20_000;

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

    public int getEvaluationK() {
        return evaluationK;
    }

    public void setEvaluationK(int evaluationK) {
        this.evaluationK = evaluationK;
    }

    public int getAnswerQualityTimeoutSeconds() {
        return answerQualityTimeoutSeconds;
    }

    public void setAnswerQualityTimeoutSeconds(int answerQualityTimeoutSeconds) {
        this.answerQualityTimeoutSeconds = answerQualityTimeoutSeconds;
    }

    public int getMaxEmbeddingProfileFanout() {
        return maxEmbeddingProfileFanout;
    }

    public void setMaxEmbeddingProfileFanout(int maxEmbeddingProfileFanout) {
        if (maxEmbeddingProfileFanout < 1 || maxEmbeddingProfileFanout > 8) {
            throw new IllegalArgumentException(
                    "rag.retrieval.max-embedding-profile-fanout must be between 1 and 8");
        }
        this.maxEmbeddingProfileFanout = maxEmbeddingProfileFanout;
    }

    public HnswIterativeScan getHnswIterativeScan() {
        return hnswIterativeScan;
    }

    public void setHnswIterativeScan(HnswIterativeScan hnswIterativeScan) {
        if (hnswIterativeScan == null) {
            throw new IllegalArgumentException(
                    "rag.retrieval.hnsw-iterative-scan must be OFF/RELAXED_ORDER/STRICT_ORDER");
        }
        this.hnswIterativeScan = hnswIterativeScan;
    }

    public int getHnswMaxScanTuples() {
        return hnswMaxScanTuples;
    }

    public void setHnswMaxScanTuples(int hnswMaxScanTuples) {
        if (hnswMaxScanTuples < 1) {
            throw new IllegalArgumentException(
                    "rag.retrieval.hnsw-max-scan-tuples must be a positive integer");
        }
        this.hnswMaxScanTuples = hnswMaxScanTuples;
    }
}
