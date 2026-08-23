package com.springairag.api.dto;

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
}
