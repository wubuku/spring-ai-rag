package com.springairag.core.controller;

import com.springairag.api.dto.ApiSloComplianceResponse;
import com.springairag.api.dto.LlmUsageResponse;
import com.springairag.api.dto.ModelMetricsResponse;
import com.springairag.api.dto.RagMetricsSummary;
import com.springairag.api.dto.SlowQueryStatsResponse;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.ModelRegistry;
import com.springairag.core.metrics.ApiSloTrackerService;
import com.springairag.core.metrics.ModelMetricsService;
import com.springairag.core.metrics.RagMetricsService;
import com.springairag.core.metrics.SlowQueryMetricsService;
import com.springairag.core.usage.LlmUsageQueryService;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * RAG metrics monitoring controller.
 *
 * <p>Provides a concise JSON view of key metrics.
 * Metrics are collected by {@link RagMetricsService} via Micrometer;
 * this endpoint aggregates key data, avoiding clients from directly querying Actuator.
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag")
@Tag(name = "RAG Metrics", description = "RAG service metrics monitoring")
public class RagMetricsController {

    private final RagMetricsService metricsService;
    private final ModelMetricsService modelMetricsService;
    private final ModelRegistry modelRegistry;
    private final ChatModelRouter modelRouter;
    private final SlowQueryMetricsService slowQueryMetricsService;
    private final ApiSloTrackerService sloTrackerService;
    private final LlmUsageQueryService usageQueryService;

    @Autowired
    public RagMetricsController(RagMetricsService metricsService,
                                ModelMetricsService modelMetricsService,
                                ModelRegistry modelRegistry,
                                ChatModelRouter modelRouter,
                                @Autowired(required = false) SlowQueryMetricsService slowQueryMetricsService,
                                @Autowired(required = false) ApiSloTrackerService sloTrackerService,
                                @Autowired(required = false) LlmUsageQueryService usageQueryService) {
        this.metricsService = metricsService;
        this.modelMetricsService = modelMetricsService;
        this.modelRegistry = modelRegistry;
        this.modelRouter = modelRouter;
        this.slowQueryMetricsService = slowQueryMetricsService;
        this.sloTrackerService = sloTrackerService;
        this.usageQueryService = usageQueryService;
    }

    /**
     * Backward-compatible constructor for existing extensions and unit fixtures.
     */
    public RagMetricsController(RagMetricsService metricsService,
                                ModelMetricsService modelMetricsService,
                                ModelRegistry modelRegistry,
                                ChatModelRouter modelRouter,
                                SlowQueryMetricsService slowQueryMetricsService,
                                ApiSloTrackerService sloTrackerService) {
        this(metricsService, modelMetricsService, modelRegistry, modelRouter,
                slowQueryMetricsService, sloTrackerService, null);
    }

    @Operation(summary = "Get RAG metrics summary",
            description = "Returns key metrics: total requests, success rate, total retrieval results, token consumption, etc.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Returns RAG metrics summary data"),
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

    @Operation(summary = "Get per-model metrics",
            description = "Returns per-provider call count, error rate, latency and other model-level metrics.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Returns model-level metrics data"),
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

    @Operation(summary = "Clear slow query history",
            description = "Clears the in-memory slow query record queue and resets the total slow query counter. "
                    + "This does not affect persistent logs or database records.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Slow query history cleared"),
    })
    @DeleteMapping(value = "/metrics/slow-queries")
    public void clearSlowQueryHistory() {
        if (slowQueryMetricsService != null) {
            slowQueryMetricsService.clearHistory();
        }
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

    @Operation(summary = "Get durable model usage aggregation",
            description = "Returns principal-scoped model invocation counts, tokens, and "
                    + "configured cost estimates for an inclusive UTC date range.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Returns durable usage aggregation"),
            @ApiResponse(responseCode = "400", description = "Invalid date or principal"),
            @ApiResponse(responseCode = "403", description = "Principal is not allowed to query the requested scope")
    })
    @GetMapping(value = "/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    public LlmUsageResponse getUsage(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String principalId,
            HttpServletRequest request) {
        if (usageQueryService == null) {
            throw new IllegalStateException("Durable usage query is unavailable");
        }
        return usageQueryService.query(
                ChatPrincipal.from(request),
                from,
                to,
                principalId);
    }

    private static String maskSql(String sql) {
        if (sql == null) return null;
        return sql.replaceAll("(?i)('[^']*')", "'***'");
    }
}
