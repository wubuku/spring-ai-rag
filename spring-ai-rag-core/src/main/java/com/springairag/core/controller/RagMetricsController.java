package com.springairag.core.controller;

import com.springairag.api.dto.RagMetricsSummary;
import com.springairag.core.metrics.RagMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 指标监控控制器
 *
 * <p>提供关键指标的简洁 JSON 视图。
 * 指标由 {@link RagMetricsService} 通过 Micrometer 收集，
 * 此端点聚合关键数据，避免客户端直接查询 Actuator。
 */
@RestController
@RequestMapping("/api/v1/rag")
@Tag(name = "RAG Metrics", description = "RAG 服务指标监控")
public class RagMetricsController {

    private final RagMetricsService metricsService;

    public RagMetricsController(RagMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @Operation(summary = "获取 RAG 指标汇总",
            description = "返回总请求数、成功率、检索结果总数、Token 消耗等关键指标。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回 RAG 指标汇总数据"),
    })
    @GetMapping(value = "/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public RagMetricsSummary getMetrics() {
        return RagMetricsSummary.of(
                metricsService.getTotalRequests(),
                metricsService.getSuccessfulRequests(),
                metricsService.getFailedRequests(),
                metricsService.getSuccessRate(),
                metricsService.getTotalRetrievalResults(),
                metricsService.getTotalLlmTokens()
        );
    }
}
