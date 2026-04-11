package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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

    public ApiKeyCreatedResponse() {
    }

    public ApiKeyCreatedResponse(String keyId, String rawKey, String name,
                                 java.time.LocalDateTime expiresAt) {
        this.keyId = keyId;
        this.rawKey = rawKey;
        this.name = name;
        this.expiresAt = expiresAt;
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
}
