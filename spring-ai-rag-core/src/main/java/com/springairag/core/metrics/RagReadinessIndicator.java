package com.springairag.core.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.Map;

/**
 * RAG Readiness 健康探针（Kubernetes ReadinessProbe 用）
 *
 * <p>检查完整组件健康状态：数据库 + pgvector + 表结构 + 缓存。
 * 用于控制是否将流量路由到本 Pod（Ready = true 时才接收请求）。
 *
 * <p>访问路径：GET /actuator/health/readiness
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
