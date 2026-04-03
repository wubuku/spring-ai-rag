package com.springairag.core.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.Map;

/**
 * RAG 服务健康检查指示器（增强版 — 多组件探针）
 *
 * <p>集成 Spring Boot Actuator，通过 `/actuator/health` 端点暴露 RAG 服务健康状态。
 * 基于 {@link ComponentHealthService} 检查每个组件的独立状态。
 *
 * <p>健康检查结果示例：
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

        // 每个组件的详细状态
        for (Map.Entry<String, ComponentHealthService.ComponentStatus> entry : components.entrySet()) {
            builder.withDetail(entry.getKey(), entry.getValue().toMap());
        }

        // 业务指标
        builder.withDetail("totalRequests", metricsService.getTotalRequests());
        builder.withDetail("successRate", String.format("%.1f%%", metricsService.getSuccessRate()));

        return builder.build();
    }
}
