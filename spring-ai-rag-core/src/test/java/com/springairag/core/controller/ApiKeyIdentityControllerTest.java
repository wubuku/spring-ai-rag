package com.springairag.core.controller;

import com.springairag.core.config.RagProperties;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.entity.ApiKeyRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
                        .value("API_KEY_MANAGE"));
    }

    @Test
    void databaseKey_hasDataPlaneCapabilitiesOnly() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);

        mockMvc.perform(get("/api/v1/rag/auth/me")
                        .with(request -> {
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                                    ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                                    "rag_k_business");
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                                    new AuthenticatedApiPrincipal(
                                            "rag_k_business", "rag_k_v3", 3,
                                            ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                                            ApiKeyRole.NORMAL, null, null, 6L, 120));
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.length()").value(2))
                .andExpect(jsonPath("$.capabilities[0]").value("RAG_READ"))
                .andExpect(jsonPath("$.capabilities[1]").value("RAG_WRITE"))
                .andExpect(jsonPath("$.credentialId").value("rag_k_v3"))
                .andExpect(jsonPath("$.credentialVersion").value(3))
                .andExpect(jsonPath("$.policyVersion").value(6));
    }

    @Test
    void missingPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/rag/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }
}
