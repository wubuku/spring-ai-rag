package com.springairag.core.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * WP10：租户级 DAY/MONTH 窗口账本（PLATFORM_GLOBAL，按 tenant_id 键控，无 RLS）。
 *
 * <p>与 per-principal 预算账本同事务接线：{@link #reserve} 用条件写入/CAS 保证并发下
 * 「reserved + settled + 本次 ≤ 硬阈值」；{@link #settle} 把预留转为实际用量，
 * {@link #release} 释放未使用预留。所有操作只用条件写入、版本列与唯一约束，不引入任何阻塞式协调。</p>
 */
@Service
public class TenantQuotaLedger {

    private final JdbcTemplate jdbcTemplate;

    public TenantQuotaLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 供同包协作方复用底层模板。 */
    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    /** 预留：超限返回 false，不抛异常（由调用方转成 429/402）。 */
    public boolean reserve(String tenantId, String windowKind, Instant windowStart,
                           long tokens, long limit) {
        validate(tenantId, windowKind, tokens, limit);
        // 窗口行补建用 ON CONFLICT DO NOTHING，幂等；两个窗口都要各自建立
        // （DAY 与 MONTH 的窗口行不同，缺任一行都会让后续 CAS 命中 0 行）。
        ensureWindow(tenantId, windowKind, windowStart);
        return jdbcTemplate.update(
                "UPDATE rag_tenant_quota_window SET reserved_tokens=reserved_tokens+?, "
                        + "version=version+1, updated_at=clock_timestamp() "
                        + "WHERE tenant_id=? AND window_kind=? AND window_start=? "
                        + "AND reserved_tokens + settled_tokens + ? <= ?",
                tokens, tenantId, windowKind, Timestamp.from(windowStart),
                tokens, limit) == 1;
    }

    /** 结算：释放 estimate，累加 actual；单次 update 保持 CAS 语义。 */
    public boolean settle(String tenantId, String windowKind, Instant windowStart,
                          long estimatedTokens, long actualTokens) {
        validate(tenantId, windowKind, actualTokens, Long.MAX_VALUE);
        return jdbcTemplate.update(
                "UPDATE rag_tenant_quota_window SET "
                        + "reserved_tokens=GREATEST(0, reserved_tokens-?), "
                        + "settled_tokens=settled_tokens+?, "
                        + "version=version+1, updated_at=clock_timestamp() "
                        + "WHERE tenant_id=? AND window_kind=? AND window_start=?",
                estimatedTokens, actualTokens, tenantId, windowKind,
                Timestamp.from(windowStart)) == 1;
    }

    /** 释放未使用预留（失败 / 取消 / TTL 过期）。 */
    public boolean release(String tenantId, String windowKind, Instant windowStart,
                           long estimatedTokens) {
        validate(tenantId, windowKind, estimatedTokens, Long.MAX_VALUE);
        return jdbcTemplate.update(
                "UPDATE rag_tenant_quota_window SET "
                        + "reserved_tokens=GREATEST(0, reserved_tokens-?), "
                        + "version=version+1, updated_at=clock_timestamp() "
                        + "WHERE tenant_id=? AND window_kind=? AND window_start=?",
                estimatedTokens, tenantId, windowKind, Timestamp.from(windowStart)) == 1;
    }

    private void ensureWindow(String tenantId, String windowKind, Instant windowStart) {
        jdbcTemplate.update(
                "INSERT INTO rag_tenant_quota_window "
                        + "(tenant_id, window_kind, window_start) VALUES (?, ?, ?) "
                        + "ON CONFLICT (tenant_id, window_kind, window_start) DO NOTHING",
                tenantId, windowKind, Timestamp.from(windowStart));
    }

    private static void validate(String tenantId, String windowKind,
                                 long tokens, long limit) {
        if (tenantId == null || tenantId.isBlank()
                || !tenantId.matches("^[a-z0-9][a-z0-9_-]{0,63}$")) {
            throw new IllegalArgumentException("tenantId must be a tenant slug");
        }
        if (!"DAY".equals(windowKind) && !"MONTH".equals(windowKind)) {
            throw new IllegalArgumentException("windowKind must be DAY or MONTH");
        }
        if (tokens <= 0 || limit <= 0) {
            throw new IllegalArgumentException("tokens and limit must be positive");
        }
    }
}
