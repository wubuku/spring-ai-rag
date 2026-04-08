package com.springairag.core.config;

/**
 * Embedding Model Configuration
 */
public class RagEmbeddingProperties {

    private String apiKey = "";
    private String baseUrl = "https://api.siliconflow.cn";
    private String model = "BAAI/bge-m3";
    private int dimensions = 1024;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }
}
