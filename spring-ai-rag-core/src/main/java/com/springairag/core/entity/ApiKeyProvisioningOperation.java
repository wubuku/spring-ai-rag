package com.springairag.core.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Successful API key provisioning result metadata.
 */
@Entity
@Table(name = "rag_api_provisioning_operation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rag_api_provisioning_owner_key",
                columnNames = {"owner_id", "idempotency_key_hash"}))
public class ApiKeyProvisioningOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64)
    private String idempotencyKeyHash;

    @Column(name = "request_fingerprint_sha256", nullable = false, length = 64)
    private String requestFingerprintSha256;

    @Column(name = "principal_id", nullable = false, length = 64)
    private String principalId;

    @Column(name = "credential_id", nullable = false, length = 64)
    private String credentialId;

    @Column(name = "credential_version", nullable = false)
    private Integer credentialVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at", nullable = false)
    private LocalDateTime completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getIdempotencyKeyHash() { return idempotencyKeyHash; }
    public void setIdempotencyKeyHash(String value) { idempotencyKeyHash = value; }
    public String getRequestFingerprintSha256() { return requestFingerprintSha256; }
    public void setRequestFingerprintSha256(String value) { requestFingerprintSha256 = value; }
    public String getPrincipalId() { return principalId; }
    public void setPrincipalId(String value) { principalId = value; }
    public String getCredentialId() { return credentialId; }
    public void setCredentialId(String value) { credentialId = value; }
    public Integer getCredentialVersion() { return credentialVersion; }
    public void setCredentialVersion(Integer value) { credentialVersion = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime value) { completedAt = value; }
}
