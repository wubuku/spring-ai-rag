package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import com.springairag.api.validation.ValidCollectionKey;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Request to create a new API key.
 */
@Schema(description = "Request to create a new API key")
public class ApiKeyCreateRequest {

    @Schema(description = "Human-readable name for this API key", example = "Production Server")
    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must be at most 255 characters")
    private String name;

    @Schema(description = "Expiration date/time (ISO-8601). Required for root-managed keys and must be in the future.",
            example = "2027-01-01T00:00:00")
    private LocalDateTime expiresAt;

    @Schema(description = "Deprecated compatibility field. Use allowedCollectionKeys. Null means unrestricted.",
            example = "[1, 2]", deprecated = true)
    @Size(max = 100, message = "At most 100 collection IDs may be assigned to one API key")
    private List<@Positive(message = "Collection IDs must be positive") Long> allowedCollectionIds;

    @Schema(description = "Optional stable Collection keys this key may access. Null/empty = all collections.",
            example = "[\"customer-42:manual:v3\"]")
    @Size(max = 100, message = "At most 100 collection keys may be assigned to one API key")
    private List<@ValidCollectionKey String> allowedCollectionKeys;

    @Schema(description = "Optional per-principal requests-per-minute quota. Null uses the global default.",
            example = "120", minimum = "1", maximum = "1000000")
    @Positive(message = "requestsPerMinute must be positive")
    @Max(value = 1_000_000, message = "requestsPerMinute must be at most 1000000")
    private Integer requestsPerMinute;

    public ApiKeyCreateRequest() {
    }

    public ApiKeyCreateRequest(String name, LocalDateTime expiresAt) {
        this.name = name;
        this.expiresAt = expiresAt;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public List<Long> getAllowedCollectionIds() { return allowedCollectionIds; }
    public void setAllowedCollectionIds(List<Long> allowedCollectionIds) {
        this.allowedCollectionIds = allowedCollectionIds;
    }

    public List<String> getAllowedCollectionKeys() { return allowedCollectionKeys; }
    public void setAllowedCollectionKeys(List<String> allowedCollectionKeys) {
        this.allowedCollectionKeys = allowedCollectionKeys;
    }

    public Integer getRequestsPerMinute() { return requestsPerMinute; }
    public void setRequestsPerMinute(Integer requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiKeyCreateRequest that = (ApiKeyCreateRequest) o;
        return Objects.equals(name, that.name) &&
                Objects.equals(expiresAt, that.expiresAt) &&
                Objects.equals(allowedCollectionIds, that.allowedCollectionIds) &&
                Objects.equals(allowedCollectionKeys, that.allowedCollectionKeys) &&
                Objects.equals(requestsPerMinute, that.requestsPerMinute);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, expiresAt, allowedCollectionIds, allowedCollectionKeys,
                requestsPerMinute);
    }

    @Override
    public String toString() {
        return "ApiKeyCreateRequest{" +
                "name='" + name + '\'' +
                ", expiresAt=" + expiresAt +
                ", allowedCollectionIds=" + allowedCollectionIds +
                ", allowedCollectionKeys=" + allowedCollectionKeys +
                ", requestsPerMinute=" + requestsPerMinute +
                '}';
    }
}
