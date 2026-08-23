package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Stable managed API principal and current policy")
public class ApiPrincipalResponse {
    private String principalId;
    private String name;
    private String role;
    private List<String> allowedCollectionKeys;
    private LocalDateTime expiresAt;
    private Integer requestsPerMinute;
    private Long policyVersion;
    private String status;
    private LocalDateTime lastUsedAt;
    private String currentCredentialId;
    private Integer currentCredentialVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getPrincipalId() { return principalId; }
    public void setPrincipalId(String principalId) { this.principalId = principalId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public List<String> getAllowedCollectionKeys() { return allowedCollectionKeys; }
    public void setAllowedCollectionKeys(List<String> allowedCollectionKeys) { this.allowedCollectionKeys = allowedCollectionKeys; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public Integer getRequestsPerMinute() { return requestsPerMinute; }
    public void setRequestsPerMinute(Integer requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }
    public Long getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(Long policyVersion) { this.policyVersion = policyVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public String getCurrentCredentialId() { return currentCredentialId; }
    public void setCurrentCredentialId(String currentCredentialId) { this.currentCredentialId = currentCredentialId; }
    public Integer getCurrentCredentialVersion() { return currentCredentialVersion; }
    public void setCurrentCredentialVersion(Integer currentCredentialVersion) { this.currentCredentialVersion = currentCredentialVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
