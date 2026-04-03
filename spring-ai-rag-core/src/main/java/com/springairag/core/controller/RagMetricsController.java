package com.springairag.core.controller;

import com.springairag.core.metrics.CacheMetricsService;
import com.springairag.core.metrics.RagMetricsService;
import com.springairag.core.versioning.ApiVersion;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RAG 指标 REST 端点
 *
 * <p>提供 RAG 服务核心指标的聚合视图，便于监控和告警。
 * 指标通过 Micrometer 采集，可对接 Prometheus、Grafana 等监控系统。
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/metrics")
@Tag(name = "RAG Metrics", description = "RAG 服务指标监控")
public class RagMetricsController {

    private final RagMetricsService metricsService;
    private final CacheMetricsService cacheMetricsService;

    public RagMetricsController(RagMetricsService metricsService,
                                 CacheMetricsService cacheMetricsService) {
        this.metricsService = metricsService;
        this.cacheMetricsService = cacheMetricsService;
    }

    /**
     * 获取 RAG 服务核心指标
     *
     * @return 总请求数、成功/失败数、成功率、平均响应时间等
     */
    @Operation(summary = "获取 RAG 服务指标",
            description = "返回 RAG 核心指标：请求数、成功率、平均响应时间、检索结果数、Token 消耗")
    @ApiResponse(responseCode = "200", description = "返回 RAG 服务指标")
    @GetMapping("/rag")
    @Timed(value = "rag.metrics", description = "RAG metrics endpoint", percentiles = {0.5, 0.99})
    public ResponseEntity<Map<String, Object>> getRagMetrics() {
        return ResponseEntity.ok(Map.of(
                "totalRequests", metricsService.getTotalRequests(),
                "successfulRequests", metricsService.getSuccessfulRequests(),
                "failedRequests", metricsService.getFailedRequests(),
                "successRate", String.format("%.2f%%", metricsService.getSuccessRate()),
                "retrievalResultsTotal", metricsService.getTotalRetrievalResults(),
                "llmTokensTotal", metricsService.getTotalLlmTokens()
        ));
    }

    /**
     * 获取 RAG 和缓存的综合指标（双视图）
     *
     * @return RAG 指标 + 嵌入缓存命中率
     */
    @Operation(summary = "获取 RAG 综合指标",
            description = "返回 RAG 核心指标 + 嵌入缓存命中率的双视图")
    @ApiResponse(responseCode = "200", description = "返回综合指标")
    @GetMapping("/overview")
    @Timed(value = "rag.metrics.overview", description = "RAG metrics overview endpoint", percentiles = {0.5, 0.99})
    public ResponseEntity<Map<String, Object>> getOverview() {
        Map<String, Object> ragMetrics = Map.of(
                "totalRequests", metricsService.getTotalRequests(),
                "successfulRequests", metricsService.getSuccessfulRequests(),
                "failedRequests", metricsService.getFailedRequests(),
                "successRate", String.format("%.2f%%", metricsService.getSuccessRate()),
                "retrievalResultsTotal", metricsService.getTotalRetrievalResults(),
                "llmTokensTotal", metricsService.getTotalLlmTokens()
        );

        Map<String, Object> cacheStats = cacheMetricsService.getStats();

        return ResponseEntity.ok(Map.of(
                "rag", ragMetrics,
                "cache", cacheStats
        ));
    }
}
