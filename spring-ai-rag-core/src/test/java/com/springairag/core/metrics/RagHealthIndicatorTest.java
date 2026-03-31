package com.springairag.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagHealthIndicator 单元测试
 */
class RagHealthIndicatorTest {

    private JdbcTemplate jdbcTemplate;
    private RagMetricsService metricsService;
    private RagHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        metricsService = mock(RagMetricsService.class);
        healthIndicator = new RagHealthIndicator(jdbcTemplate, metricsService);
    }

    @Test
    @DisplayName("数据库正常 + 有指标时返回 UP")
    void healthy_returnsUp() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM rag_documents"), eq(Integer.class))).thenReturn(100);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM rag_embeddings"), eq(Integer.class))).thenReturn(500);
        when(metricsService.getTotalRequests()).thenReturn(42L);
        when(metricsService.getSuccessRate()).thenReturn(95.5);

        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("UP", health.getDetails().get("database"));
        assertEquals(100, health.getDetails().get("documents"));
        assertEquals(500, health.getDetails().get("embeddings"));
        assertEquals(42L, health.getDetails().get("totalRequests"));
        assertTrue(health.getDetails().get("successRate").toString().contains("95.5"));
    }

    @Test
    @DisplayName("数据库不可用时返回 DOWN")
    void databaseDown_returnsDown() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new RuntimeException("Connection refused"));

        Health health = healthIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("DOWN", health.getDetails().get("database"));
        assertNotNull(health.getDetails().get("databaseError"));
    }

    @Test
    @DisplayName("表不存在时仍返回 UP（表未初始化）")
    void tablesNotExist_stillUp() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM rag_documents"), eq(Integer.class)))
                .thenThrow(new RuntimeException("relation does not exist"));
        when(metricsService.getTotalRequests()).thenReturn(0L);
        when(metricsService.getSuccessRate()).thenReturn(100.0);

        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("not_initialized", health.getDetails().get("tables"));
    }
}
