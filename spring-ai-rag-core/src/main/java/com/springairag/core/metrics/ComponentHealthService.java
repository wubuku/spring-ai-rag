package com.springairag.core.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Component-level health check service.
 *
 * <p>Independent of Spring Boot Actuator, can be used by REST controllers and Actuator metrics.
 * Each check method returns a standardized {@link ComponentStatus} containing status, latency, and optional error info.
 *
 * <h3>Check items</h3>
 * <ul>
 *   <li>database — PostgreSQL connection + query latency</li>
 *   <li>pgvector — vector extension availability</li>
 *   <li>tables — core table existence</li>
 *   <li>cache — cache statistics summary</li>
 * </ul>
 *
 * <p>Embedding model and LLM checks are optional (external API calls), executed only when explicitly enabled.
 */
@Service // Spring bean (also created by GeneralRagAutoConfiguration.componentHealthService)
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
     * Check all components and return ordered results.
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
     * Check database connection and latency.
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
                log.warn("Database response slow: {}ms (threshold {}ms)", latencyMs, DB_SLOW_THRESHOLD_MS);
            }

            return new ComponentStatus(status, details, null);
        } catch (Exception e) { // Health probe: must never throw
            log.error("Database health check failed: {}", e.getMessage());
            return new ComponentStatus("DOWN", Map.of(), e.getMessage());
        }
    }

    /**
     * Check if pgvector extension is available.
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
            log.debug("pgvector extension check failed: {}", e.getMessage());
            return new ComponentStatus("DOWN", Map.of(),
                    "pgvector extension not found: " + e.getMessage());
        }
    }

    /**
     * Check if core tables exist.
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
     * Check cache status.
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
     * Overall status: DOWN if any core component is DOWN.
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

    // ==================== Inner class ====================

    /**
     * Component status.
     *
     * @param status  status: UP / DOWN / DEGRADED / SLOW
     * @param details details
     * @param error   error message (null means no error)
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
