package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Retrieval configuration
 */
@Schema(description = "Retrieval configuration parameters")
public class RetrievalConfig {

    @Min(value = 1, message = "Max results must be at least 1")
    @Max(value = 100, message = "Max results must not exceed 100")
    @Schema(description = "Maximum number of results", example = "10", defaultValue = "10")
    private int maxResults = 10;

    @DecimalMin(value = "0.0", message = "Min score must be at least 0.0")
    @DecimalMax(value = "1.0", message = "Min score must not exceed 1.0")
    @Schema(description = "Minimum relevance score threshold", example = "0.5", defaultValue = "0.5")
    private double minScore = 0.5;

    @Schema(description = "Whether to use hybrid search", example = "true", defaultValue = "true")
    private boolean useHybridSearch = true;

    @Schema(description = "Whether to use reranking", example = "true", defaultValue = "true")
    private boolean useRerank = true;

    @DecimalMin(value = "0.0", message = "Vector weight must be at least 0.0")
    @DecimalMax(value = "1.0", message = "Vector weight must not exceed 1.0")
    @Schema(description = "Vector search weight", example = "0.5", defaultValue = "0.5")
    private double vectorWeight = 0.5;

    @DecimalMin(value = "0.0", message = "Fulltext weight must be at least 0.0")
    @DecimalMax(value = "1.0", message = "Fulltext weight must not exceed 1.0")
    @Schema(description = "Fulltext search weight", example = "0.5", defaultValue = "0.5")
    private double fulltextWeight = 0.5;

    public RetrievalConfig() {}

    public static RetrievalConfigBuilder builder() { return new RetrievalConfigBuilder(); }

    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) { this.maxResults = maxResults; }

    public double getMinScore() { return minScore; }
    public void setMinScore(double minScore) { this.minScore = minScore; }

    public boolean isUseHybridSearch() { return useHybridSearch; }
    public void setUseHybridSearch(boolean useHybridSearch) { this.useHybridSearch = useHybridSearch; }

    public boolean isUseRerank() { return useRerank; }
    public void setUseRerank(boolean useRerank) { this.useRerank = useRerank; }

    public double getVectorWeight() { return vectorWeight; }
    public void setVectorWeight(double vectorWeight) { this.vectorWeight = vectorWeight; }

    public double getFulltextWeight() { return fulltextWeight; }
    public void setFulltextWeight(double fulltextWeight) { this.fulltextWeight = fulltextWeight; }

    public static class RetrievalConfigBuilder {
        private final RetrievalConfig config = new RetrievalConfig();

        public RetrievalConfigBuilder maxResults(int maxResults) { config.setMaxResults(maxResults); return this; }
        public RetrievalConfigBuilder minScore(double minScore) { config.setMinScore(minScore); return this; }
        public RetrievalConfigBuilder useHybridSearch(boolean v) { config.setUseHybridSearch(v); return this; }
        public RetrievalConfigBuilder useRerank(boolean v) { config.setUseRerank(v); return this; }
        public RetrievalConfigBuilder vectorWeight(double v) { config.setVectorWeight(v); return this; }
        public RetrievalConfigBuilder fulltextWeight(double v) { config.setFulltextWeight(v); return this; }
        public RetrievalConfig build() { return config; }
    }
}
