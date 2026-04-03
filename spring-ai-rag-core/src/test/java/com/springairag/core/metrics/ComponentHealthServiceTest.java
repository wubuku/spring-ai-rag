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

@DisplayName("ComponentHealthService — 组件级健康检查")
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
    @DisplayName("数据库检查")
    class DatabaseCheck {

        @Test
        @DisplayName("正常连接返回 UP")
        void normalConnection() {
            when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

            ComponentHealthService.ComponentStatus status = healthService.checkDatabase();

            assertEquals("UP", status.status());
            assertTrue((Long) status.details().get("latencyMs") >= 0);
            assertNull(status.error());
        }

        @Test
        @DisplayName("连接失败返回 DOWN")
        void connectionFailed() {
            when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                    .thenThrow(new RuntimeException("Connection refused"));

            ComponentHealthService.ComponentStatus status = healthService.checkDatabase();

            assertEquals("DOWN", status.status());
            assertNotNull(status.error());
        }
    }

    @Nested
    @DisplayName("pgvector 检查")
    class PgVectorCheck {

        @Test
        @DisplayName("扩展存在返回版本号")
        void extensionExists() {
            when(jdbcTemplate.queryForObject(
                    eq("SELECT extversion FROM pg_extension WHERE extname = 'vector'"),
                    eq(String.class))).thenReturn("0.7.4");

            ComponentHealthService.ComponentStatus status = healthService.checkPgVector();

            assertEquals("UP", status.status());
            assertEquals("0.7.4", status.details().get("version"));
        }

        @Test
        @DisplayName("扩展不存在返回 DOWN")
        void extensionMissing() {
            when(jdbcTemplate.queryForObject(any(String.class), eq(String.class)))
                    .thenThrow(new RuntimeException("no data found"));

            ComponentHealthService.ComponentStatus status = healthService.checkPgVector();

            assertEquals("DOWN", status.status());
            assertNotNull(status.error());
        }
    }

    @Nested
    @DisplayName("表检查")
    class TablesCheck {

        @Test
        @DisplayName("所有表存在返回 UP + 计数")
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
        @DisplayName("部分表缺失返回 DEGRADED")
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
    @DisplayName("缓存检查")
    class CacheCheck {

        @Test
        @DisplayName("有缓存指标时返回 hitRate")
        void withCacheMetrics() {
            when(cacheMetricsService.getStats()).thenReturn(Map.of(
                    "hitCount", 80L, "missCount", 20L, "totalCount", 100L, "hitRate", "80.0%"));

            ComponentHealthService.ComponentStatus status = healthService.checkCache();

            assertEquals("UP", status.status());
            assertEquals("80.0%", status.details().get("hitRate"));
        }

        @Test
        @DisplayName("CacheMetricsService 为 null 时返回 enabled=false")
        void nullCacheMetrics() {
            ComponentHealthService service = new ComponentHealthService(jdbcTemplate, null);

            ComponentHealthService.ComponentStatus status = service.checkCache();

            assertEquals("UP", status.status());
            assertEquals(false, status.details().get("enabled"));
        }
    }

    @Nested
    @DisplayName("综合状态")
    class OverallStatus {

        @Test
        @DisplayName("全部 UP 时整体 UP")
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
        @DisplayName("数据库 DOWN 时整体 DOWN")
        void dbDown() {
            when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                    .thenThrow(new RuntimeException("fail"));

            Map<String, ComponentHealthService.ComponentStatus> components = healthService.checkAll();
            String overall = healthService.overallStatus(components);

            assertEquals("DOWN", overall);
        }
    }
}
