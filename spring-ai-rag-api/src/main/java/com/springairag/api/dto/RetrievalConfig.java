package com.springairag.api.dto;

/**
 * 检索配置
 */
public class RetrievalConfig {

    private int maxResults = 10;
    private double minScore = 0.5;
    private boolean useHybridSearch = true;
    private boolean useRerank = true;
    private double vectorWeight = 0.5;
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
