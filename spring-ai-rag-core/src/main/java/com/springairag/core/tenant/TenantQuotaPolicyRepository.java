package com.springairag.core.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * WP10：租户级硬阈值策略读取（平台管理面写入，app 只读）。
 *
 * <p>表 {@code rag_tenant_quota_policy} 是 PLATFORM_GLOBAL（无 RLS），
 * 因此读取必须显式带 tenant_id 谓词，禁止无谓词全表查询。</p>
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
}
