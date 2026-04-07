package com.springairag.api.dto;

import com.springairag.api.dto.RetrievalResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Direct search response (GET /search)
 */
@Schema(description = "Direct search response")
public record SearchResponse(
        @Schema(description = "Retrieval result list")
        List<RetrievalResult> results,

        @Schema(description = "Total result count", example = "10")
        int total,

        @Schema(description = "Search query", example = "What is Spring AI")
        String query
) {
    public static SearchResponse of(List<RetrievalResult> results, String query) {
        return new SearchResponse(results, results.size(), query);
    }
}
