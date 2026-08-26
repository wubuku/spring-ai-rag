package com.springairag.api.dto;

import com.springairag.api.validation.ValidCollectionKey;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

public class ApiPrincipalPolicyUpdateRequest {
    @NotNull
    @Positive
    private Long expectedPolicyVersion;

    @NotBlank
    @Size(max = 255)
    private String name;

    private LocalDateTime expiresAt;

    @Size(max = 100)
    private List<@ValidCollectionKey String> allowedCollectionKeys;

    @Positive
    @Max(1_000_000)
    private Integer requestsPerMinute;

    @Schema(description = "Operation capabilities. Null keeps the current principal capabilities.",
            example = "[\"RAG_READ\"]",
            allowableValues = {"RAG_READ", "RAG_WRITE"})
    @Size(max = 2)
    private List<String> capabilities;

    public Long getExpectedPolicyVersion() { return expectedPolicyVersion; }
    public void setExpectedPolicyVersion(Long expectedPolicyVersion) { this.expectedPolicyVersion = expectedPolicyVersion; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public List<String> getAllowedCollectionKeys() { return allowedCollectionKeys; }
    public void setAllowedCollectionKeys(List<String> allowedCollectionKeys) { this.allowedCollectionKeys = allowedCollectionKeys; }
    public Integer getRequestsPerMinute() { return requestsPerMinute; }
    public void setRequestsPerMinute(Integer requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }
    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
}
