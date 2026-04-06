package com.springairag.core.controller;

import com.springairag.api.dto.ApiSloComplianceResponse;
import com.springairag.api.dto.ModelMetricsResponse;
import com.springairag.api.dto.RagMetricsSummary;
import com.springairag.api.dto.SlowQueryStatsResponse;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.ModelRegistry;
import com.springairag.core.metrics.ApiSloTrackerService;
import com.springairag.core.metrics.ModelMetricsService;
import com.springairag.core.metrics.RagMetricsService;
import com.springairag.core.metrics.SlowQueryMetricsService;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    private final SlowQueryMetricsService slowQueryMetricsService;
    private final ApiSloTrackerService sloTrackerService;

    public RagMetricsController(RagMetricsService metricsService,
                                ModelMetricsService modelMetricsService,
                                ModelRegistry modelRegistry,
                                ChatModelRouter modelRouter,
                                @Autowired(required = false) SlowQueryMetricsService slowQueryMetricsService,
                                @Autowired(required = false) ApiSloTrackerService sloTrackerService) {
        this.metricsService = metricsService;
        this.modelMetricsService = modelMetricsService;
        this.modelRegistry = modelRegistry;
        this.modelRouter = modelRouter;
        this.slowQueryMetricsService = slowQueryMetricsService;
        this.sloTrackerService = sloTrackerService;
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
    public ModelMetricsResponse getModelMetrics() {
        List<String> providers = modelRouter.getAvailableProviders();

        List<ModelMetricsResponse.ModelMetric> modelStats = providers.stream()
                .map(p -> new ModelMetricsResponse.ModelMetric(
                        p,
                        modelMetricsService.getCallCount(p),
                        modelMetricsService.getErrorCount(p),
                        modelMetricsService.getErrorRate(p),
                        modelRegistry.getDisplayName(p)))
                .toList();

        return new ModelMetricsResponse(modelRouter.isMultiModelEnabled(), modelStats);
    }

    @Operation(summary = "Get slow query statistics",
            description = "Returns slow query count, threshold, and recent slow query records. "
                    + "Requires hibernate.generate_statistics=true to be configured.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Returns slow query statistics"),
    })
    @GetMapping(value = "/metrics/slow-queries", produces = MediaType.APPLICATION_JSON_VALUE)
    public SlowQueryStatsResponse getSlowQueryStats() {
        if (slowQueryMetricsService == null) {
            return new SlowQueryStatsResponse(
                    false, 0, 0, 0, 0, List.of());
        }
        SlowQueryMetricsService.SlowQueryStatsSummary summary =
                slowQueryMetricsService.getStatsSummary();
        List<SlowQueryStatsResponse.SlowQueryRecordDto> recentRecords =
                summary.recentSlowQueries().stream()
                        .map(r -> new SlowQueryStatsResponse.SlowQueryRecordDto(
                                r.timestampMs(), r.durationMs(),
                                maskSql(r.sql())))
                        .toList();
        return new SlowQueryStatsResponse(
                slowQueryMetricsService.isEnabled(),
                slowQueryMetricsService.getThresholdMs(),
                summary.totalQueryCount(),
                summary.slowQueryCount(),
                summary.averageQueryDurationMs(),
                recentRecords
        );
    }

    @Operation(summary = "Get API SLO compliance metrics",
            description = "Returns per-endpoint SLO compliance percentages (p95 latency vs. threshold) "
                    + "within the configured time window. Use this to monitor whether key API endpoints "
                    + "are meeting their latency objectives.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Returns SLO compliance metrics per endpoint"),
    })
    @GetMapping(value = "/metrics/slo", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiSloComplianceResponse getSloCompliance() {
        if (sloTrackerService == null) {
            return new ApiSloComplianceResponse(false, 0, List.of());
        }
        return sloTrackerService.getCompliance();
    }

    private static String maskSql(String sql) {
        if (sql == null) return null;
        return sql.replaceAll("(?i)('[^']*')", "'***'");
    }
}
