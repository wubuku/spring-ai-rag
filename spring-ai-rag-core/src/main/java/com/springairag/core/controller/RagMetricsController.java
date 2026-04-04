package com.springairag.core.controller;

import com.springairag.api.dto.RagMetricsSummary;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.ModelRegistry;
import com.springairag.core.metrics.ModelMetricsService;
import com.springairag.core.metrics.RagMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 指标监控控制器
 *
 * <p>提供关键指标的简洁 JSON 视图。
 * 指标由 {@link RagMetricsService} 通过 Micrometer 收集，
 * 此端点聚合关键数据，避免客户端直接查询 Actuator。
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag")
@Tag(name = "RAG Metrics", description = "RAG 服务指标监控")
public class RagMetricsController {

    private final RagMetricsService metricsService;
    private final ModelMetricsService modelMetricsService;
    private final ModelRegistry modelRegistry;
    private final ChatModelRouter modelRouter;

    public RagMetricsController(RagMetricsService metricsService,
                                ModelMetricsService modelMetricsService,
                                ModelRegistry modelRegistry,
                                ChatModelRouter modelRouter) {
        this.metricsService = metricsService;
        this.modelMetricsService = modelMetricsService;
        this.modelRegistry = modelRegistry;
        this.modelRouter = modelRouter;
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

    @Operation(summary = "获取各模型指标",
            description = "返回各 provider 的调用量、错误率、延迟等模型级指标。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回模型级指标数据"),
    })
    @GetMapping(value = "/metrics/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getModelMetrics() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("multiModelEnabled", modelRouter.isMultiModelEnabled());

        List<String> providers = modelRouter.getAvailableProviders();
        List<Map<String, Object>> modelStats = providers.stream()
                .map(p -> {
                    Map<String, Object> stats = new LinkedHashMap<>();
                    stats.put("provider", p);
                    stats.put("calls", modelMetricsService.getCallCount(p));
                    stats.put("errors", modelMetricsService.getErrorCount(p));
                    stats.put("errorRate", modelMetricsService.getErrorRate(p));
                    stats.put("displayName", modelRegistry.getDisplayName(p));
                    return stats;
                })
                .toList();

        response.put("models", modelStats);
        return response;
    }
}
