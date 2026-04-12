package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Model comparison response
 *
 * @param query Query text
 * @param providers List of providers to compare
 * @param results Comparison results for each model
 */
@Schema(description = "Multi-model comparison results")
public record ModelCompareResponse(
        @Schema(description = "The original query text", example = "What is RAG?") String query,
        @Schema(description = "List of model providers that were compared", example = "[\"openai\",\"anthropic\"]") List<String> providers,
        @Schema(description = "Comparison results for each model") List<ModelCompareResult> results
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
    @Schema(description = "Single model comparison result")
    public record ModelCompareResult(
            @Schema(description = "Model/provider name", example = "openai/gpt-4o") String modelName,
            @Schema(description = "Whether the LLM call succeeded", example = "true") boolean success,
            @Schema(description = "LLM response text (on success)", example = "RAG is...") String response,
            @Schema(description = "LLM call latency in milliseconds", example = "1250") Long latencyMs,
            @Schema(description = "Number of prompt tokens", example = "150") Integer promptTokens,
            @Schema(description = "Number of completion tokens", example = "320") Integer completionTokens,
            @Schema(description = "Total tokens used", example = "470") Integer totalTokens,
            @Schema(description = "Error message (on failure)", example = "Rate limit exceeded") String error
    ) {}
}
