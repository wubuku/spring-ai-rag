package com.springairag.api.dto;

import com.springairag.api.dto.RetrievalResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SearchResponse that = (SearchResponse) o;
        return total == that.total
                && Objects.equals(results, that.results)
                && Objects.equals(query, that.query);
    }

    @Override
    public int hashCode() {
        return Objects.hash(results, total, query);
    }

    @Override
    public String toString() {
        return "SearchResponse{" +
                "results=" + (results != null ? results.size() + " result(s)" : "null") +
                ", total=" + total +
                ", query='" + query + '\'' +
                '}';
    }
}
