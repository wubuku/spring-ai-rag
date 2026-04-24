package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

@Schema(description = "Batch document deletion summary")
public record BatchDeleteSummary(
        @Schema(description = "Total documents requested", example = "10")
        int total,

        @Schema(description = "Successfully deleted count", example = "8")
        int deleted,

        @Schema(description = "Not found count", example = "2")
        int notFound
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchDeleteSummary that = (BatchDeleteSummary) o;
        return total == that.total && deleted == that.deleted && notFound == that.notFound;
    }

    @Override
    public int hashCode() {
        return Objects.hash(total, deleted, notFound);
    }

    @Override
    public String toString() {
        return "BatchDeleteSummary{total=" + total + ", deleted=" + deleted + ", notFound=" + notFound + "}";
    }
}
