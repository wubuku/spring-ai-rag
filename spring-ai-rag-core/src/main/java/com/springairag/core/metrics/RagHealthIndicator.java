package com.springairag.core.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.Map;

/**
 * RAG service health indicator (enhanced — multi-component probe).
 *
 * <p>Integrates with Spring Boot Actuator, exposing RAG service health via `/actuator/health`.
 * Checks each component's independent status based on {@link ComponentHealthService}.
 *
 * <p>Health check response example:
 * <pre>
 * {
 *   "status": "UP",
 *   "components": {
 *     "ragService": {
 *       "status": "UP",
 *       "details": {
 *         "database": { "status": "UP", "latencyMs": 3 },
 *         "pgvector": { "status": "UP", "version": "0.7.4" },
 *         "tables":   { "status": "UP", "rag_documents": 150 },
 *         "cache":    { "status": "UP", "hitRate": "82.3%" }
 *       }
 *     }
 *   }
 * }
 * </pre>
 */
public class RagHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(RagHealthIndicator.class);

    private final ComponentHealthService componentHealth;
    private final RagMetricsService metricsService;

    public RagHealthIndicator(ComponentHealthService componentHealth,
                               RagMetricsService metricsService) {
        this.componentHealth = componentHealth;
        this.metricsService = metricsService;
    }

    @Override
    public Health health() {
        Map<String, ComponentHealthService.ComponentStatus> components =
                componentHealth.checkAll();

        String overallStatus = componentHealth.overallStatus(components);
        Health.Builder builder = "UP".equals(overallStatus) ? Health.up() : Health.down();

        // Detailed status for each component
        for (Map.Entry<String, ComponentHealthService.ComponentStatus> entry : components.entrySet()) {
            builder.withDetail(entry.getKey(), entry.getValue().toMap());
        }

        // Business metrics
        builder.withDetail("totalRequests", metricsService.getTotalRequests());
        builder.withDetail("successRate", String.format("%.1f%%", metricsService.getSuccessRate()));

        return builder.build();
    }
}
