package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Single item result in batch deletion")
public record BatchDeleteItem(
        @Schema(description = "Document ID", example = "123")
        Long id,

        @Schema(description = "Deletion status: DELETED or NOT_FOUND", example = "DELETED")
        String status
) {
}
