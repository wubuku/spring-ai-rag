package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

@Schema(description = "Single document deletion response")
public record DocumentDeleteResponse(
        @Schema(description = "Success message", example = "Document deleted")
        String message,

        @Schema(description = "Deleted document ID", example = "123")
        Long id,

        @Schema(description = "Number of embedding vectors removed", example = "5")
        Long embeddingsRemoved
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof DocumentDeleteResponse that
                && Objects.equals(this.message, that.message)
                && Objects.equals(this.id, that.id)
                && Objects.equals(this.embeddingsRemoved, that.embeddingsRemoved);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, id, embeddingsRemoved);
    }

    @Override
    public String toString() {
        return "DocumentDeleteResponse{message=" + message + ", id=" + id
                + ", embeddingsRemoved=" + embeddingsRemoved + "}";
    }
}
