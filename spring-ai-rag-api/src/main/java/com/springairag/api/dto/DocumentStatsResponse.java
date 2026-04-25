package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.Objects;

/**
 * Document statistics response (counts by processing status).
 */
@Schema(description = "Document statistics response")
public record DocumentStatsResponse(
        @Schema(description = "Total document count", example = "42")
        long total,

        @Schema(description = "Counts grouped by processing status")
        Map<String, Long> byStatus
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof DocumentStatsResponse that
                && this.total == that.total
                && Objects.equals(this.byStatus, that.byStatus);
    }

    @Override
    public int hashCode() {
        return Objects.hash(total, byStatus);
    }

    @Override
    public String toString() {
        return "DocumentStatsResponse{total=" + total
                + ", byStatus=" + (byStatus != null ? byStatus.size() + " entries" : "null") + "}";
    }
}
