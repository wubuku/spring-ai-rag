package com.springairag.api.dto;

import com.springairag.api.dto.RetrievalResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 直接检索响应（GET /search）
 */
@Schema(description = "直接检索响应")
public record SearchResponse(
        @Schema(description = "检索结果列表")
        List<RetrievalResult> results,

        @Schema(description = "结果总数", example = "10")
        int total,

        @Schema(description = "检索query", example = "什么是Spring AI")
        String query
) {
    public static SearchResponse of(List<RetrievalResult> results, String query) {
        return new SearchResponse(results, results.size(), query);
    }
}
