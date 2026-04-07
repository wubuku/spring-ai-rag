package com.springairag.core.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RAG Liveness health probe (for Kubernetes LivenessProbe).
 *
 * <p>Checks only core infrastructure (database connection), not external dependencies.
 * If the database is unresponsive, K8s should restart the container.
 *
 * <p>Comparison:
 * <ul>
 *   <li>LivenessProbe — is database reachable (fast failure)</li>
 *   <li>ReadinessProbe — full component health (traffic routing)</li>
 * </ul>
 *
 * <p>Access path: GET /actuator/health/liveness
 */
public class RagLivenessIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(RagLivenessIndicator.class);

    private final JdbcTemplate jdbcTemplate;

    public RagLivenessIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            long start = System.currentTimeMillis();
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            long latencyMs = System.currentTimeMillis() - start;

            return Health.up()
                    .withDetail("database", "reachable")
                    .withDetail("latencyMs", latencyMs)
                    .build();
        } catch (Exception e) { // Health probe: must never throw, degrade gracefully
            log.error("Liveness probe failed: database unreachable - {}", e.getMessage());
            return Health.down()
                    .withDetail("database", "unreachable")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
