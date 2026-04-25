package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Model list response
 *
 * @param multiModelEnabled Whether multi-model is enabled
 * @param defaultProvider Default provider
 * @param availableProviders List of available providers
 * @param fallbackChain Fallback chain
 * @param models Detailed information for each model
 */
@Schema(description = "List of available models and their configuration")
public record ModelListResponse(
        @Schema(description = "Whether multi-model support is enabled", example = "true") boolean multiModelEnabled,
        @Schema(description = "Default model provider", example = "openai") String defaultProvider,
        @Schema(description = "List of available provider names", example = "[\"openai\",\"anthropic\"]") List<String> availableProviders,
        @Schema(description = "Fallback chain in priority order", example = "[\"openai\",\"anthropic\"]") List<String> fallbackChain,
        @Schema(description = "Detailed information for each available model") List<Map<String, Object>> models
) {
    public static ModelListResponse of(
            boolean multiModelEnabled,
            String defaultProvider,
            List<String> availableProviders,
            List<String> fallbackChain,
            List<Map<String, Object>> models) {
        return new ModelListResponse(multiModelEnabled, defaultProvider,
                availableProviders, fallbackChain, models);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModelListResponse that = (ModelListResponse) o;
        return multiModelEnabled == that.multiModelEnabled &&
                Objects.equals(defaultProvider, that.defaultProvider) &&
                Objects.equals(availableProviders, that.availableProviders) &&
                Objects.equals(fallbackChain, that.fallbackChain) &&
                Objects.equals(models, that.models);
    }

    @Override
    public int hashCode() {
        return Objects.hash(multiModelEnabled, defaultProvider, availableProviders, fallbackChain, models);
    }

    @Override
    public String toString() {
        return "ModelListResponse{" +
                "multiModelEnabled=" + multiModelEnabled +
                ", defaultProvider='" + defaultProvider + '\'' +
                ", availableProviders=" + availableProviders +
                ", fallbackChain=" + fallbackChain +
                ", models=" + (models != null ? models.size() + " model(s)" : "null") +
                '}';
    }
}
