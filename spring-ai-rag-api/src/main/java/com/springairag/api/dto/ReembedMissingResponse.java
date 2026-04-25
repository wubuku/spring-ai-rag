package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

@Schema(description = "Response for batch re-embedding documents lacking embedding vectors")
public record ReembedMissingResponse(
        @Schema(description = "Total documents processed") int total,
        @Schema(description = "Number successfully re-embedded") int success,
        @Schema(description = "Number that failed") int failed,
        @Schema(description = "Individual re-embed results") List<ReembedResultResponse> results
) {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof ReembedMissingResponse that
                && this.total == that.total
                && this.success == that.success
                && this.failed == that.failed
                && Objects.equals(this.results, that.results);
    }

    @Override
    public int hashCode() {
        return Objects.hash(total, success, failed, results);
    }

    @Override
    public String toString() {
        return "ReembedMissingResponse{total=" + total + ", success=" + success + ", failed=" + failed
                + ", results count=" + (results == null ? 0 : results.size()) + "}";
    }
}
