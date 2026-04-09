package com.springairag.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ComponentHealthService - Component-level Health Checks")
class ComponentHealthServiceTest {

    private JdbcTemplate jdbcTemplate;
    private CacheMetricsService cacheMetricsService;
    private ComponentHealthService healthService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        cacheMetricsService = mock(CacheMetricsService.class);
        healthService = new ComponentHealthService(jdbcTemplate, cacheMetricsService);
    }

    @Nested
    @DisplayName("Database Check")
    class DatabaseCheck {

        @Test
        @DisplayName("Returns UP for normal connection")
        void normalConnection() {
            when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

            ComponentHealthService.ComponentStatus status = healthService.checkDatabase();

            assertEquals("UP", status.status());
            assertTrue((Long) status.details().get("latencyMs") >= 0);
            assertNull(status.error());
        }

        @Test
        @DisplayName("Returns DOWN when connection fails")
        void connectionFailed() {
            when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                    .thenThrow(new RuntimeException("Connection refused"));

            ComponentHealthService.ComponentStatus status = healthService.checkDatabase();

            assertEquals("DOWN", status.status());
            assertNotNull(status.error());
        }
    }

    @Nested
    @DisplayName("pgvector Check")
    class PgVectorCheck {

        @Test
        @DisplayName("extension exists and returns version")
        void extensionExists() {
            when(jdbcTemplate.queryForObject(
                    eq("SELECT extversion FROM pg_extension WHERE extname = 'vector'"),
                    eq(String.class))).thenReturn("0.7.4");

            ComponentHealthService.ComponentStatus status = healthService.checkPgVector();

            assertEquals("UP", status.status());
            assertEquals("0.7.4", status.details().get("version"));
        }

        @Test
        @DisplayName("Returns DOWN when extension is missing")
        void extensionMissing() {
            when(jdbcTemplate.queryForObject(any(String.class), eq(String.class)))
                    .thenThrow(new RuntimeException("no data found"));

            ComponentHealthService.ComponentStatus status = healthService.checkPgVector();

            assertEquals("DOWN", status.status());
            assertNotNull(status.error());
        }
    }

    @Nested
    @DisplayName("Table Check")
    class TablesCheck {

        @Test
        @DisplayName("Returns UP with table counts when all tables exist")
        void allTablesExist() {
            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM rag_documents"), eq(Integer.class))).thenReturn(100);
            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM rag_embeddings"), eq(Integer.class))).thenReturn(500);
            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM rag_collections"), eq(Integer.class))).thenReturn(10);

            ComponentHealthService.ComponentStatus status = healthService.checkTables();

            assertEquals("UP", status.status());
            assertEquals(100, status.details().get("rag_documents"));
            assertEquals(500, status.details().get("rag_embeddings"));
            assertEquals(10, status.details().get("rag_collections"));
        }

        @Test
        @DisplayName("Returns DEGRADED when some tables are missing")
        void someTablesMissing() {
            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM rag_documents"), eq(Integer.class))).thenReturn(100);
            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM rag_embeddings"), eq(Integer.class)))
                    .thenThrow(new RuntimeException("relation not found"));
            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM rag_collections"), eq(Integer.class))).thenReturn(10);

            ComponentHealthService.ComponentStatus status = healthService.checkTables();

            assertEquals("DEGRADED", status.status());
            assertEquals(100, status.details().get("rag_documents"));
            assertEquals("missing", status.details().get("rag_embeddings"));
        }
    }

    @Nested
    @DisplayName("Cache Check")
    class CacheCheck {

        @Test
        @DisplayName("Returns hitRate when cache metrics are available")
        void withCacheMetrics() {
            when(cacheMetricsService.getStats()).thenReturn(Map.of(
                    "hitCount", 80L, "missCount", 20L, "totalCount", 100L, "hitRate", "80.0%"));

            ComponentHealthService.ComponentStatus status = healthService.checkCache();

            assertEquals("UP", status.status());
            assertEquals("80.0%", status.details().get("hitRate"));
        }

        @Test
        @DisplayName("Returns enabled=false when CacheMetricsService is null")
        void nullCacheMetrics() {
            ComponentHealthService service = new ComponentHealthService(jdbcTemplate, null);

            ComponentHealthService.ComponentStatus status = service.checkCache();

            assertEquals("UP", status.status());
            assertEquals(false, status.details().get("enabled"));
        }
    }

    @Nested
    @DisplayName("Overall Status")
    class OverallStatus {

        @Test
        @DisplayName("Returns UP overall when all components are UP")
        void allUp() {
            when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
            when(jdbcTemplate.queryForObject(any(String.class), eq(String.class))).thenReturn("0.7.4");
            when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class))).thenReturn(0);
            when(cacheMetricsService.getStats()).thenReturn(Map.of(
                    "hitCount", 0L, "missCount", 0L, "totalCount", 0L, "hitRate", "N/A"));

            Map<String, ComponentHealthService.ComponentStatus> components = healthService.checkAll();
            String overall = healthService.overallStatus(components);

            assertEquals("UP", overall);
        }

        @Test
        @DisplayName("Returns DOWN overall when database is DOWN")
        void dbDown() {
            when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                    .thenThrow(new RuntimeException("fail"));

            Map<String, ComponentHealthService.ComponentStatus> components = healthService.checkAll();
            String overall = healthService.overallStatus(components);

            assertEquals("DOWN", overall);
        }
    }
}
