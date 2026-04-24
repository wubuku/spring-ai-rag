package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * API Key metadata response (raw key is NOT included).
 */
@Schema(description = "API Key metadata (raw key is never returned after creation)")
public class ApiKeyResponse {

    @Schema(description = "Public key identifier", example = "rag_k_abc123def456")
    private String keyId;

    @Schema(description = "Human-readable name", example = "Production Server")
    private String name;

    @Schema(description = "Creation timestamp", example = "2026-04-12T03:50:00")
    private LocalDateTime createdAt;

    @Schema(description = "Last used timestamp (null if never used)", example = "2026-04-12T10:00:00")
    private LocalDateTime lastUsedAt;

    @Schema(description = "Expiration timestamp (null if never expires)", example = "2027-01-01T00:00:00")
    private LocalDateTime expiresAt;

    @Schema(description = "Whether the key is currently active", example = "true")
    private Boolean enabled;

    @Schema(description = "Role of this key (ADMIN or NORMAL). May be null when role is not available.", example = "ADMIN")
    private String role;

    public ApiKeyResponse() {
    }

    public ApiKeyResponse(String keyId, String name, LocalDateTime createdAt,
                          LocalDateTime lastUsedAt, LocalDateTime expiresAt, Boolean enabled) {
        this.keyId = keyId;
        this.name = name;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
        this.expiresAt = expiresAt;
        this.enabled = enabled;
    }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiKeyResponse that = (ApiKeyResponse) o;
        return Objects.equals(keyId, that.keyId) &&
                Objects.equals(name, that.name) &&
                Objects.equals(createdAt, that.createdAt) &&
                Objects.equals(lastUsedAt, that.lastUsedAt) &&
                Objects.equals(expiresAt, that.expiresAt) &&
                Objects.equals(enabled, that.enabled) &&
                Objects.equals(role, that.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyId, name, createdAt, lastUsedAt, expiresAt, enabled, role);
    }

    @Override
    public String toString() {
        return "ApiKeyResponse{" +
                "keyId='" + keyId + '\'' +
                ", name='" + name + '\'' +
                ", createdAt=" + createdAt +
                ", lastUsedAt=" + lastUsedAt +
                ", expiresAt=" + expiresAt +
                ", enabled=" + enabled +
                ", role='" + role + '\'' +
                '}';
    }
}
