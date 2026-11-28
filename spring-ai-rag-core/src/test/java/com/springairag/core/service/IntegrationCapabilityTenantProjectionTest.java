package com.springairag.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.IntegrationCapabilitiesResponse;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.tenant.TenantMode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WP-2 接缝护栏：受限 NORMAL Key 在 SINGLE/STRICT 下取到与 /auth/me 一致的租户视图，
 * 且星幕四端点仍被声明为可读/管理端点（不因新增可选字段触发 403 回归）。
 */
class IntegrationCapabilityTenantProjectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void normalRestrictedKeyTenantViewMatchesIdentitySemanticsInBothModes() {
        for (TenantMode mode : List.of(TenantMode.SINGLE, TenantMode.STRICT)) {
            String tenantId = mode == TenantMode.STRICT ? "scenemill-prod" : "default";
            RagProperties properties = new RagProperties();
            properties.getTenant().setMode(mode);
            CollectionIdentityResolver resolver = mock(CollectionIdentityResolver.class);
            when(resolver.mapKeys(List.of(3L)))
                    .thenReturn(Map.of(3L, "tenant:manual"));
            IntegrationCapabilityCatalog catalog =
                    new IntegrationCapabilityCatalog(properties, resolver);

            AuthenticatedApiPrincipal principal = new AuthenticatedApiPrincipal(
                    "rag_k_restricted", "rag_k_credential", 1,
                    ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, ApiKeyRole.NORMAL,
                    "3", null, 1, 120,
                    List.of(ApiCapabilitySupport.RAG_READ), tenantId, "ACTIVE");
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                    ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                    principal.getPrincipalId());
            request.setAttribute(
                    ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE, principal);

            IntegrationCapabilitiesResponse response = catalog.describe(request);
            IntegrationCapabilitiesResponse.Principal projection =
                    response.getPrincipal();

            assertEquals(tenantId, projection.getTenantId());
            assertEquals(mode.name(), projection.getTenantMode());
            assertEquals("ACTIVE", projection.getTenantStatus());
            assertEquals(List.of("tenant:manual"),
                    projection.getAllowedCollectionKeys());
            assertEquals("1.1", response.getProtocol().version());
            assertEquals(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                    projection.getPrincipalType());
            assertEquals("NORMAL", projection.getPrincipalRole());

            JsonNode json = MAPPER.valueToTree(projection);
            assertEquals(tenantId, json.get("tenantId").asText());
            assertEquals(mode.name(), json.get("tenantMode").asText());
            assertEquals("ACTIVE", json.get("tenantStatus").asText());
            assertTrue(json.has("principalType"));
            assertTrue(json.has("allowedCollectionKeys"));
        }
    }

    @Test
    void nullTenantFieldsAreOmittedFromJson() throws Exception {
        IntegrationCapabilitiesResponse.Principal legacy =
                new IntegrationCapabilitiesResponse.Principal(
                        "LEGACY", "NORMAL", List.of("RAG_READ"), null, null);
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(legacy));

        assertTrue(json.has("principalType"));
        assertTrue(json.has("allowedCollectionKeys"));
        assertFalse(json.has("tenantId"));
        assertFalse(json.has("tenantMode"));
        assertFalse(json.has("tenantStatus"));
    }
}
