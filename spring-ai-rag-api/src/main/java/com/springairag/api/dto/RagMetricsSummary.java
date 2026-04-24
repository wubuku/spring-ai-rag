package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Objects;

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RagMetricsSummary that = (RagMetricsSummary) o;
        return totalRequests == that.totalRequests
                && successfulRequests == that.successfulRequests
                && failedRequests == that.failedRequests
                && Double.compare(that.successRate, successRate) == 0
                && totalRetrievalResults == that.totalRetrievalResults
                && totalLlmTokens == that.totalLlmTokens
                && Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(totalRequests, successfulRequests, failedRequests,
                successRate, totalRetrievalResults, totalLlmTokens, timestamp);
    }

    @Override
    public String toString() {
        return "RagMetricsSummary{totalRequests=" + totalRequests
                + ", successfulRequests=" + successfulRequests
                + ", failedRequests=" + failedRequests
                + ", successRate=" + successRate
                + ", totalRetrievalResults=" + totalRetrievalResults
                + ", totalLlmTokens=" + totalLlmTokens
                + ", timestamp=" + timestamp + "}";
    }
}
