package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** 分阶段 credential 轮换状态；raw credential 只在首次 prepare 成功时返回。 */
@Schema(description = "Bounded staged API credential rotation state")
public class ApiKeyRotationResponse {

    private UUID rotationId;
    private String status;
    private String principalId;
    private String keyId;
    private Integer credentialVersion;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String rawKey;

    private Boolean secretAvailable;
    private Boolean idempotentReplay;
    private Boolean currentCredentialActive;
    private Boolean rotationPending;
    private String retiringCredentialId;
    private Integer retiringCredentialVersion;
    private LocalDateTime rotationExpiresAt;

    public UUID getRotationId() { return rotationId; }
    public void setRotationId(UUID rotationId) { this.rotationId = rotationId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPrincipalId() { return principalId; }
    public void setPrincipalId(String principalId) { this.principalId = principalId; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public Integer getCredentialVersion() { return credentialVersion; }
    public void setCredentialVersion(Integer credentialVersion) {
        this.credentialVersion = credentialVersion;
    }
    public String getRawKey() { return rawKey; }
    public void setRawKey(String rawKey) { this.rawKey = rawKey; }
    public Boolean getSecretAvailable() { return secretAvailable; }
    public void setSecretAvailable(Boolean secretAvailable) {
        this.secretAvailable = secretAvailable;
    }
    public Boolean getIdempotentReplay() { return idempotentReplay; }
    public void setIdempotentReplay(Boolean idempotentReplay) {
        this.idempotentReplay = idempotentReplay;
    }
    public Boolean getCurrentCredentialActive() { return currentCredentialActive; }
    public void setCurrentCredentialActive(Boolean currentCredentialActive) {
        this.currentCredentialActive = currentCredentialActive;
    }
    public Boolean getRotationPending() { return rotationPending; }
    public void setRotationPending(Boolean rotationPending) {
        this.rotationPending = rotationPending;
    }
    public String getRetiringCredentialId() { return retiringCredentialId; }
    public void setRetiringCredentialId(String retiringCredentialId) {
        this.retiringCredentialId = retiringCredentialId;
    }
    public Integer getRetiringCredentialVersion() { return retiringCredentialVersion; }
    public void setRetiringCredentialVersion(Integer retiringCredentialVersion) {
        this.retiringCredentialVersion = retiringCredentialVersion;
    }
    public LocalDateTime getRotationExpiresAt() { return rotationExpiresAt; }
    public void setRotationExpiresAt(LocalDateTime rotationExpiresAt) {
        this.rotationExpiresAt = rotationExpiresAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ApiKeyRotationResponse that)) return false;
        return Objects.equals(rotationId, that.rotationId)
                && Objects.equals(status, that.status)
                && Objects.equals(principalId, that.principalId)
                && Objects.equals(keyId, that.keyId)
                && Objects.equals(credentialVersion, that.credentialVersion)
                && Objects.equals(rawKey, that.rawKey)
                && Objects.equals(secretAvailable, that.secretAvailable)
                && Objects.equals(idempotentReplay, that.idempotentReplay)
                && Objects.equals(currentCredentialActive, that.currentCredentialActive)
                && Objects.equals(rotationPending, that.rotationPending)
                && Objects.equals(retiringCredentialId, that.retiringCredentialId)
                && Objects.equals(retiringCredentialVersion, that.retiringCredentialVersion)
                && Objects.equals(rotationExpiresAt, that.rotationExpiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rotationId, status, principalId, keyId,
                credentialVersion, rawKey, secretAvailable, idempotentReplay,
                currentCredentialActive, rotationPending, retiringCredentialId,
                retiringCredentialVersion, rotationExpiresAt);
    }

    @Override
    public String toString() {
        return "ApiKeyRotationResponse{" +
                "rotationId=" + rotationId +
                ", status='" + status + '\'' +
                ", principalId='" + principalId + '\'' +
                ", keyId='" + keyId + '\'' +
                ", credentialVersion=" + credentialVersion +
                ", secretAvailable=" + secretAvailable +
                ", idempotentReplay=" + idempotentReplay +
                ", currentCredentialActive=" + currentCredentialActive +
                ", rotationPending=" + rotationPending +
                ", retiringCredentialId='" + retiringCredentialId + '\'' +
                ", retiringCredentialVersion=" + retiringCredentialVersion +
                ", rotationExpiresAt=" + rotationExpiresAt +
                '}';
    }
}
