package com.springairag.core.service;

import com.springairag.api.contract.DocumentSyncRunLimits;
import com.springairag.api.dto.IntegrationCapabilitiesResponse;
import com.springairag.api.enums.CollectionAccessMode;
import com.springairag.api.enums.FailureClass;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagRetryProperties;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Builds the stable, low-sensitivity runtime integration contract.
 */
@Component
public class IntegrationCapabilityCatalog {

    private static final String CONTRACT_NAME = "spring-ai-rag-integration";
    private static final String CONTRACT_VERSION = "1.1";
    private static final String API_VERSION = "1.0.0";

    private final RagProperties properties;
    private final CollectionIdentityResolver collectionIdentityResolver;
    private final RagRetryProperties retryProperties;

    @Autowired
    public IntegrationCapabilityCatalog(
            RagProperties properties,
            CollectionIdentityResolver collectionIdentityResolver,
            RagRetryProperties retryProperties) {
        this.properties = properties;
        this.collectionIdentityResolver = collectionIdentityResolver;
        this.retryProperties = retryProperties;
    }

    /** Backward-compatible constructor for extensions and focused unit tests. */
    public IntegrationCapabilityCatalog(
            RagProperties properties,
            CollectionIdentityResolver collectionIdentityResolver) {
        this(properties, collectionIdentityResolver, new RagRetryProperties());
    }

    public IntegrationCapabilitiesResponse describe(HttpServletRequest request) {
        IntegrationCapabilitiesResponse.Principal principal =
                principalProjection(request);
        IntegrationCapabilitiesResponse.Features features =
                new IntegrationCapabilitiesResponse.Features(
                        new IntegrationCapabilitiesResponse.Provisioning(
                                properties.getApiKeyProvisioning().isEnabled(),
                                false,
                                true,
                                properties.getCollectionProvisioning().isEnabled()),
                        new IntegrationCapabilitiesResponse.DataPlane(
                                true,
                                new IntegrationCapabilitiesResponse.JsonRecords(
                                        true, true, true, true, true, true),
                                new IntegrationCapabilitiesResponse.Embedding(
                                        properties.getEmbeddingJobs().isEnabled(),
                                        true),
                                true),
                        new IntegrationCapabilitiesResponse.OptionalFeatures(
                                properties.getDocumentLifecycle().isSyncRunsEnabled(),
                                properties.getDocumentLifecycle().isSyncRunsEnabled(),
                                properties.getOpenAiCompatibility().isEnabled(),
                                properties.getIntegrationObservability().isEnabled(),
                                collectionPurgeVisible(request),
                                true,
                                true),
                        new IntegrationCapabilitiesResponse.CredentialRotation(
                                true,
                                true,
                                true,
                                true,
                                properties.getApiKeyRotation()
                                        .defaultOverlapSeconds(),
                                properties.getApiKeyRotation()
                                        .maxOverlapSeconds(),
                                false,
                                properties.getApiKeyRotation()
                                        .operationRetentionDays()));
        var structuredRecords = properties.getStructuredRecords();
        var observability = properties.getIntegrationObservability();
        return new IntegrationCapabilitiesResponse(
                new IntegrationCapabilitiesResponse.Protocol(
                        CONTRACT_NAME, CONTRACT_VERSION, API_VERSION),
                principal,
                features,
                new IntegrationCapabilitiesResponse.Limits(
                        100,
                        128,
                        128,
                        255,
                        255,
                        new IntegrationCapabilitiesResponse.StructuredRecordsLimits(
                                structuredRecords.getMaxJsonbPayloadBytes(),
                                structuredRecords.getMaxRetrievalTextChars(),
                                structuredRecords.getMaxBatchSize(),
                                structuredRecords.getMaxBatchPayloadBytes(),
                                structuredRecords.getMaxSearchResults(),
                                structuredRecords.getMaxPayloadFilterBytes(),
                                structuredRecords.getMaxPayloadFilterDepth()),
                        new IntegrationCapabilitiesResponse.SyncRunsLimits(
                                DocumentSyncRunLimits.MAX_BATCH_ITEMS,
                                DocumentSyncRunLimits.MAX_ITEM_RECEIPT_PAGE_ITEMS,
                                DocumentSyncRunLimits.MAX_RUN_LIST_PAGE_ITEMS),
                        new IntegrationCapabilitiesResponse.ObservabilityLimits(
                                observability.retentionDays(),
                                observability.maxQueryRangeDays(),
                                observability.getMaxCollectionBreakdownItems()),
                        new IntegrationCapabilitiesResponse.CollectionPurgeLimits(
                                properties.getCollectionPurge().getMaxDocuments(),
                                properties.getCollectionPurge().getMaxEmbeddings(),
                                properties.getCollectionPurge().getMaxVersions(),
                                properties.getCollectionPurge().getMaxDerivedRows(),
                                properties.getCollectionPurge()
                                        .getMaxAffectedChatSessions(),
                                properties.getCollectionPurge().getMaxChatRows())),
                new IntegrationCapabilitiesResponse.Reliability(
                        List.of(FailureClass.CLIENT_REQUEST.name(),
                                FailureClass.AUTHENTICATION.name(),
                                FailureClass.AUTHORIZATION.name(),
                                FailureClass.NOT_FOUND.name(),
                                FailureClass.CONFLICT.name(),
                                FailureClass.RATE_LIMITED.name(),
                                FailureClass.DEPENDENCY_UNAVAILABLE.name(),
                                FailureClass.TIMEOUT.name(),
                                FailureClass.INTERNAL.name()),
                        "X-Trace-Id",
                        "X-RAG-Failure-Class",
                        List.of(429),
                        List.of("CHAT_TURN_IN_PROGRESS"),
                        true,
                        false,
                        properties.getCircuitBreaker().isEnabled(),
                        properties.getTimeout().getChatAskMs() > 0,
                        retryProperties.isEnabled(),
                        retryProperties.getMaxAttempts(),
                        properties.getTimeout().getChatAskMs()),
                new IntegrationCapabilitiesResponse.Governance(
                        "/api/v1/rag/data-governance/policy",
                        "/api/v1/rag/audit-logs",
                        "sensitive-data-masking-v1",
                        properties.getDataGovernance().isEnabled()
                                && properties.getDataGovernance().isCleanupEnabled(),
                        isManagementPrincipal(request),
                        properties.getDataGovernance().getAuditLogRetentionDays(),
                        properties.getDataGovernance().getClientErrorRetentionDays(),
                        properties.getDataGovernance().getEvaluationRetentionDays(),
                        properties.getDataGovernance().getCleanupBatchSize(),
                        properties.getDataGovernance().getCleanupMaxBatches()));
    }

    private boolean isManagementPrincipal(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        Object type = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        if (ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(type)
                || Boolean.TRUE.equals(request.getAttribute(
                ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE))) {
            return true;
        }
        return ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(type)
                && request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE)
                instanceof AuthenticatedApiPrincipal principal
                && principal.getRole() == com.springairag.core.entity.ApiKeyRole.ADMIN;
    }

    private boolean collectionPurgeVisible(HttpServletRequest request) {
        if (!properties.getCollectionPurge().isEnabled()) {
            return false;
        }
        Object type = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        if (ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(type)) {
            return true;
        }
        if (ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(type)
                && request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE)
                instanceof AuthenticatedApiPrincipal principal) {
            return principal.getRole()
                    == com.springairag.core.entity.ApiKeyRole.ADMIN;
        }
        return type == null
                && request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE) == null
                && properties.getCollectionPurge().isAllowAuthDisabled()
                && CollectionPurgeAuthorization.isDirectLoopback(
                request.getRemoteAddr());
    }

    private IntegrationCapabilitiesResponse.Principal principalProjection(
            HttpServletRequest request) {
        Object typeAttribute = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        Object idAttribute = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE);
        if (ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(typeAttribute)) {
            return new IntegrationCapabilitiesResponse.Principal(
                    ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT,
                    null,
                    List.of(ApiCapabilitySupport.RAG_READ,
                            ApiCapabilitySupport.RAG_WRITE,
                            "API_KEY_MANAGE"),
                    CollectionAccessMode.UNRESTRICTED,
                    null);
        }
        if (ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC.equals(typeAttribute)) {
            return new IntegrationCapabilitiesResponse.Principal(
                    ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC,
                    null,
                    ApiCapabilitySupport.fullCapabilities(),
                    CollectionAccessMode.UNRESTRICTED,
                    null);
        }
        if (ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(typeAttribute)) {
            if (!(idAttribute instanceof String principalId)
                    || !(request.getAttribute(
                            ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE)
                    instanceof AuthenticatedApiPrincipal principal)
                    || !principalId.equals(principal.getPrincipalId())) {
                throw unavailable();
            }
            return databaseProjection(principal);
        }
        if (typeAttribute == null && idAttribute == null) {
            return new IntegrationCapabilitiesResponse.Principal(
                    "LOCAL_AUTH_DISABLED",
                    null,
                    ApiCapabilitySupport.fullCapabilities(),
                    CollectionAccessMode.UNRESTRICTED,
                    null);
        }
        throw unavailable();
    }

    private IntegrationCapabilitiesResponse.Principal databaseProjection(
            AuthenticatedApiPrincipal principal) {
        if (ApiKeyCollectionAccess.isUnrestricted(principal)) {
            return new IntegrationCapabilitiesResponse.Principal(
                    ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                    principal.getRole().name(),
                    ApiCapabilitySupport.effectiveForRole(
                            principal.getRole(), principal.getCapabilities()),
                    CollectionAccessMode.UNRESTRICTED,
                    null);
        }
        try {
            List<Long> ids = ApiKeyCollectionAccess.parseAllowedIds(
                    principal.getAllowedCollectionIds());
            Map<Long, String> keysById = collectionIdentityResolver.mapKeys(ids);
            if (keysById.size() != ids.size()
                    || ids.stream().anyMatch(id -> !keysById.containsKey(id))) {
                throw unavailable();
            }
            return new IntegrationCapabilitiesResponse.Principal(
                    ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                    principal.getRole().name(),
                    ApiCapabilitySupport.effectiveForRole(
                            principal.getRole(), principal.getCapabilities()),
                    CollectionAccessMode.RESTRICTED,
                    ids.stream().map(keysById::get).toList());
        } catch (DataAccessException | IllegalStateException error) {
            throw unavailable();
        }
    }

    private com.springairag.core.exception.RagException unavailable() {
        return new com.springairag.core.exception.RagException(
                com.springairag.api.enums.ErrorCode.SERVICE_UNAVAILABLE,
                "The integration capability contract cannot be resolved completely");
    }
}
