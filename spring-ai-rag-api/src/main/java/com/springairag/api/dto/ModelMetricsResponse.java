package com.springairag.api.dto;

import java.util.List;

/**
 * Model metrics response
 *
 * @param multiModelEnabled Whether multi-model is enabled
 * @param models Metrics data for each model
 */
public record ModelMetricsResponse(
        boolean multiModelEnabled,
        List<ModelMetric> models
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
    public record ModelMetric(
            String provider,
            long calls,
            long errors,
            double errorRate,
            String displayName
    ) {}
}
