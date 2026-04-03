package com.springairag.core.controller;

import com.springairag.core.metrics.CacheMetricsService;
import com.springairag.core.metrics.ComponentHealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagHealthController 单元测试
 */
class RagHealthControllerTest {

    private JdbcTemplate jdbcTemplate;
    private CacheMetricsService cacheMetricsService;
    private ComponentHealthService componentHealth;
    private RagHealthController controller;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        cacheMetricsService = mock(CacheMetricsService.class);
        componentHealth = new ComponentHealthService(jdbcTemplate, cacheMetricsService);
        controller = new RagHealthController(componentHealth);
    }

    @Test
    @DisplayName("健康检查返回各组件状态")
    void health_returnsComponentStatus() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class))).thenReturn("0.7.4");
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class))).thenReturn(10);
        when(cacheMetricsService.getStats()).thenReturn(Map.of(
                "hitCount", 80L, "missCount", 20L, "totalCount", 100L, "hitRate", "80.0%"));

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("UP", response.getBody().get("database"));
        assertEquals("UP", response.getBody().get("pgvector"));
        assertNotNull(response.getBody().get("timestamp"));
    }

    @Test
    @DisplayName("数据库不可用时返回 DOWN")
    void health_databaseDown() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new RuntimeException("Connection refused"));

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("DOWN", response.getBody().get("status"));
    }

    @Test
    @DisplayName("组件详细端点返回完整信息")
    void healthComponents_returnsDetails() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class))).thenReturn("0.7.4");
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class))).thenReturn(10);
        when(cacheMetricsService.getStats()).thenReturn(Map.of(
                "hitCount", 80L, "missCount", 20L, "totalCount", 100L, "hitRate", "80.0%"));

        ResponseEntity<Map<String, Object>> response = controller.healthComponents();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
        assertNotNull(response.getBody().get("components"));
        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        assertTrue(components.containsKey("database"));
        assertTrue(components.containsKey("pgvector"));
        assertTrue(components.containsKey("tables"));
        assertTrue(components.containsKey("cache"));
    }
}
