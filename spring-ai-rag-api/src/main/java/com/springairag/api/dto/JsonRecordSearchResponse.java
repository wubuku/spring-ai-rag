package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * JSON structured-record search response.
 */
@Schema(description = "JSON structured-record search response")
public record JsonRecordSearchResponse(
        String query,
        List<JsonRecordSearchResult> results
) {
}
