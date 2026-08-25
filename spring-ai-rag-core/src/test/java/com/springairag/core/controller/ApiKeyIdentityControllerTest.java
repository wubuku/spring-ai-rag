package com.springairag.core.controller;

import com.springairag.core.config.RagProperties;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ApiKeyIdentityController.class)
@Import(ApiKeyIdentityControllerTest.RagPropertiesTestConfig.class)
class ApiKeyIdentityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EnvironmentRootCredentialResolver rootCredentialResolver;

    @MockitoBean
    private CollectionIdentityResolver collectionIdentityResolver;

    @TestConfiguration
    static class RagPropertiesTestConfig {

        @Bean
        RagProperties ragProperties() {
            return new RagProperties();
        }
    }

    @Test
    void environmentRoot_returnsManagementCapability() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);

        mockMvc.perform(get("/api/v1/rag/auth/me")
                        .with(request -> {
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                    ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT);
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                                    EnvironmentRootCredentialResolver.PRINCIPAL_ID);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.principalType")
                        .value("ENVIRONMENT_ROOT"))
                .andExpect(jsonPath("$.rootMode").value(true))
                .andExpect(jsonPath("$.capabilities[2]")
                        .value("API_KEY_MANAGE"))
                .andExpect(jsonPath("$.principalRole").value(nullValue()))
                .andExpect(jsonPath("$.collectionAccessMode")
                        .value("UNRESTRICTED"))
                .andExpect(jsonPath("$.allowedCollectionKeys").value(nullValue()));
    }

    @Test
    void restrictedDatabaseKey_returnsRoleAndAllowedCollectionKeys() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        when(collectionIdentityResolver.mapKeys(java.util.List.of(2L, 7L)))
                .thenReturn(Map.of(2L, "customer-a:records:v1",
                        7L, "shared:records:v1"));

        mockMvc.perform(get("/api/v1/rag/auth/me")
                        .with(request -> {
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                    ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                                    "rag_p_business");
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                                    new AuthenticatedApiPrincipal(
                                            "rag_p_business", "rag_k_v3", 3,
                                            ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                                            ApiKeyRole.NORMAL, "2,7", null, 6L, 120));
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.length()").value(2))
                .andExpect(jsonPath("$.capabilities[0]").value("RAG_READ"))
                .andExpect(jsonPath("$.capabilities[1]").value("RAG_WRITE"))
                .andExpect(jsonPath("$.credentialId").value("rag_k_v3"))
                .andExpect(jsonPath("$.credentialVersion").value(3))
                .andExpect(jsonPath("$.policyVersion").value(6))
                .andExpect(jsonPath("$.principalRole").value("NORMAL"))
                .andExpect(jsonPath("$.collectionAccessMode").value("RESTRICTED"))
                .andExpect(jsonPath("$.allowedCollectionKeys[0]")
                        .value("customer-a:records:v1"))
                .andExpect(jsonPath("$.allowedCollectionKeys[1]")
                        .value("shared:records:v1"));
    }

    @Test
    void unrestrictedDatabaseKey_returnsNullAllowList() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);

        mockMvc.perform(get("/api/v1/rag/auth/me")
                        .with(request -> {
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                    ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                                    "rag_p_business");
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                                    principal(ApiKeyRole.NORMAL, null));
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principalRole").value("NORMAL"))
                .andExpect(jsonPath("$.collectionAccessMode").value("UNRESTRICTED"))
                .andExpect(jsonPath("$.allowedCollectionKeys").value(nullValue()));

        verify(collectionIdentityResolver, never()).mapKeys(any());
    }

    @Test
    void adminDatabaseKey_isAlwaysUnrestricted() throws Exception {
        mockMvc.perform(get("/api/v1/rag/auth/me")
                        .with(request -> {
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                    ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                                    "rag_p_business");
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                                    principal(ApiKeyRole.ADMIN, "2,7"));
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principalRole").value("ADMIN"))
                .andExpect(jsonPath("$.collectionAccessMode").value("UNRESTRICTED"))
                .andExpect(jsonPath("$.allowedCollectionKeys").value(nullValue()));

        verify(collectionIdentityResolver, never()).mapKeys(any());
    }

    @Test
    void legacyStatic_returnsExplicitUnrestrictedContract() throws Exception {
        mockMvc.perform(get("/api/v1/rag/auth/me")
                        .with(request -> {
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                    ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC);
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                                    "legacy-static");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principalRole").value(nullValue()))
                .andExpect(jsonPath("$.collectionAccessMode").value("UNRESTRICTED"))
                .andExpect(jsonPath("$.allowedCollectionKeys").value(nullValue()));
    }

    @Test
    void incompleteCollectionMapping_returns503WithoutPartialScope() throws Exception {
        when(collectionIdentityResolver.mapKeys(java.util.List.of(2L, 7L)))
                .thenReturn(Map.of(2L, "customer-a:records:v1"));

        mockMvc.perform(get("/api/v1/rag/auth/me")
                        .with(request -> {
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                    ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                                    "rag_p_business");
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                                    principal(ApiKeyRole.NORMAL, "2,7"));
                            return request;
                        }))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.allowedCollectionKeys").doesNotExist());
    }

    @Test
    void mismatchedRequestPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/rag/auth/me")
                        .with(request -> {
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                    ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                                    "rag_p_other");
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                                    principal(ApiKeyRole.NORMAL, null));
                            return request;
                        }))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/rag/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    private AuthenticatedApiPrincipal principal(ApiKeyRole role, String allowedIds) {
        return new AuthenticatedApiPrincipal(
                "rag_p_business", "rag_k_v3", 3,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                role, allowedIds, null, 6L, 120);
    }
}
