package com.springairag.core.security;

import com.springairag.core.entity.ApiKeyRole;

import java.time.LocalDateTime;

/**
 * API 调用方授权所需的只读策略视图。
 */
public interface ApiAccessPolicy {

    String getPrincipalId();

    String getCredentialId();

    ApiKeyRole getRole();

    String getAllowedCollectionIds();

    LocalDateTime getExpiresAt();

    default Integer getCredentialVersion() {
        return null;
    }

    default Long getPolicyVersion() {
        return null;
    }

    default Integer getRequestsPerMinute() {
        return null;
    }
}
