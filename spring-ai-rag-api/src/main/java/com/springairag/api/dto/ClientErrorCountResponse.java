package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * Client error count response.
 *
 * @param count Total number of recorded client-side errors
 */
@Schema(description = "Total count of recorded client-side errors")
public record ClientErrorCountResponse(
        @Schema(description = "Total number of recorded client errors", example = "42") long count
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof ClientErrorCountResponse that
                && this.count == that.count;
    }

    @Override
    public int hashCode() {
        return Objects.hash(count);
    }

    @Override
    public String toString() {
        return "ClientErrorCountResponse{count=" + count + "}";
    }
}
