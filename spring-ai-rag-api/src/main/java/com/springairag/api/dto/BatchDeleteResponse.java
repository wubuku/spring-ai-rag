package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

@Schema(description = "Batch document deletion response")
public record BatchDeleteResponse(
        @Schema(description = "Individual deletion results")
        List<BatchDeleteItem> results,

        @Schema(description = "Batch operation summary")
        BatchDeleteSummary summary
) {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof BatchDeleteResponse that
                && Objects.equals(this.results, that.results)
                && Objects.equals(this.summary, that.summary);
    }

    @Override
    public int hashCode() {
        return Objects.hash(results, summary);
    }

    @Override
    public String toString() {
        return "BatchDeleteResponse{results=[" + (results == null ? 0 : results.size()) + " items], summary=" + summary + "}";
    }
}
