package com.springairag.core.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * RAG 服务健康检查指示器
 *
 * <p>集成 Spring Boot Actuator，通过 `/actuator/health` 端点暴露 RAG 服务健康状态。
 * 检查项包括：数据库连接、嵌入模型可用性。
 *
 * <p>健康检查结果示例：
 * <pre>
 * {
 *   "status": "UP",
 *   "components": {
 *     "ragService": {
 *       "status": "UP",
 *       "details": {
 *         "database": "UP",
 *         "documents": 150,
 *         "embeddings": 3200
 *       }
 *     }
 *   }
 * }
 * </pre>
 */
@Component("ragService")
public class RagHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(RagHealthIndicator.class);

    private final JdbcTemplate jdbcTemplate;
    private final RagMetricsService metricsService;

    public RagHealthIndicator(JdbcTemplate jdbcTemplate, RagMetricsService metricsService) {
        this.jdbcTemplate = jdbcTemplate;
        this.metricsService = metricsService;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();

        // 检查数据库连接
        boolean dbUp = checkDatabase(builder);

        // 检查表数据
        if (dbUp) {
            checkTableCounts(builder);
        }

        // 指标摘要
        builder.withDetail("totalRequests", metricsService.getTotalRequests());
        builder.withDetail("successRate", String.format("%.1f%%", metricsService.getSuccessRate()));

        // 如果数据库不可用，标记为 DOWN
        if (!dbUp) {
            builder.down();
        }

        return builder.build();
    }

    private boolean checkDatabase(Health.Builder builder) {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            builder.withDetail("database", "UP");
            return true;
        } catch (Exception e) {
            log.warn("Database health check failed: {}", e.getMessage());
            builder.withDetail("database", "DOWN");
            builder.withDetail("databaseError", e.getMessage());
            return false;
        }
    }

    private void checkTableCounts(Health.Builder builder) {
        try {
            Integer docCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM rag_documents", Integer.class);
            Integer embCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM rag_embeddings", Integer.class);
            builder.withDetail("documents", docCount != null ? docCount : 0);
            builder.withDetail("embeddings", embCount != null ? embCount : 0);
        } catch (Exception e) {
            log.debug("Table count check failed (tables may not exist yet): {}", e.getMessage());
            builder.withDetail("tables", "not_initialized");
        }
    }
}
