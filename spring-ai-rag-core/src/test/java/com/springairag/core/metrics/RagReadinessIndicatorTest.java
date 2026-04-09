package com.springairag.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagReadinessIndicator Tests")
class RagReadinessIndicatorTest {

    @Mock
    private ComponentHealthService componentHealth;

    @Mock
    private RagMetricsService metricsService;

    private RagReadinessIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new RagReadinessIndicator(componentHealth, metricsService);
    }

    @Test
    @DisplayName("Returns UP when all components are UP")
    void health_allUp_returnsUp() {
        Map<String, ComponentHealthService.ComponentStatus> components = new LinkedHashMap<>();
        components.put("database", new ComponentHealthService.ComponentStatus("UP",
                Map.of("latencyMs", 5), null));
        components.put("pgvector", new ComponentHealthService.ComponentStatus("UP",
                Map.of("version", "0.7.4"), null));
        components.put("tables", new ComponentHealthService.ComponentStatus("UP",
                Map.of("rag_documents", 100), null));
        components.put("cache", new ComponentHealthService.ComponentStatus("UP",
                Map.of("hitRate", "80%"), null));

        when(componentHealth.checkAll()).thenReturn(components);
        when(componentHealth.overallStatus(components)).thenReturn("UP");
        when(metricsService.getTotalRequests()).thenReturn(50L);
        when(metricsService.getSuccessRate()).thenReturn(98.0);

        Health health = indicator.health();

        assertEquals("UP", health.getStatus().getCode());
        assertEquals(50L, health.getDetails().get("totalRequests"));
        assertEquals("98.0%", health.getDetails().get("successRate"));
        assertTrue(health.getDetails().containsKey("database"));
        assertTrue(health.getDetails().containsKey("pgvector"));
        assertTrue(health.getDetails().containsKey("tables"));
        assertTrue(health.getDetails().containsKey("cache"));
    }

    @Test
    @DisplayName("Returns DOWN when database is DOWN")
    void health_databaseDown_returnsDown() {
        Map<String, ComponentHealthService.ComponentStatus> components = new LinkedHashMap<>();
        components.put("database", new ComponentHealthService.ComponentStatus("DOWN",
                Map.of(), "connection refused"));
        components.put("pgvector", new ComponentHealthService.ComponentStatus("UP",
                Map.of("version", "0.7.4"), null));

        when(componentHealth.checkAll()).thenReturn(components);
        when(componentHealth.overallStatus(components)).thenReturn("DOWN");
        when(metricsService.getTotalRequests()).thenReturn(10L);
        when(metricsService.getSuccessRate()).thenReturn(50.0);

        Health health = indicator.health();

        assertEquals("DOWN", health.getStatus().getCode());
        assertTrue(health.getDetails().containsKey("database"));
    }

    @Test
    @DisplayName("Returns UP when pgvector is DOWN but database is UP (DEGRADED still accepts traffic)")
    void health_pgvectorDown_returnsUpStill() {
        Map<String, ComponentHealthService.ComponentStatus> components = new LinkedHashMap<>();
        components.put("database", new ComponentHealthService.ComponentStatus("UP",
                Map.of("latencyMs", 5), null));
        components.put("pgvector", new ComponentHealthService.ComponentStatus("DOWN",
                Map.of(), "not found"));

        when(componentHealth.checkAll()).thenReturn(components);
        when(componentHealth.overallStatus(components)).thenReturn("DEGRADED");
        when(metricsService.getTotalRequests()).thenReturn(0L);
        when(metricsService.getSuccessRate()).thenReturn(0.0);

        Health health = indicator.health();

        // DEGRADED maps to UP in readiness (still accepts traffic)
        assertEquals("UP", health.getStatus().getCode());
    }

    @Test
    @DisplayName("includes all component details")
    void health_includesAllComponentDetails() {
        Map<String, ComponentHealthService.ComponentStatus> components = new LinkedHashMap<>();
        components.put("database", new ComponentHealthService.ComponentStatus("UP",
                Map.of("latencyMs", 3), null));
        components.put("pgvector", new ComponentHealthService.ComponentStatus("UP",
                Map.of("version", "0.7.4"), null));
        components.put("tables", new ComponentHealthService.ComponentStatus("UP",
                Map.of("rag_documents", 150, "rag_collections", 5), null));
        components.put("cache", new ComponentHealthService.ComponentStatus("UP",
                Map.of("hitRate", "82.3%", "hitCount", 823L, "missCount", 177L), null));

        when(componentHealth.checkAll()).thenReturn(components);
        when(componentHealth.overallStatus(components)).thenReturn("UP");
        when(metricsService.getTotalRequests()).thenReturn(1000L);
        when(metricsService.getSuccessRate()).thenReturn(99.5);

        Health health = indicator.health();

        @SuppressWarnings("unchecked")
        Map<String, Object> dbDetails = (Map<String, Object>) health.getDetails().get("database");
        assertEquals("UP", dbDetails.get("status"));
        assertEquals(3, dbDetails.get("latencyMs"));

        @SuppressWarnings("unchecked")
        Map<String, Object> cacheDetails = (Map<String, Object>) health.getDetails().get("cache");
        assertEquals("82.3%", cacheDetails.get("hitRate"));
    }
}
