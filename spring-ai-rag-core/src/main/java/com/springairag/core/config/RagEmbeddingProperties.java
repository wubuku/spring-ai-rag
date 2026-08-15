package com.springairag.core.config;

/**
 * Embedding Model Configuration
 */
public class RagEmbeddingProperties {

    private String apiKey = "";
    private String baseUrl = "https://api.siliconflow.cn";
    private String model = "BAAI/bge-m3";
    private int dimensions = 1024;
    private String profileKey = EmbeddingProfileRegistry.DEFAULT_PROFILE_KEY;
    private String provider = "siliconflow";
    private String modelRevision = "unspecified";
    private String distanceMetric = "COSINE";
    private String normalization = "PROVIDER_DEFAULT";
    private String migrationMode = "none";
    private String migrationLegacyProfileKey = "";
    private String migrationConfirm = "";

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

    public String getProfileKey() {
        return profileKey;
    }

    public void setProfileKey(String profileKey) {
        this.profileKey = profileKey;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModelRevision() {
        return modelRevision;
    }

    public void setModelRevision(String modelRevision) {
        this.modelRevision = modelRevision;
    }

    public String getDistanceMetric() {
        return distanceMetric;
    }

    public void setDistanceMetric(String distanceMetric) {
        this.distanceMetric = distanceMetric;
    }

    public String getNormalization() {
        return normalization;
    }

    public void setNormalization(String normalization) {
        this.normalization = normalization;
    }

    public String getMigrationMode() {
        return migrationMode;
    }

    public void setMigrationMode(String migrationMode) {
        this.migrationMode = migrationMode;
    }

    public String getMigrationLegacyProfileKey() {
        return migrationLegacyProfileKey;
    }

    public void setMigrationLegacyProfileKey(String migrationLegacyProfileKey) {
        this.migrationLegacyProfileKey = migrationLegacyProfileKey;
    }

    public String getMigrationConfirm() {
        return migrationConfirm;
    }

    public void setMigrationConfirm(String migrationConfirm) {
        this.migrationConfirm = migrationConfirm;
    }
}
