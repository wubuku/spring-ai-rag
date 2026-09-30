package com.springairag.core.controller;

import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.ModelRegistry;
import com.springairag.core.metrics.ApiSloTrackerService;
import com.springairag.core.metrics.ModelMetricsService;
import com.springairag.core.metrics.RagMetricsService;
import com.springairag.core.metrics.SlowQueryMetricsService;
import com.springairag.core.usage.LlmUsageQueryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 指标控制器长尾（Batch 733，JaCoCo 驱动）：慢查询记录的 SQL 掩
 * 码映射（137-139）、durable usage 查询通道缺失时的拒绝（194）与
 * 委托（204-205）。
 */
class RagMetricsControllerUsageTailTest {

    private RagMetricsService metricsService;
    private SlowQueryMetricsService slowQueryMetricsService;
    private LlmUsageQueryService usageQueryService;
    private RagMetricsController controller;

    @BeforeEach
    void setUp() {
        metricsService = mock(RagMetricsService.class);
        slowQueryMetricsService = mock(SlowQueryMetricsService.class);
        usageQueryService = mock(LlmUsageQueryService.class);
        controller = new RagMetricsController(
                metricsService, mock(ModelMetricsService.class),
                mock(ModelRegistry.class), mock(ChatModelRouter.class),
                slowQueryMetricsService, mock(ApiSloTrackerService.class),
                usageQueryService);
    }

    @Test
    void slowQueryStatsMaskSqlStrings() {
        when(slowQueryMetricsService.getStatsSummary())
                .thenReturn(new SlowQueryMetricsService.SlowQueryStatsSummary(
                        2, 2_000L, 1, 1_000L, 1_000L,
                        List.of(
                                new SlowQueryMetricsService.SlowQueryRecord(
                                        1L, "SELECT * FROM t WHERE k = 'secret'", 1_500L),
                                new SlowQueryMetricsService.SlowQueryRecord(
                                        2L, "SELECT 1", 1_200L))));
        when(slowQueryMetricsService.isEnabled()).thenReturn(true);

        var response = controller.getSlowQueryStats();

        Assertions.assertEquals(2, response.recentSlowQueries().size());
        // 掩码：字符串字面量替换为 '***'。
        Assertions.assertEquals("SELECT * FROM t WHERE k = '***'",
                response.recentSlowQueries().get(0).sql());
        Assertions.assertEquals("SELECT 1",
                response.recentSlowQueries().get(1).sql());
    }

    @Test
    void durableUsageQueryThrowsWhenChannelUnavailable() {
        var controllerNoUsage = new RagMetricsController(
                metricsService, mock(ModelMetricsService.class),
                mock(ModelRegistry.class), mock(ChatModelRouter.class),
                slowQueryMetricsService, mock(ApiSloTrackerService.class),
                null);

        Assertions.assertThrows(IllegalStateException.class,
                () -> controllerNoUsage.getUsage(
                        null, null, null,
                        new MockHttpServletRequest()));
    }

    @Test
    void durableUsageQueryDelegatesToQueryService() {
        var request = new MockHttpServletRequest("GET", "/ops/usage");
        var response = Mockito.mock(com.springairag.api.dto.LlmUsageResponse.class);
        Mockito.when(usageQueryService.query(
                        Mockito.any(), Mockito.eq("from-1"),
                        Mockito.eq("to-1"), Mockito.eq("p-1")))
                .thenReturn(response);

        var result = controller.getUsage("from-1", "to-1", "p-1", request);

        Assertions.assertSame(response, result);
    }
}
