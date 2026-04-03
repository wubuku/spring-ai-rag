package com.springairag.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagHealthIndicator 单元测试
 */
class RagHealthIndicatorTest {

    private JdbcTemplate jdbcTemplate;
    private RagMetricsService metricsService;
    private CacheMetricsService cacheMetricsService;
    private ComponentHealthService componentHealth;
    private RagHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        metricsService = mock(RagMetricsService.class);
        cacheMetricsService = mock(CacheMetricsService.class);
        componentHealth = new ComponentHealthService(jdbcTemplate, cacheMetricsService);
        healthIndicator = new RagHealthIndicator(componentHealth, metricsService);
    }

    @Test
    @DisplayName("所有组件正常时返回 UP")
    void healthy_returnsUp() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class))).thenReturn("0.7.4");
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class))).thenReturn(100);
        when(cacheMetricsService.getStats()).thenReturn(Map.of(
                "hitCount", 80L, "missCount", 20L, "totalCount", 100L, "hitRate", "80.0%"));
        when(metricsService.getTotalRequests()).thenReturn(42L);
        when(metricsService.getSuccessRate()).thenReturn(95.5);

        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertNotNull(health.getDetails().get("database"));
        assertNotNull(health.getDetails().get("pgvector"));
        assertNotNull(health.getDetails().get("tables"));
        assertNotNull(health.getDetails().get("cache"));
        assertEquals(42L, health.getDetails().get("totalRequests"));
    }

    @Test
    @DisplayName("数据库不可用时返回 DOWN")
    void databaseDown_returnsDown() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new RuntimeException("Connection refused"));

        Health health = healthIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
    }

    @Test
    @DisplayName("pgvector 不可用时返回 DEGRADED")
    void pgvectorDown_returnsDegraded() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class)))
                .thenThrow(new RuntimeException("extension not found"));
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class))).thenReturn(0);
        when(cacheMetricsService.getStats()).thenReturn(Map.of(
                "hitCount", 0L, "missCount", 0L, "totalCount", 0L, "hitRate", "N/A"));
        when(metricsService.getTotalRequests()).thenReturn(0L);
        when(metricsService.getSuccessRate()).thenReturn(100.0);

        Health health = healthIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
    }
}
