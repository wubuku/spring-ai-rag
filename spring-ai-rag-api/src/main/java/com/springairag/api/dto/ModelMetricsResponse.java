package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

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
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModelMetricsResponse that = (ModelMetricsResponse) o;
        return multiModelEnabled == that.multiModelEnabled &&
                Objects.equals(models, that.models);
    }

    @Override
    public int hashCode() {
        return Objects.hash(multiModelEnabled, models);
    }

    @Override
    public String toString() {
        return "ModelMetricsResponse{" +
                "multiModelEnabled=" + multiModelEnabled +
                ", models=" + (models != null ? models.size() + " model(s)" : "null") +
                '}';
    }

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
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ModelMetric that = (ModelMetric) o;
            return calls == that.calls &&
                    errors == that.errors &&
                    Double.compare(that.errorRate, errorRate) == 0 &&
                    Objects.equals(provider, that.provider) &&
                    Objects.equals(displayName, that.displayName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(provider, calls, errors, errorRate, displayName);
        }

        @Override
        public String toString() {
            return "ModelMetricsResponse.ModelMetric{" +
                    "provider='" + provider + '\'' +
                    ", calls=" + calls +
                    ", errors=" + errors +
                    ", errorRate=" + errorRate +
                    ", displayName='" + displayName + '\'' +
                    '}';
        }
    }
}
