package com.springairag.core.service;

import com.springairag.api.dto.IntegrationCapabilitiesResponse;
import com.springairag.api.enums.CollectionAccessMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntegrationCapabilityCatalogTest {

    private RagProperties properties;
    private CollectionIdentityResolver collectionIdentityResolver;
    private IntegrationCapabilityCatalog catalog;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        catalog = new IntegrationCapabilityCatalog(
                properties, collectionIdentityResolver);
    }

    @Test
    void projectsEnvironmentRootLegacyStaticAndAuthDisabledCallers() {
        MockHttpServletRequest root = new MockHttpServletRequest();
        root.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT);
        IntegrationCapabilitiesResponse.Principal rootPrincipal =
                catalog.describe(root).getPrincipal();
        assertEquals(ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT,
                rootPrincipal.getPrincipalType());
        assertEquals(CollectionAccessMode.UNRESTRICTED,
                rootPrincipal.getCollectionAccessMode());
        assertTrue(rootPrincipal.getCapabilities().contains("API_KEY_MANAGE"));
        assertNull(rootPrincipal.getAllowedCollectionKeys());

        MockHttpServletRequest legacy = new MockHttpServletRequest();
        legacy.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC);
        IntegrationCapabilitiesResponse.Principal legacyPrincipal =
                catalog.describe(legacy).getPrincipal();
        assertEquals(ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC,
                legacyPrincipal.getPrincipalType());
        assertEquals(ApiCapabilitySupport.fullCapabilities(),
                legacyPrincipal.getCapabilities());

        IntegrationCapabilitiesResponse.Principal localPrincipal =
                catalog.describe(new MockHttpServletRequest()).getPrincipal();
        assertEquals("LOCAL_AUTH_DISABLED", localPrincipal.getPrincipalType());
        assertEquals(CollectionAccessMode.UNRESTRICTED,
                localPrincipal.getCollectionAccessMode());
        assertEquals(ApiCapabilitySupport.fullCapabilities(),
                localPrincipal.getCapabilities());
    }

    @Test
    void projectsDatabaseCallerWithEffectivePolicyAndStableCollectionKeys() {
        AuthenticatedApiPrincipal restricted = principal(
                "rag_k_restricted", "3,7",
                List.of(ApiCapabilitySupport.RAG_READ));
        when(collectionIdentityResolver.mapKeys(List.of(3L, 7L)))
                .thenReturn(Map.of(3L, "tenant:manual", 7L, "tenant:faq"));

        IntegrationCapabilitiesResponse.Principal projection =
                catalog.describe(databaseRequest(restricted)).getPrincipal();

        assertEquals(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                projection.getPrincipalType());
        assertEquals("NORMAL", projection.getPrincipalRole());
        assertEquals(List.of(ApiCapabilitySupport.RAG_READ),
                projection.getCapabilities());
        assertEquals(CollectionAccessMode.RESTRICTED,
                projection.getCollectionAccessMode());
        assertEquals(List.of("tenant:manual", "tenant:faq"),
                projection.getAllowedCollectionKeys());

        AuthenticatedApiPrincipal unrestricted = principal(
                "rag_k_unrestricted", null,
                List.of(ApiCapabilitySupport.RAG_READ,
                        ApiCapabilitySupport.RAG_WRITE));
        IntegrationCapabilitiesResponse.Principal unrestrictedProjection =
                catalog.describe(databaseRequest(unrestricted)).getPrincipal();
        assertEquals(CollectionAccessMode.UNRESTRICTED,
                unrestrictedProjection.getCollectionAccessMode());
        assertNull(unrestrictedProjection.getAllowedCollectionKeys());
    }

    @Test
    void reflectsRuntimeFeatureFlags() {
        properties.getApiKeyProvisioning().setEnabled(false);
        properties.getEmbeddingJobs().setEnabled(false);
        properties.getDocumentLifecycle().setSyncRunsEnabled(true);
        properties.getOpenAiCompatibility().setEnabled(true);

        IntegrationCapabilitiesResponse.Features features =
                catalog.describe(new MockHttpServletRequest()).getFeatures();

        assertFalse(features.provisioning().idempotencyKey());
        assertFalse(features.dataPlane().embedding().asyncPolicy());
        assertTrue(features.optional().documentSyncRuns());
        assertTrue(features.optional().openAiCompatibility());
    }

    @Test
    void failsClosedWhenRestrictedAclCannotBeMappedCompletely() {
        AuthenticatedApiPrincipal restricted = principal(
                "rag_k_restricted", "3,7",
                List.of(ApiCapabilitySupport.RAG_READ));
        when(collectionIdentityResolver.mapKeys(List.of(3L, 7L)))
                .thenReturn(Map.of(3L, "tenant:manual"));

        RagException error = assertThrows(
                RagException.class,
                () -> catalog.describe(databaseRequest(restricted)));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
    }

    @Test
    void failsClosedWhenDatabaseIdentitySnapshotIsInconsistent() {
        AuthenticatedApiPrincipal principal = principal(
                "rag_k_actual", null, List.of(ApiCapabilitySupport.RAG_READ));
        MockHttpServletRequest request = databaseRequest(principal);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                "rag_k_different");

        RagException error = assertThrows(
                RagException.class, () -> catalog.describe(request));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
    }

    private MockHttpServletRequest databaseRequest(
            AuthenticatedApiPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                principal.getPrincipalId());
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                principal);
        return request;
    }

    private AuthenticatedApiPrincipal principal(
            String principalId,
            String allowedCollectionIds,
            List<String> capabilities) {
        return new AuthenticatedApiPrincipal(
                principalId,
                "rag_k_credential",
                1,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                ApiKeyRole.NORMAL,
                allowedCollectionIds,
                null,
                1,
                120,
                capabilities);
    }
}
