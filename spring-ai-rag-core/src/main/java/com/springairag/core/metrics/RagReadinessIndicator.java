package com.springairag.core.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.Map;

/**
 * RAG Readiness health probe (for Kubernetes ReadinessProbe).
 *
 * <p>Checks full component health: database + pgvector + table structure + cache.
 * Controls whether traffic is routed to this Pod (accepts requests only when Ready = true).
 *
 * <p>Access path: GET /actuator/health/readiness
 */
public class RagReadinessIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(RagReadinessIndicator.class);

    private final ComponentHealthService componentHealth;
    private final RagMetricsService metricsService;

    public RagReadinessIndicator(ComponentHealthService componentHealth,
                                  RagMetricsService metricsService) {
        this.componentHealth = componentHealth;
        this.metricsService = metricsService;
    }

    @Override
    public Health health() {
        Map<String, ComponentHealthService.ComponentStatus> components =
                componentHealth.checkAll();

        String overallStatus = componentHealth.overallStatus(components);
        Health.Builder builder = "UP".equals(overallStatus) ? Health.up()
                : "DEGRADED".equals(overallStatus) ? Health.up()  // DEGRADED still accepts traffic
                : Health.down();

        for (Map.Entry<String, ComponentHealthService.ComponentStatus> entry : components.entrySet()) {
            builder.withDetail(entry.getKey(), entry.getValue().toMap());
        }

        builder.withDetail("totalRequests", metricsService.getTotalRequests());
        builder.withDetail("successRate", String.format("%.1f%%", metricsService.getSuccessRate()));

        return builder.build();
    }
}
