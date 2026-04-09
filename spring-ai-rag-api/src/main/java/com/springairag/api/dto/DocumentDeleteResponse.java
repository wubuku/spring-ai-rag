package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Single document deletion response")
public record DocumentDeleteResponse(
        @Schema(description = "Success message", example = "Document deleted")
        String message,

        @Schema(description = "Deleted document ID", example = "123")
        Long id,

        @Schema(description = "Number of embedding vectors removed", example = "5")
        Long embeddingsRemoved
) {
}
