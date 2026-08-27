package com.springairag.api.dto;

import com.springairag.api.enums.IntegrationObservabilityBucket;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 外部业务接入数据面的有限时间聚合观测结果。
 */
@Schema(description = "Bounded aggregate observability for external integration operations")
public record IntegrationObservabilityResponse(
        Scope scope,
        Completeness completeness,
        Totals totals,
        List<StatusBreakdown> byStatus,
        List<OperationBreakdown> byOperation,
        List<CollectionContribution> collectionContributions,
        List<TimelineBucket> timeline) {

    public IntegrationObservabilityResponse {
        byStatus = byStatus == null ? List.of() : List.copyOf(byStatus);
        byOperation = byOperation == null ? List.of() : List.copyOf(byOperation);
        collectionContributions = collectionContributions == null
                ? List.of() : List.copyOf(collectionContributions);
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
    }

    public record Scope(
            Instant from,
            Instant to,
            IntegrationObservabilityBucket bucket,
            String principalId,
            String collectionKey,
            String operation) {
    }

    public record Completeness(
            String mode,
            boolean recordingEnabled,
            int retentionDays,
            long currentInstanceDropped,
            Instant oldestIncludedBucket) {
    }

    public record Totals(
            long requestCount,
            BigDecimal durationAverageMs,
            long durationMaxMs,
            long estimatedP50UpperBoundMs,
            long estimatedP95UpperBoundMs,
            boolean estimated) {
    }

    public record StatusBreakdown(
            int httpStatus,
            String statusClass,
            Totals totals) {
    }

    public record OperationBreakdown(
            String operation,
            Totals totals) {
    }

    public record CollectionContribution(
            String collectionKey,
            Totals totals) {
    }

    public record TimelineBucket(
            String bucketStart,
            Totals totals) {
    }
}
