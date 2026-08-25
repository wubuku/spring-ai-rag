package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.springairag.api.enums.CollectionAccessMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 当前 API Key principal 及其控制台能力。
 */
@Schema(description = "Authenticated API principal and capabilities")
public class ApiKeyIdentityResponse {

    private String principalType;
    private String principalId;
    private boolean rootMode;
    private List<String> capabilities;
    private String credentialId;
    private Integer credentialVersion;
    private Long policyVersion;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(
            description = "Database principal role. Null for environment-root and legacy-static principals.",
            allowableValues = {"NORMAL", "ADMIN"},
            nullable = true,
            example = "NORMAL")
    private String principalRole;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(
            description = "Effective Collection access mode for the current principal.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private CollectionAccessMode collectionAccessMode;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(
            description = "Stable Collection keys allowed for a restricted principal. Null means unrestricted.",
            nullable = true,
            example = "[\"customer-42:records:v1\"]")
    private List<String> allowedCollectionKeys;

    public ApiKeyIdentityResponse() {
    }

    public ApiKeyIdentityResponse(String principalType, String principalId,
                                  boolean rootMode, List<String> capabilities) {
        this.principalType = principalType;
        this.principalId = principalId;
        this.rootMode = rootMode;
        this.capabilities = capabilities;
    }

    public String getPrincipalType() {
        return principalType;
    }

    public void setPrincipalType(String principalType) {
        this.principalType = principalType;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public void setPrincipalId(String principalId) {
        this.principalId = principalId;
    }

    public boolean isRootMode() {
        return rootMode;
    }

    public void setRootMode(boolean rootMode) {
        this.rootMode = rootMode;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(List<String> capabilities) {
        this.capabilities = capabilities;
    }

    public String getCredentialId() { return credentialId; }
    public void setCredentialId(String credentialId) { this.credentialId = credentialId; }
    public Integer getCredentialVersion() { return credentialVersion; }
    public void setCredentialVersion(Integer credentialVersion) { this.credentialVersion = credentialVersion; }
    public Long getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(Long policyVersion) { this.policyVersion = policyVersion; }
    public String getPrincipalRole() { return principalRole; }
    public void setPrincipalRole(String principalRole) { this.principalRole = principalRole; }
    public CollectionAccessMode getCollectionAccessMode() { return collectionAccessMode; }
    public void setCollectionAccessMode(CollectionAccessMode collectionAccessMode) {
        this.collectionAccessMode = collectionAccessMode;
    }
    public List<String> getAllowedCollectionKeys() { return allowedCollectionKeys; }
    public void setAllowedCollectionKeys(List<String> allowedCollectionKeys) {
        this.allowedCollectionKeys = allowedCollectionKeys;
    }
}
