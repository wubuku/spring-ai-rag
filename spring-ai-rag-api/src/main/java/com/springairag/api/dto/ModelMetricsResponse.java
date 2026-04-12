package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Model metrics response
 *
 * @param multiModelEnabled Whether multi-model is enabled
 * @param models Metrics data for each model
 */
@Schema(description = "Per-model call metrics (call count, errors, latency)")
public record ModelMetricsResponse(
        @Schema(description = "Whether multi-model support is enabled", example = "true") boolean multiModelEnabled,
        @Schema(description = "Metrics for each model") List<ModelMetric> models
) {
    /**
     * Single model metrics
     *
     * @param provider Provider name
     * @param calls Number of calls
     * @param errors Number of errors
     * @param errorRate Error rate
     * @param displayName Display name
     */
    @Schema(description = "Metrics for a single model/provider")
    public record ModelMetric(
            @Schema(description = "Provider name", example = "openai") String provider,
            @Schema(description = "Total number of calls", example = "1523") long calls,
            @Schema(description = "Number of failed calls", example = "12") long errors,
            @Schema(description = "Error rate as a fraction", example = "0.0079") double errorRate,
            @Schema(description = "Human-readable display name", example = "OpenAI GPT-4o") String displayName
    ) {}
}
