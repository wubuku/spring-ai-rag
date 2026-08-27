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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Features(
            Provisioning provisioning,
            DataPlane dataPlane,
            OptionalFeatures optional,
            CredentialRotation credentialRotation) {

        public Features(
                Provisioning provisioning,
                DataPlane dataPlane,
                OptionalFeatures optional) {
            this(provisioning, dataPlane, optional, null);
        }
    }

    public record CredentialRotation(
            boolean immediate,
            boolean staged,
            boolean cancel,
            boolean idempotencyKeyRequired,
            int defaultOverlapSeconds,
            int maxOverlapSeconds,
            boolean replayReturnsSecret,
            int operationRetentionDays) {
    }

    public record Provisioning(
            boolean idempotencyKey,
            boolean replayReturnsSecret,
            boolean rawCredentialShownOnce,
            boolean collectionCreateIdempotencyKey) {

        public Provisioning(
                boolean idempotencyKey,
                boolean replayReturnsSecret,
                boolean rawCredentialShownOnce) {
            this(idempotencyKey, replayReturnsSecret, rawCredentialShownOnce, false);
        }
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
            boolean documentSyncRunItemReceipts,
            boolean openAiCompatibility,
            boolean integrationObservability,
            boolean collectionPurge) {

        public OptionalFeatures(
                boolean documentSyncRuns,
                boolean documentSyncRunItemReceipts,
                boolean openAiCompatibility) {
            this(documentSyncRuns, documentSyncRunItemReceipts,
                    openAiCompatibility, false, false);
        }

        public OptionalFeatures(
                boolean documentSyncRuns,
                boolean openAiCompatibility) {
            this(documentSyncRuns, documentSyncRuns,
                    openAiCompatibility, false, false);
        }

        public OptionalFeatures(
                boolean documentSyncRuns,
                boolean documentSyncRunItemReceipts,
                boolean openAiCompatibility,
                boolean integrationObservability) {
            this(documentSyncRuns, documentSyncRunItemReceipts,
                    openAiCompatibility, integrationObservability, false);
        }
    }

    public record Limits(
            int maxCollectionKeysPerPrincipal,
            int collectionKeyMaxLength,
            int sourceNamespaceMaxLength,
            int externalIdMaxLength,
            int sourceRevisionMaxLength,
            StructuredRecordsLimits structuredRecords,
            SyncRunsLimits syncRuns,
            ObservabilityLimits observability,
            CollectionPurgeLimits collectionPurge) {

        public Limits(
                int maxCollectionKeysPerPrincipal,
                int collectionKeyMaxLength,
                int sourceNamespaceMaxLength,
                int externalIdMaxLength,
                int sourceRevisionMaxLength) {
            this(maxCollectionKeysPerPrincipal, collectionKeyMaxLength,
                    sourceNamespaceMaxLength, externalIdMaxLength,
                    sourceRevisionMaxLength, null, null, null, null);
        }
    }

    public record StructuredRecordsLimits(
            int maxJsonbPayloadBytes,
            int maxRetrievalTextChars,
            int maxBatchItems,
            int maxBatchPayloadBytes,
            int maxSearchResults,
            int maxPayloadFilterBytes,
            int maxPayloadFilterDepth) {
    }

    public record SyncRunsLimits(
            int maxBatchItems,
            int maxItemReceiptPageItems,
            int maxRunListPageItems) {
    }

    public record ObservabilityLimits(
            int retentionDays,
            int maxQueryRangeDays,
            int maxCollectionBreakdownItems) {
    }

    public record CollectionPurgeLimits(
            int maxDocuments,
            int maxEmbeddings,
            int maxVersions,
            int maxDerivedRows,
            int maxAffectedChatSessions,
            int maxChatRows) {
    }
}
