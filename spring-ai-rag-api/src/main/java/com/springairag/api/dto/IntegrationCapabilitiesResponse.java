package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.springairag.api.enums.CollectionAccessMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Versioned, low-sensitivity capability contract for programmatic RAG clients.
 */
@Schema(description = "Runtime integration capabilities visible to the authenticated caller")
public class IntegrationCapabilitiesResponse {

    private Protocol protocol;
    private Principal principal;
    private Features features;
    private Limits limits;

    public IntegrationCapabilitiesResponse() {
    }

    public IntegrationCapabilitiesResponse(Protocol protocol, Principal principal,
                                            Features features, Limits limits) {
        this.protocol = protocol;
        this.principal = principal;
        this.features = features;
        this.limits = limits;
    }

    public Protocol getProtocol() { return protocol; }
    public void setProtocol(Protocol protocol) { this.protocol = protocol; }
    public Principal getPrincipal() { return principal; }
    public void setPrincipal(Principal principal) { this.principal = principal; }
    public Features getFeatures() { return features; }
    public void setFeatures(Features features) { this.features = features; }
    public Limits getLimits() { return limits; }
    public void setLimits(Limits limits) { this.limits = limits; }

    public record Protocol(
            String name,
            String version,
            String apiVersion) {
    }

    @Schema(description = "Effective identity projection for this request")
    public static class Principal {
        private String principalType;
        private String principalRole;
        private List<String> capabilities;
        private CollectionAccessMode collectionAccessMode;

        @JsonInclude(JsonInclude.Include.ALWAYS)
        private List<String> allowedCollectionKeys;

        public Principal() {
        }

        public Principal(String principalType, String principalRole,
                         List<String> capabilities,
                         CollectionAccessMode collectionAccessMode,
                         List<String> allowedCollectionKeys) {
            this.principalType = principalType;
            this.principalRole = principalRole;
            this.capabilities = capabilities;
            this.collectionAccessMode = collectionAccessMode;
            this.allowedCollectionKeys = allowedCollectionKeys;
        }

        public String getPrincipalType() { return principalType; }
        public void setPrincipalType(String principalType) { this.principalType = principalType; }
        public String getPrincipalRole() { return principalRole; }
        public void setPrincipalRole(String principalRole) { this.principalRole = principalRole; }
        public List<String> getCapabilities() { return capabilities; }
        public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
        public CollectionAccessMode getCollectionAccessMode() { return collectionAccessMode; }
        public void setCollectionAccessMode(CollectionAccessMode collectionAccessMode) {
            this.collectionAccessMode = collectionAccessMode;
        }
        public List<String> getAllowedCollectionKeys() { return allowedCollectionKeys; }
        public void setAllowedCollectionKeys(List<String> allowedCollectionKeys) {
            this.allowedCollectionKeys = allowedCollectionKeys;
        }
    }

    public record Features(
            Provisioning provisioning,
            DataPlane dataPlane,
            OptionalFeatures optional) {
    }

    public record Provisioning(
            boolean idempotencyKey,
            boolean replayReturnsSecret,
            boolean rawCredentialShownOnce) {
    }

    public record DataPlane(
            boolean collectionKey,
            JsonRecords jsonRecords,
            Embedding embedding,
            boolean bindingPreflight) {
    }

    public record JsonRecords(
            boolean upsert,
            boolean search,
            boolean payloadContains,
            boolean revisionCas,
            boolean exactReplay,
            boolean tombstoneRestore) {
    }

    public record Embedding(
            boolean asyncPolicy,
            boolean readinessEndpoint) {
    }

    public record OptionalFeatures(
            boolean documentSyncRuns,
            boolean openAiCompatibility) {
    }

    public record Limits(
            int maxCollectionKeysPerPrincipal,
            int collectionKeyMaxLength,
            int sourceNamespaceMaxLength,
            int externalIdMaxLength,
            int sourceRevisionMaxLength) {
    }
}
