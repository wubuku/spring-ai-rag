package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Principal-scoped durable model-invocation usage aggregation.
 */
@Schema(description = "Durable model invocation usage aggregation")
public record LlmUsageResponse(
        @Schema(description = "Whether new usage events are currently recorded")
        boolean recordingEnabled,
        @Schema(description = "Usage events not confirmed by this instance since startup")
        long localLostEventsSinceStart,
        @Schema(description = "Effective query scope")
        Scope scope,
        @Schema(description = "Inclusive UTC start date")
        LocalDate from,
        @Schema(description = "Inclusive UTC end date")
        LocalDate to,
        @Schema(description = "Aggregate usage counters")
        Totals totals,
        @Schema(description = "Configured cost totals grouped by cost unit")
        List<CostBreakdown> costs,
        @Schema(description = "Usage grouped by model reference")
        List<ModelBreakdown> byModel,
        @Schema(description = "Usage grouped by invocation purpose")
        List<PurposeBreakdown> byPurpose,
        @Schema(description = "Usage grouped by Chat mode")
        List<ModeBreakdown> byMode,
        @Schema(description = "Usage grouped by UTC day")
        List<DayBreakdown> byDay) {

    public LlmUsageResponse {
        costs = costs == null ? List.of() : List.copyOf(costs);
        byModel = byModel == null ? List.of() : List.copyOf(byModel);
        byPurpose = byPurpose == null ? List.of() : List.copyOf(byPurpose);
        byMode = byMode == null ? List.of() : List.copyOf(byMode);
        byDay = byDay == null ? List.of() : List.copyOf(byDay);
    }

    public record Scope(
            @Schema(description = "SELF, ALL, or PRINCIPAL")
            String type,
            @Schema(description = "Stable principal ID when type is SELF or PRINCIPAL")
            String principalId) {
    }

    public record Totals(
            long logicalExecutionCount,
            long invocationCount,
            long succeededCount,
            long failedCount,
            long cancelledCount,
            BigDecimal promptTokens,
            BigDecimal completionTokens,
            BigDecimal totalTokens,
            long usageAvailableCount,
            long usageUnavailableCount,
            long pricingUnavailableCount,
            long costUnavailableCount) {
    }

    public record CostBreakdown(
            String unit,
            BigDecimal configuredCost,
            long invocationCount,
            long costAvailableCount) {
    }

    public record ModelBreakdown(
            String modelRef,
            Totals totals) {
    }

    public record PurposeBreakdown(
            String purpose,
            Totals totals) {
    }

    public record ModeBreakdown(
            String mode,
            Totals totals) {
    }

    public record DayBreakdown(
            LocalDate day,
            Totals totals) {
    }
}
