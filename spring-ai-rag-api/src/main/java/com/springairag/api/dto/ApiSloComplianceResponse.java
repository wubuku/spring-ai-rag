package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * API SLO Compliance Response.
 *
 * @author Spring AIRAG Team
 * @since 1.0
 */
@Schema(description = "API SLO compliance metrics per endpoint")
public record ApiSloComplianceResponse(
        @Schema(description = "Whether SLO tracking is enabled")
        boolean enabled,

        @Schema(description = "Time window in seconds used for compliance calculation")
        int windowSeconds,

        @Schema(description = "Per-endpoint SLO compliance metrics")
        List<EndpointSlo> endpoints
) {
    @Schema(description = "SLO compliance for a single endpoint")
    public record EndpointSlo(
            @Schema(description = "Endpoint identifier (e.g., rag.search.post)")
            String endpoint,

            @Schema(description = "HTTP method")
            String method,

            @Schema(description = "SLO threshold in milliseconds")
            long thresholdMs,

            @Schema(description = "Compliance percentage (0-100) within the time window")
            double compliancePercent,

            @Schema(description = "Total number of requests within the time window")
            int requestCount,

            @Schema(description = "Number of requests meeting SLO")
            int sloCount,

            @Schema(description = "Number of requests breaching SLO")
            int breachCount,

            @Schema(description = "Latency statistics within the time window")
            LatencyStats stats
    ) {}

    @Schema(description = "Latency statistics from recent requests in the time window")
    public record LatencyStats(
            @Schema(description = "50th percentile latency in milliseconds")
            double p50Ms,

            @Schema(description = "95th percentile latency in milliseconds")
            double p95Ms,

            @Schema(description = "99th percentile latency in milliseconds")
            double p99Ms,

            @Schema(description = "Minimum latency in milliseconds")
            double minMs,

            @Schema(description = "Maximum latency in milliseconds")
            double maxMs,

            @Schema(description = "Average latency in milliseconds")
            double avgMs
    ) {}
}
