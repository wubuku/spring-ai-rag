package com.springairag.core.security;

import com.springairag.core.entity.ApiKeyRole;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 一次认证请求使用的不可变 principal/policy 快照，不包含 raw credential 或 hash。
 */
public record AuthenticatedApiPrincipal(
        String principalId,
        String credentialId,
        int credentialVersion,
        String principalType,
        ApiKeyRole role,
        String allowedCollectionIds,
        LocalDateTime expiresAt,
        long policyVersion,
        Integer requestsPerMinute) implements ApiAccessPolicy {

    public AuthenticatedApiPrincipal {
        Objects.requireNonNull(principalId, "principalId must not be null");
        Objects.requireNonNull(credentialId, "credentialId must not be null");
        Objects.requireNonNull(principalType, "principalType must not be null");
        Objects.requireNonNull(role, "role must not be null");
        if (credentialVersion <= 0 || policyVersion <= 0) {
            throw new IllegalArgumentException("Credential and policy versions must be positive");
        }
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
}
