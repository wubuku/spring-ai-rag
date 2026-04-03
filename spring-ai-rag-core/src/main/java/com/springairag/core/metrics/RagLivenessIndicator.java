package com.springairag.core.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RAG Liveness 健康探针（Kubernetes LivenessProbe 用）
 *
 * <p>仅检查核心基础设施（数据库连接），不检查外部依赖。
 * 如果数据库无响应，K8s 应重启容器。
 *
 * <p>对比：
 * <ul>
 *   <li>LivenessProbe — 数据库是否可达（快速失败）</li>
 *   <li>ReadinessProbe — 完整组件健康（流量调度）</li>
 * </ul>
 *
 * <p>访问路径：GET /actuator/health/liveness
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
