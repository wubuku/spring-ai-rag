package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

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
}
