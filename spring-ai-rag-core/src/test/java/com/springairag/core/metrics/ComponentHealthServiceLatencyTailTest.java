package com.springairag.core.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 组件健康检查长尾（Batch 966，JaCoCo 驱动）：数据库探针的 SLOW
 * 慢响应臂、缓存统计抛异常时的 fail-open 降级、整体状态对 SLOW 的
 * DEGRADED 判定。
 */
class ComponentHealthServiceLatencyTailTest {

    @Test
    void databaseProbeFlagsSlowWhenLatencyExceedsThreshold() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        // 探针延迟 > 1000ms 阈值：mock 里真实睡 1.1s。
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(1100);
                    return 1;
                });
        ComponentHealthService service = new ComponentHealthService(
                jdbcTemplate, mock(CacheMetricsService.class));

        ComponentHealthService.ComponentStatus status = service.checkDatabase();

        assertEquals("SLOW", status.status());
        assertNull(status.error());
        Object latency = status.details().get("latencyMs");
        assertTrue(latency instanceof Long latencyMs && latencyMs > 1000,
                "latencyMs should exceed the slow threshold: " + latency);
    }

    @Test
    void cacheCheckFailsOpenWhenStatsThrow() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenReturn(1);
        CacheMetricsService failingCache = mock(CacheMetricsService.class);
        when(failingCache.getStats())
                .thenThrow(new IllegalStateException("stats down"));
        ComponentHealthService service = new ComponentHealthService(
                jdbcTemplate, failingCache);

        ComponentHealthService.ComponentStatus status = service.checkCache();

        // 缓存统计失败不影响整体健康：仍判 UP，但 enabled=false。
        assertEquals("UP", status.status());
        assertEquals(Boolean.FALSE, status.details().get("enabled"));
    }

    @Test
    void overallStatusFlagsDegradedWhenAnyComponentSlow() {
        ComponentHealthService service = new ComponentHealthService(
                mock(JdbcTemplate.class), mock(CacheMetricsService.class));

        String status = service.overallStatus(Map.of(
                "database",
                new ComponentHealthService.ComponentStatus(
                        "UP", Map.of(), null),
                "cache",
                new ComponentHealthService.ComponentStatus(
                        "SLOW", Map.of(), null)));

        assertEquals("DEGRADED", status);
    }
}
