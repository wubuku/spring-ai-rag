package com.springairag.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 稳定 API principal 及其权威授权策略。 */
@Entity
@Table(name = "rag_api_principal")
public class RagApiPrincipal {

    @Id
    @Column(name = "principal_id", length = 64)
    private String principalId;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApiKeyRole role;

    @Column(name = "allowed_collection_ids", length = 2048)
    private String allowedCollectionIds;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "requests_per_minute")
    private Integer requestsPerMinute;

    @Column(name = "policy_version", nullable = false)
    private Long policyVersion;

    @Column(name = "next_credential_version", nullable = false)
    private Integer nextCredentialVersion;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getPrincipalId() { return principalId; }
    public void setPrincipalId(String principalId) { this.principalId = principalId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ApiKeyRole getRole() { return role; }
    public void setRole(ApiKeyRole role) { this.role = role; }
    public String getAllowedCollectionIds() { return allowedCollectionIds; }
    public void setAllowedCollectionIds(String allowedCollectionIds) { this.allowedCollectionIds = allowedCollectionIds; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public Integer getRequestsPerMinute() { return requestsPerMinute; }
    public void setRequestsPerMinute(Integer requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }
    public Long getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(Long policyVersion) { this.policyVersion = policyVersion; }
    public Integer getNextCredentialVersion() { return nextCredentialVersion; }
    public void setNextCredentialVersion(Integer nextCredentialVersion) { this.nextCredentialVersion = nextCredentialVersion; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
