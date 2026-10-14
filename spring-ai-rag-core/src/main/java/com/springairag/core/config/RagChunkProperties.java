package com.springairag.core.config;

/**
 * Document Chunking Configuration
 */
public class RagChunkProperties {

    public enum Profile {
        LEGACY_V2, BOUNDED_V3
    }

    private Profile profile = Profile.LEGACY_V2;
    private int maxInputChars = 1000000;
    private int maxChunks = 20000;
    private int previewMaxChars = 100000;
    private int defaultChunkSize = 1000;
    private int defaultChunkOverlap = 100;
    private int minChunkSize = 100;

    public Profile getProfile() { return profile; }
    public void setProfile(Profile profile) { this.profile = profile; }
    public int getMaxInputChars() { return maxInputChars; }
    public void setMaxInputChars(int maxInputChars) { this.maxInputChars = maxInputChars; }
    public int getMaxChunks() { return maxChunks; }
    public void setMaxChunks(int maxChunks) { this.maxChunks = maxChunks; }
    public int getPreviewMaxChars() { return previewMaxChars; }
    public void setPreviewMaxChars(int previewMaxChars) { this.previewMaxChars = previewMaxChars; }

    public void validate() {
        if (profile == null || previewMaxChars <= 0) {
            throw new IllegalArgumentException("Invalid chunking profile or preview limit");
        }
        if (profile == Profile.BOUNDED_V3 && (defaultChunkSize < 32
                || minChunkSize < 0 || minChunkSize > defaultChunkSize
                || defaultChunkOverlap < 0 || defaultChunkOverlap >= defaultChunkSize
                || maxInputChars <= 0 || maxChunks <= 0)) {
            throw new IllegalArgumentException("Invalid bounded chunking budgets");
        }
    }

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
