package com.springairag.core.config;

/** 单个 OpenAI-compatible Embedding Profile 的运行时配置。 */
public class RagEmbeddingProfileProperties {

    private boolean enabled = true;
    private String provider;
    private String model;
    private String modelRevision = "unspecified";
    private int dimensions;
    private boolean requestDimensions;
    private String distanceMetric = "COSINE";
    private String normalization = "PROVIDER_DEFAULT";
    private String baseUrl;
    private String apiKey = "";
    private int retryMaxAttempts = 3;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getModelRevision() { return modelRevision; }
    public void setModelRevision(String modelRevision) { this.modelRevision = modelRevision; }
    public int getDimensions() { return dimensions; }
    public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    public boolean isRequestDimensions() { return requestDimensions; }
    public void setRequestDimensions(boolean requestDimensions) { this.requestDimensions = requestDimensions; }
    public String getDistanceMetric() { return distanceMetric; }
    public void setDistanceMetric(String distanceMetric) { this.distanceMetric = distanceMetric; }
    public String getNormalization() { return normalization; }
    public void setNormalization(String normalization) { this.normalization = normalization; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public int getRetryMaxAttempts() { return retryMaxAttempts; }
    public void setRetryMaxAttempts(int retryMaxAttempts) { this.retryMaxAttempts = retryMaxAttempts; }
}
