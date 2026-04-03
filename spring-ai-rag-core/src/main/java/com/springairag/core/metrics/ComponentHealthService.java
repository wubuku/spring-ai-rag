package com.springairag.core.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 组件级健康检查服务
 *
 * <p>独立于 Spring Boot Actuator，可被 REST 控制器和 Actuator 指标共用。
 * 每个检查方法返回标准化的 {@link ComponentStatus}，包含状态、延迟和可选错误信息。
 *
 * <h3>检查项</h3>
 * <ul>
 *   <li>database — PostgreSQL 连接 + 查询延迟</li>
 *   <li>pgvector — vector 扩展可用性</li>
 *   <li>tables — 核心表存在性</li>
 *   <li>cache — 缓存统计摘要</li>
 * </ul>
 *
 * <p>嵌入模型和 LLM 检查是可选的（外部 API 调用），仅在显式启用时执行。
 */
public class ComponentHealthService {

    private static final Logger log = LoggerFactory.getLogger(ComponentHealthService.class);

    private static final long DB_SLOW_THRESHOLD_MS = 1000;
    private static final long DB_TIMEOUT_MS = 5000;

    private final JdbcTemplate jdbcTemplate;
    private final CacheMetricsService cacheMetricsService;

    public ComponentHealthService(JdbcTemplate jdbcTemplate,
                                   CacheMetricsService cacheMetricsService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cacheMetricsService = cacheMetricsService;
    }

    /**
     * 检查所有组件，返回有序结果
     */
    public Map<String, ComponentStatus> checkAll() {
        Map<String, ComponentStatus> results = new LinkedHashMap<>();
        results.put("database", checkDatabase());
        results.put("pgvector", checkPgVector());
        results.put("tables", checkTables());
        results.put("cache", checkCache());
        return results;
    }

    /**
     * 检查数据库连接和延迟
     */
    public ComponentStatus checkDatabase() {
        try {
            Instant start = Instant.now();
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            long latencyMs = Duration.between(start, Instant.now()).toMillis();

            String status = latencyMs > DB_SLOW_THRESHOLD_MS ? "SLOW" : "UP";
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("latencyMs", latencyMs);

            if (latencyMs > DB_SLOW_THRESHOLD_MS) {
                log.warn("数据库响应缓慢: {}ms (阈值 {}ms)", latencyMs, DB_SLOW_THRESHOLD_MS);
            }

            return new ComponentStatus(status, details, null);
        } catch (Exception e) { // Health probe: must never throw
            log.error("数据库健康检查失败: {}", e.getMessage());
            return new ComponentStatus("DOWN", Map.of(), e.getMessage());
        }
    }

    /**
     * 检查 pgvector 扩展是否可用
     */
    public ComponentStatus checkPgVector() {
        try {
            String version = jdbcTemplate.queryForObject(
                    "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
                    String.class);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("version", version != null ? version : "unknown");
            return new ComponentStatus("UP", details, null);
        } catch (Exception e) { // Health probe: must never throw
            log.debug("pgvector 扩展检查失败: {}", e.getMessage());
            return new ComponentStatus("DOWN", Map.of(),
                    "pgvector extension not found: " + e.getMessage());
        }
    }

    /**
     * 检查核心表是否存在
     */
    public ComponentStatus checkTables() {
        String[] coreTables = {"rag_documents", "rag_embeddings", "rag_collections"};
        Map<String, Object> details = new LinkedHashMap<>();
        boolean allExist = true;

        for (String table : coreTables) {
            try {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table, Integer.class);
                details.put(table, count != null ? count : 0);
            } catch (Exception e) { // Health probe: must never throw
                details.put(table, "missing");
                allExist = false;
            }
        }

        return new ComponentStatus(allExist ? "UP" : "DEGRADED", details, null);
    }

    /**
     * 检查缓存状态
     */
    public ComponentStatus checkCache() {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            if (cacheMetricsService != null) {
                Map<String, Object> stats = cacheMetricsService.getStats();
                details.put("hitRate", stats.get("hitRate"));
                details.put("hitCount", stats.get("hitCount"));
                details.put("missCount", stats.get("missCount"));
            } else {
                details.put("enabled", false);
            }
            return new ComponentStatus("UP", details, null);
        } catch (Exception e) { // Health probe: must never throw
            return new ComponentStatus("UP", Map.of("enabled", false), null);
        }
    }

    /**
     * 综合状态：任一核心组件 DOWN 则整体 DOWN
     */
    public String overallStatus(Map<String, ComponentStatus> components) {
        ComponentStatus db = components.get("database");
        ComponentStatus pgvector = components.get("pgvector");

        if (db != null && "DOWN".equals(db.status())) {
            return "DOWN";
        }
        if (pgvector != null && "DOWN".equals(pgvector.status())) {
            return "DEGRADED";
        }

        boolean hasSlow = components.values().stream()
                .anyMatch(c -> "SLOW".equals(c.status()));
        return hasSlow ? "DEGRADED" : "UP";
    }

    // ==================== 内部类 ====================

    /**
     * 组件状态
     *
     * @param status  状态：UP / DOWN / DEGRADED / SLOW
     * @param details 详细信息
     * @param error   错误消息（null 表示无错误）
     */
    public record ComponentStatus(String status, Map<String, Object> details, String error) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status);
            map.putAll(details);
            if (error != null) {
                map.put("error", error);
            }
            return map;
        }
    }
}
