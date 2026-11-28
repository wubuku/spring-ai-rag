package com.springairag.core.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * P1-3 修复 / V86：持久化平台审计实现（写入 {@code rag_platform_audit_event}）。
 *
 * <p>默认同时输出 SLF4J 结构化日志并落库，供运营方管理控制台按单调 {@code seq} 倒序查询。
 * 若在未执行 V86 迁移的轻量测试库中落库失败，记录告警并降级为日志，不破坏旧测试兼容性。</p>
 */
public class JdbcTenantLifecycleAudit implements TenantLifecycleAudit {

    private static final Logger log = LoggerFactory.getLogger(JdbcTenantLifecycleAudit.class);

    private final JdbcTemplate jdbcTemplate;

    public JdbcTenantLifecycleAudit(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(String action, String tenantId, String actorId, String detail) {
        String normalizedAction = normalizeLifecycleAction(action);
        String outcome = "REJECT".equalsIgnoreCase(action) ? "DENIED" : "SUCCESS";
        recordEvent(actorId, "ENVIRONMENT_ROOT", normalizedAction, tenantId, tenantId, detail, outcome);
    }

    public void recordEvent(String actor,
                            String actorType,
                            String action,
                            String targetTenantId,
                            String targetResourceId,
                            String reason,
                            String outcome) {
        String safeActor = actor == null || actor.isBlank() ? "unknown" : actor;
        String safeActorType = actorType == null || actorType.isBlank() ? "ENVIRONMENT_ROOT" : actorType;
        String safeAction = action == null || action.isBlank() ? "UNKNOWN" : action;
        String safeOutcome = outcome == null || outcome.isBlank() ? "SUCCESS" : outcome;
        log.info("platform audit action={} tenant={} resource={} actor={} outcome={} reason={}",
                safeAction, targetTenantId, targetResourceId, safeActor, safeOutcome, reason);
        try {
            jdbcTemplate.update("""
                    INSERT INTO rag_platform_audit_event
                        (event_id, actor, actor_type, action, target_tenant_id, target_resource_id, reason, outcome)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    safeActor,
                    safeActorType,
                    safeAction,
                    targetTenantId,
                    targetResourceId,
                    reason == null ? "" : reason,
                    safeOutcome);
        } catch (DataAccessException ex) {
            log.warn("Failed to persist rag_platform_audit_event (falling back to log only): {}",
                    ex.getMessage());
        }
    }

    public List<AuditEventRow> listEvents(String tenantId, String action, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        StringBuilder sql = new StringBuilder("""
                SELECT seq, event_id, occurred_at, actor, actor_type, action,
                       target_tenant_id, target_resource_id, reason, outcome
                FROM rag_platform_audit_event
                WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        if (tenantId != null && !tenantId.isBlank()) {
            sql.append(" AND target_tenant_id = ?");
            params.add(tenantId.trim());
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            params.add(action.trim());
        }
        sql.append(" ORDER BY seq DESC LIMIT ?");
        params.add(safeLimit);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Timestamp ts = rs.getTimestamp("occurred_at");
            return new AuditEventRow(
                    rs.getLong("seq"),
                    rs.getString("event_id"),
                    ts == null ? Instant.now().toString() : ts.toInstant().toString(),
                    rs.getString("actor"),
                    rs.getString("actor_type"),
                    rs.getString("action"),
                    rs.getString("target_tenant_id"),
                    rs.getString("target_resource_id"),
                    rs.getString("reason"),
                    rs.getString("outcome"));
        }, params.toArray());
    }

    private static String normalizeLifecycleAction(String action) {
        if (action == null) {
            return "TENANT_UPDATE";
        }
        return switch (action.toUpperCase()) {
            case "CREATE" -> "TENANT_CREATE";
            case "UPDATE" -> "TENANT_UPDATE";
            case "SUSPEND" -> "TENANT_SUSPEND";
            case "RESUME" -> "TENANT_RESUME";
            case "DISABLE" -> "TENANT_DISABLE";
            case "DELETE" -> "TENANT_SOFT_DELETE";
            case "REJECT" -> "TENANT_REJECT";
            default -> action.toUpperCase();
        };
    }

    public record AuditEventRow(
            long seq,
            String eventId,
            String occurredAt,
            String actor,
            String actorType,
            String action,
            String targetTenantId,
            String targetResourceId,
            String reason,
            String outcome) {
    }
}
