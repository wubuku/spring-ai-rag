package com.springairag.core.security;

import com.springairag.core.entity.ApiKeyRole;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 一次认证请求使用的不可变 principal/policy 快照，不包含 raw credential 或 hash。
 */

/* WP5：无显式租户绑定时使用的兜底常量（见 10 参兼容构造器）。 */

public record AuthenticatedApiPrincipal(
        String principalId,
        String credentialId,
        int credentialVersion,
        String principalType,
        ApiKeyRole role,
        String allowedCollectionIds,
        LocalDateTime expiresAt,
        long policyVersion,
        Integer requestsPerMinute,
        List<String> capabilities,
        String tenantId,
        String tenantStatus) implements ApiAccessPolicy {

    /** 旧构造器兜底：default 租户。 */
    public static final String DEFAULT_TENANT_ID = "default";

    /** 旧构造器兜底状态：视为 ACTIVE。 */
    public static final String DEFAULT_TENANT_STATUS = "ACTIVE";

    public AuthenticatedApiPrincipal {
        Objects.requireNonNull(principalId, "principalId must not be null");
        Objects.requireNonNull(credentialId, "credentialId must not be null");
        Objects.requireNonNull(principalType, "principalType must not be null");
        Objects.requireNonNull(role, "role must not be null");
        if (credentialVersion <= 0 || policyVersion <= 0) {
            throw new IllegalArgumentException("Credential and policy versions must be positive");
        }
        capabilities = List.copyOf(Objects.requireNonNull(
                capabilities, "capabilities must not be null"));
    }

    /** Backward-compatible constructor for legacy extensions and tests. */
    public AuthenticatedApiPrincipal(
            String principalId,
            String credentialId,
            int credentialVersion,
            String principalType,
            ApiKeyRole role,
            String allowedCollectionIds,
            LocalDateTime expiresAt,
            long policyVersion,
            Integer requestsPerMinute) {
        this(principalId, credentialId, credentialVersion, principalType, role,
                allowedCollectionIds, expiresAt, policyVersion, requestsPerMinute,
                ApiCapabilitySupport.fullCapabilities());
    }

    /**
     * Backward-compatible constructor without tenant binding.
     *
     * <p>WP5 之前创建的 principal 一律视为 default 租户；多租户凭据必须走
     * {@link #AuthenticatedApiPrincipal(String, String, int, String, ApiKeyRole,
     * String, LocalDateTime, long, Integer, List, String, String)} 显式传入绑定租户，
     * 避免「默认落到 default」被误当成可信解析结果。</p>
     */
    public AuthenticatedApiPrincipal(
            String principalId,
            String credentialId,
            int credentialVersion,
            String principalType,
            ApiKeyRole role,
            String allowedCollectionIds,
            LocalDateTime expiresAt,
            long policyVersion,
            Integer requestsPerMinute,
            List<String> capabilities) {
        this(principalId, credentialId, credentialVersion, principalType, role,
                allowedCollectionIds, expiresAt, policyVersion, requestsPerMinute,
                capabilities, DEFAULT_TENANT_ID, DEFAULT_TENANT_STATUS);
    }

    @Override
    public String getPrincipalId() { return principalId; }

    @Override
    public String getCredentialId() { return credentialId; }

    @Override
    public Integer getCredentialVersion() { return credentialVersion; }

    @Override
    public ApiKeyRole getRole() { return role; }

    @Override
    public String getAllowedCollectionIds() { return allowedCollectionIds; }

    @Override
    public LocalDateTime getExpiresAt() { return expiresAt; }

    @Override
    public Long getPolicyVersion() { return policyVersion; }

    @Override
    public Integer getRequestsPerMinute() { return requestsPerMinute; }

    @Override
    public List<String> getCapabilities() { return capabilities; }

    /** WP5：凭据绑定的租户 id（凭据表无 RLS，由应用层校验绑定一致性）。 */
    public String getTenantId() { return tenantId; }

    /** WP5：租户状态（ACTIVE/SUSPENDED/DISABLED）。 */
    public String getTenantStatus() { return tenantStatus; }
}
