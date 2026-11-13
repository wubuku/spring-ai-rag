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

    /** WP9：当前凭据绑定的租户（root / legacy-static 固定 default）。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "Tenant bound to the current credential", example = "default",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantId;

    /** WP9：当前运行模式（SINGLE / STRICT）；WebUI 只做只读提示。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "Current tenant runtime mode", example = "SINGLE",
            allowableValues = {"SINGLE", "STRICT"},
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantMode;

    /** WP9：绑定租户的状态（root / legacy-static 固定 ACTIVE）。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "Status of the bound tenant", example = "ACTIVE",
            allowableValues = {"ACTIVE", "SUSPENDED", "DISABLED"},
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantStatus;

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
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getTenantMode() { return tenantMode; }
    public void setTenantMode(String tenantMode) { this.tenantMode = tenantMode; }
    public String getTenantStatus() { return tenantStatus; }
    public void setTenantStatus(String tenantStatus) { this.tenantStatus = tenantStatus; }
}
