package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * RAG service metrics summary DTO
 *
 * <p>Provides a concise JSON view of key metrics, instead of directly querying Micrometer/Actuator.
 */
@Schema(description = "RAG service metrics summary")
public record RagMetricsSummary(
        @Schema(description = "Total number of requests", example = "1523")
        long totalRequests,

        @Schema(description = "Number of successful requests", example = "1489")
        long successfulRequests,

        @Schema(description = "Number of failed requests", example = "34")
        long failedRequests,

        @Schema(description = "Success rate (percentage)", example = "97.77")
        double successRate,

        @Schema(description = "Total number of retrieval results", example = "28450")
        long totalRetrievalResults,

        @Schema(description = "Total LLM token consumption", example = "892450")
        long totalLlmTokens,

        @Schema(description = "Metrics snapshot timestamp")
        Instant timestamp
) {
    public static RagMetricsSummary of(long total, long success, long failed,
            double rate, long retrievalResults, long tokens) {
        return new RagMetricsSummary(total, success, failed, rate,
                retrievalResults, tokens, Instant.now());
    }
}
