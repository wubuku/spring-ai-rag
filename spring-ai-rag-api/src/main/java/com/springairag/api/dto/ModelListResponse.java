package com.springairag.api.dto;

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
public record ModelListResponse(
        boolean multiModelEnabled,
        String defaultProvider,
        List<String> availableProviders,
        List<String> fallbackChain,
        List<Map<String, Object>> models
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
