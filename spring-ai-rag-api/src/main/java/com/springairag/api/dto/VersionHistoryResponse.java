package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Document version history paginated response.
 */
@Schema(description = "Document version history response")
public record VersionHistoryResponse(
        @Schema(description = "Parent document ID", example = "42")
        Long documentId,

        @Schema(description = "Total number of versions", example = "7")
        long totalVersions,

        @Schema(description = "Current page number", example = "0")
        int page,

        @Schema(description = "Page size", example = "20")
        int size,

        @Schema(description = "Version records for this page")
        List<DocumentVersionResponse> versions
) {
}
