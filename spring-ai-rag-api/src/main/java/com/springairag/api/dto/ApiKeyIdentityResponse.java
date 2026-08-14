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
}
