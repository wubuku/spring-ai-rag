package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * RAG 服务指标汇总 DTO
 *
 * <p>提供关键指标的简洁 JSON 视图，替代直接查询 Micrometer/Actuator。
 */
@Schema(description = "RAG 服务指标汇总")
public record RagMetricsSummary(
        @Schema(description = "总请求数", example = "1523")
        long totalRequests,

        @Schema(description = "成功请求数", example = "1489")
        long successfulRequests,

        @Schema(description = "失败请求数", example = "34")
        long failedRequests,

        @Schema(description = "成功率（百分比）", example = "97.77")
        double successRate,

        @Schema(description = "检索结果总数", example = "28450")
        long totalRetrievalResults,

        @Schema(description = "LLM Token 消耗总数", example = "892450")
        long totalLlmTokens,

        @Schema(description = "指标快照时间")
        Instant timestamp
) {
    public static RagMetricsSummary of(long total, long success, long failed,
            double rate, long retrievalResults, long tokens) {
        return new RagMetricsSummary(total, success, failed, rate,
                retrievalResults, tokens, Instant.now());
    }
}
