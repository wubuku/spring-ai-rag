package com.springairag.core.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * WP10 + Operator Console：租户级硬阈值策略仓储（平台管理面读写，app 只读）。
 *
 * <p>表 {@code rag_tenant_quota_policy} 是 PLATFORM_GLOBAL（无 RLS），
 * app 路径只调用带 {@code tenant_id} 谓词的 {@link #load(String)}；
 * 运营方控制台管理端点通过 {@link #listAll()} 与 {@link #upsert(String, boolean, long, long)} 维护策略。</p>
 */
@Repository
public class TenantQuotaPolicyRepository {

    private final JdbcTemplate jdbcTemplate;

    public TenantQuotaPolicyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 读取策略；未配置视为 disabled（不施加租户级硬阈值）。 */
    public TenantQuotaPolicy load(String tenantId) {
        List<TenantQuotaPolicy> rows = jdbcTemplate.query(
                "SELECT tenant_id, enabled, daily_token_limit, monthly_token_limit "
                        + "FROM rag_tenant_quota_policy WHERE tenant_id = ?",
                (rs, rowNum) -> new TenantQuotaPolicy(
                        rs.getString("tenant_id"),
                        rs.getBoolean("enabled"),
                        rs.getLong("daily_token_limit"),
                        rs.getLong("monthly_token_limit")),
                tenantId);
        return rows.isEmpty()
                ? TenantQuotaPolicy.disabled(tenantId)
                : rows.getFirst();
    }

    /** 平台管理面：列出全部已配置配额策略。 */
    public List<TenantQuotaPolicy> listAll() {
        return jdbcTemplate.query(
                "SELECT tenant_id, enabled, daily_token_limit, monthly_token_limit "
                        + "FROM rag_tenant_quota_policy ORDER BY tenant_id ASC",
                (rs, rowNum) -> new TenantQuotaPolicy(
                        rs.getString("tenant_id"),
                        rs.getBoolean("enabled"),
                        rs.getLong("daily_token_limit"),
                        rs.getLong("monthly_token_limit")));
    }

    /** 平台管理面：创建或更新租户配额策略（ON CONFLICT 幂等）。 */
    public TenantQuotaPolicy upsert(String tenantId,
                                    boolean enabled,
                                    long dailyTokenLimit,
                                    long monthlyTokenLimit) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (dailyTokenLimit < 0 || monthlyTokenLimit < 0) {
            throw new IllegalArgumentException("token limits must be non-negative");
        }
        jdbcTemplate.update("""
                INSERT INTO rag_tenant_quota_policy
                    (tenant_id, enabled, daily_token_limit, monthly_token_limit, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    enabled = EXCLUDED.enabled,
                    daily_token_limit = EXCLUDED.daily_token_limit,
                    monthly_token_limit = EXCLUDED.monthly_token_limit,
                    updated_at = CURRENT_TIMESTAMP
                """,
                tenantId, enabled, dailyTokenLimit, monthlyTokenLimit);
        return new TenantQuotaPolicy(tenantId, enabled, dailyTokenLimit, monthlyTokenLimit);
    }
}
