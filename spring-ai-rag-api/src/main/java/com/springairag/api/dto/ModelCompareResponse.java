package com.springairag.api.dto;

import java.util.List;

/**
 * Model comparison response
 *
 * @param query Query text
 * @param providers List of providers to compare
 * @param results Comparison results for each model
 */
public record ModelCompareResponse(
        String query,
        List<String> providers,
        List<ModelCompareResult> results
) {
    /**
     * Single model comparison result
     *
     * @param modelName Model name
     * @param success Whether the call succeeded
     * @param response Response content (populated on success)
     * @param latencyMs Latency in milliseconds
     * @param promptTokens Prompt token count
     * @param completionTokens Completion token count
     * @param totalTokens Total token count
     * @param error Error message (populated on failure)
     */
    public record ModelCompareResult(
            String modelName,
            boolean success,
            String response,
            Long latencyMs,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            String error
    ) {}
}
