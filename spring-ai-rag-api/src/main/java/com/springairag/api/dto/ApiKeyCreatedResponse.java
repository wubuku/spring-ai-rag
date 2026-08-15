package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Response returned immediately after creating a new API key.
 * The raw key is included ONLY here — it cannot be retrieved again.
 */
@Schema(description = "New API key created. The raw key is shown ONLY here — save it securely.")
public class ApiKeyCreatedResponse {

    @Schema(description = "Public key identifier", example = "rag_k_abc123def456")
    private String keyId;

    @Schema(description = "The raw API key — shown ONLY now. Store it securely; it cannot be retrieved again.", example = "rag_sk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
    private String rawKey;

    @Schema(description = "Human-readable name", example = "Production Server")
    private String name;

    @Schema(description = "Expiration timestamp (null if never expires)")
    private java.time.LocalDateTime expiresAt;

    @Schema(description = "Warning to remind users to save the key")
    private String warning;

    @Schema(description = "Deprecated compatibility field. Null means unrestricted.",
            example = "[1, 2]", deprecated = true)
    private List<Long> allowedCollectionIds;

    @Schema(description = "Stable Collection keys this key may access. Null means unrestricted.",
            example = "[\"customer-42:manual:v3\"]")
    private List<String> allowedCollectionKeys;

    public ApiKeyCreatedResponse() {
    }

    public ApiKeyCreatedResponse(String keyId, String rawKey, String name,
                                 java.time.LocalDateTime expiresAt) {
        this(keyId, rawKey, name, expiresAt, null);
    }

    public ApiKeyCreatedResponse(String keyId, String rawKey, String name,
                                 java.time.LocalDateTime expiresAt,
                                 List<Long> allowedCollectionIds) {
        this.keyId = keyId;
        this.rawKey = rawKey;
        this.name = name;
        this.expiresAt = expiresAt;
        this.allowedCollectionIds = allowedCollectionIds;
        this.warning = "Save this key now — it will not be shown again.";
    }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getRawKey() { return rawKey; }
    public void setRawKey(String rawKey) { this.rawKey = rawKey; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public java.time.LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(java.time.LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public String getWarning() { return warning; }
    public void setWarning(String warning) { this.warning = warning; }

    public List<Long> getAllowedCollectionIds() { return allowedCollectionIds; }
    public void setAllowedCollectionIds(List<Long> allowedCollectionIds) {
        this.allowedCollectionIds = allowedCollectionIds;
    }

    public List<String> getAllowedCollectionKeys() { return allowedCollectionKeys; }
    public void setAllowedCollectionKeys(List<String> allowedCollectionKeys) {
        this.allowedCollectionKeys = allowedCollectionKeys;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiKeyCreatedResponse that = (ApiKeyCreatedResponse) o;
        return Objects.equals(keyId, that.keyId) &&
                Objects.equals(rawKey, that.rawKey) &&
                Objects.equals(name, that.name) &&
                Objects.equals(expiresAt, that.expiresAt) &&
                Objects.equals(warning, that.warning) &&
                Objects.equals(allowedCollectionIds, that.allowedCollectionIds) &&
                Objects.equals(allowedCollectionKeys, that.allowedCollectionKeys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyId, rawKey, name, expiresAt, warning,
                allowedCollectionIds, allowedCollectionKeys);
    }

    @Override
    public String toString() {
        return "ApiKeyCreatedResponse{" +
                "keyId='" + keyId + '\'' +
                ", name='" + name + '\'' +
                ", expiresAt=" + expiresAt +
                ", allowedCollectionIds=" + allowedCollectionIds +
                ", allowedCollectionKeys=" + allowedCollectionKeys +
                ", warning='" + warning + '\'' +
                // rawKey intentionally excluded from toString (security)
                '}';
    }
}
