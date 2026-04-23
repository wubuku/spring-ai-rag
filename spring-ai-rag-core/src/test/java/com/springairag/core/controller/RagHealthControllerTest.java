package com.springairag.core.controller;

import com.springairag.api.dto.ComponentHealthResponse;
import com.springairag.api.dto.HealthResponse;
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
 * RagHealthController Unit Tests
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
    @DisplayName("Health check returns component statuses")
    void health_returnsComponentStatus() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class))).thenReturn("0.7.4");
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class))).thenReturn(10);
        when(cacheMetricsService.getStats()).thenReturn(Map.of(
                "hitCount", 80L, "missCount", 20L, "totalCount", 100L, "hitRate", "80.0%"));

        ResponseEntity<HealthResponse> response = controller.health();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().status());
        assertEquals("UP", response.getBody().components().get("database"));
        assertEquals("UP", response.getBody().components().get("pgvector"));
        assertNotNull(response.getBody().timestamp());
    }

    @Test
    @DisplayName("Returns DOWN when database is unavailable")
    void health_databaseDown() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new RuntimeException("Connection refused"));

        ResponseEntity<HealthResponse> response = controller.health();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("DOWN", response.getBody().status());
    }

    @Test
    @DisplayName("Component detail endpoint returns complete information")
    void healthComponents_returnsDetails() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class))).thenReturn("0.7.4");
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class))).thenReturn(10);
        when(cacheMetricsService.getStats()).thenReturn(Map.of(
                "hitCount", 80L, "missCount", 20L, "totalCount", 100L, "hitRate", "80.0%"));

        ResponseEntity<ComponentHealthResponse> response = controller.healthComponents();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().status());
        assertNotNull(response.getBody().components());
        assertTrue(response.getBody().components().containsKey("database"));
        assertTrue(response.getBody().components().containsKey("pgvector"));
        assertTrue(response.getBody().components().containsKey("tables"));
        assertTrue(response.getBody().components().containsKey("cache"));
    }
}
