package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Collection list response
 */
@Schema(description = "Collection list response")
public record CollectionListResponse(
        @Schema(description = "Collection list")
        List<CollectionResponse> collections,

        @Schema(description = "Total count", example = "25")
        long total,

        @Schema(description = "Page number", example = "0")
        int page,

        @Schema(description = "Page size", example = "10")
        int pageSize
) {
}
