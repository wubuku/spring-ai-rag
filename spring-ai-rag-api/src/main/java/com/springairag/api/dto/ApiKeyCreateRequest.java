package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
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

    @Schema(description = "Optional expiration date/time (ISO-8601). Null means never expires.", example = "2027-01-01T00:00:00")
    private LocalDateTime expiresAt;

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiKeyCreateRequest that = (ApiKeyCreateRequest) o;
        return Objects.equals(name, that.name) &&
                Objects.equals(expiresAt, that.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, expiresAt);
    }

    @Override
    public String toString() {
        return "ApiKeyCreateRequest{" +
                "name='" + name + '\'' +
                ", expiresAt=" + expiresAt +
                '}';
    }
}
