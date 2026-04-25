package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

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
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EndpointSlo that = (EndpointSlo) o;
            return Double.compare(that.compliancePercent, compliancePercent) == 0 &&
                    requestCount == that.requestCount &&
                    sloCount == that.sloCount &&
                    breachCount == that.breachCount &&
                    Objects.equals(endpoint, that.endpoint) &&
                    Objects.equals(method, that.method) &&
                    thresholdMs == that.thresholdMs &&
                    Objects.equals(stats, that.stats);
        }

        @Override
        public int hashCode() {
            return Objects.hash(endpoint, method, thresholdMs, compliancePercent,
                    requestCount, sloCount, breachCount, stats);
        }

        @Override
        public String toString() {
            return "EndpointSlo{" +
                    "endpoint='" + endpoint + '\'' +
                    ", method='" + method + '\'' +
                    ", thresholdMs=" + thresholdMs +
                    ", compliancePercent=" + compliancePercent +
                    ", requestCount=" + requestCount +
                    ", sloCount=" + sloCount +
                    ", breachCount=" + breachCount +
                    ", stats=" + stats +
                    '}';
        }
    }

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
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LatencyStats that = (LatencyStats) o;
            return Double.compare(that.p50Ms, p50Ms) == 0 &&
                    Double.compare(that.p95Ms, p95Ms) == 0 &&
                    Double.compare(that.p99Ms, p99Ms) == 0 &&
                    Double.compare(that.minMs, minMs) == 0 &&
                    Double.compare(that.maxMs, maxMs) == 0 &&
                    Double.compare(that.avgMs, avgMs) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(p50Ms, p95Ms, p99Ms, minMs, maxMs, avgMs);
        }

        @Override
        public String toString() {
            return "LatencyStats{" +
                    "p50Ms=" + p50Ms +
                    ", p95Ms=" + p95Ms +
                    ", p99Ms=" + p99Ms +
                    ", minMs=" + minMs +
                    ", maxMs=" + maxMs +
                    ", avgMs=" + avgMs +
                    '}';
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiSloComplianceResponse that = (ApiSloComplianceResponse) o;
        return enabled == that.enabled &&
                windowSeconds == that.windowSeconds &&
                Objects.equals(endpoints, that.endpoints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, windowSeconds, endpoints);
    }

    @Override
    public String toString() {
        return "ApiSloComplianceResponse{" +
                "enabled=" + enabled +
                ", windowSeconds=" + windowSeconds +
                ", endpointsCount=" + (endpoints != null ? endpoints.size() : 0) +
                '}';
    }
}
