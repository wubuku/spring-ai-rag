package com.springairag.core.tenant;

import com.springairag.core.security.ApiKeyCollectionAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * WP8：SSE 投递点的「当前」Collection ACL 重查器（实施规划 §5.5）。
 *
 * <p>绝不复用建连时的 ApiAccessPolicy 快照：每次投递都实时读取
 * {@code rag_api_principal} 的 role / allowed_collection_ids / revoked_at /
 * expires_at 与活动凭据状态。撤权、scope 收窄、principal 注销都会让下一次
 * 投递返回 false；订阅是 request-scoped 短流，不维护长期注册表。</p>
 *
 * <p>凭据表按 WP1 裁决属于 CREDENTIAL（无 RLS，认证先于租户解析），因此本查询
 * 显式按 tenant_id + principal_id 过滤；{@link TenantAuthorizedSsePublisher} 在调用
 * 本组件前已用 {@link TenantContext} 做 fail-closed 和事件归属比对。</p>
 */
@Component
public class TenantCurrentAclSseChecker implements TenantSseAccessChecker {

    private final JdbcTemplate jdbcTemplate;

    public TenantCurrentAclSseChecker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public boolean canRead(String tenantId, String actorId, String collectionId) {
        if (tenantId == null || actorId == null || collectionId == null) {
            return false;
        }
        // 每次投递重新按当前 principal 状态 + 当前凭据状态 + 当前 allow-list 判定。
        List<CurrentAcl> rows = jdbcTemplate.query("""
                SELECT p.role, p.allowed_collection_ids, p.revoked_at, p.expires_at
                FROM rag_api_principal p
                JOIN rag_api_key k ON k.principal_id = p.principal_id
                WHERE p.tenant_id = ?
                  AND p.principal_id = ?
                  AND k.tenant_id = ?
                  AND k.enabled = TRUE
                  AND k.retire_at IS NULL
                  AND k.revoked_at IS NULL
                """,
                (rs, rowNum) -> new CurrentAcl(
                        rs.getString("role"),
                        rs.getString("allowed_collection_ids"),
                        rs.getObject("revoked_at", java.time.LocalDateTime.class),
                        rs.getObject("expires_at", java.time.LocalDateTime.class)),
                tenantId, actorId, tenantId);
        if (rows.isEmpty()) {
            return false;
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        long collectionNumericId;
        try {
            collectionNumericId = Long.parseLong(collectionId);
        } catch (NumberFormatException badCollection) {
            return false;
        }
        for (CurrentAcl acl : rows) {
            if (acl.revokedAt() != null
                    || (acl.expiresAt() != null && !acl.expiresAt().isAfter(now))) {
                continue;
            }
            if ("ADMIN".equalsIgnoreCase(acl.role())) {
                return true;
            }
            String raw = acl.allowedCollectionIds();
            if (raw == null || raw.isBlank()) {
                return true;
            }
            List<Long> allowed = ApiKeyCollectionAccess.parseAllowedIds(raw);
            if (allowed.contains(collectionNumericId)) {
                return true;
            }
        }
        return false;
    }

    /** 当期凭据联表投影；不含 raw credential / hash。 */
    private record CurrentAcl(String role, String allowedCollectionIds,
                              java.time.LocalDateTime revokedAt,
                              java.time.LocalDateTime expiresAt) {
    }
}
