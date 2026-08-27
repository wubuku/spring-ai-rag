package com.springairag.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.UUID;

/** 不含 raw secret 的 credential 轮换操作账本。 */
@Entity
@Table(
        name = "rag_api_key_rotation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rag_api_key_rotation_principal_idempotency",
                columnNames = {"principal_id", "idempotency_key_hash"}))
public class ApiKeyRotationOperation {

    @Id
    @Column(name = "rotation_id", nullable = false)
    private UUID rotationId;

    @Column(name = "principal_id", nullable = false, length = 64)
    private String principalId;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64)
    private String idempotencyKeyHash;

    @Column(name = "request_fingerprint_sha256", nullable = false, length = 64)
    private String requestFingerprintSha256;

    @Column(name = "source_credential_id", nullable = false, length = 64)
    private String sourceCredentialId;

    @Column(name = "target_credential_id", nullable = false, length = 64)
    private String targetCredentialId;

    @Column(name = "overlap_seconds", nullable = false)
    private Integer overlapSeconds;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApiKeyRotationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "terminal_at")
    private LocalDateTime terminalAt;

    public UUID getRotationId() { return rotationId; }
    public void setRotationId(UUID rotationId) { this.rotationId = rotationId; }
    public String getPrincipalId() { return principalId; }
    public void setPrincipalId(String principalId) { this.principalId = principalId; }
    public String getIdempotencyKeyHash() { return idempotencyKeyHash; }
    public void setIdempotencyKeyHash(String idempotencyKeyHash) {
        this.idempotencyKeyHash = idempotencyKeyHash;
    }
    public String getRequestFingerprintSha256() { return requestFingerprintSha256; }
    public void setRequestFingerprintSha256(String requestFingerprintSha256) {
        this.requestFingerprintSha256 = requestFingerprintSha256;
    }
    public String getSourceCredentialId() { return sourceCredentialId; }
    public void setSourceCredentialId(String sourceCredentialId) {
        this.sourceCredentialId = sourceCredentialId;
    }
    public String getTargetCredentialId() { return targetCredentialId; }
    public void setTargetCredentialId(String targetCredentialId) {
        this.targetCredentialId = targetCredentialId;
    }
    public Integer getOverlapSeconds() { return overlapSeconds; }
    public void setOverlapSeconds(Integer overlapSeconds) {
        this.overlapSeconds = overlapSeconds;
    }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public ApiKeyRotationStatus getStatus() { return status; }
    public void setStatus(ApiKeyRotationStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getTerminalAt() { return terminalAt; }
    public void setTerminalAt(LocalDateTime terminalAt) { this.terminalAt = terminalAt; }
}
