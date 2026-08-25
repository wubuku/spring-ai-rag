package com.springairag.core.integration;

import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagWebSecurityConfiguration;
import com.springairag.core.config.CorsConfig;
import com.springairag.core.controller.ApiKeyController;
import com.springairag.core.controller.ApiKeyIdentityController;
import com.springairag.core.controller.GlobalExceptionHandler;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({
        ApiKeyController.class,
        ApiKeyIdentityController.class
})
@Import({
        RagWebSecurityConfiguration.class,
        CorsConfig.class,
        GlobalExceptionHandler.class
})
@EnableConfigurationProperties(RagProperties.class)
@TestPropertySource(properties = {
        "rag.security.enabled=false",
        "rag.security.api-key=legacy-static-key",
        "rag.security.root-api-key=root-2026-08-14-9f4c2a7b6d1e8a3c",
        "rag.cors.enabled=true",
        "rag.cors.allowed-origins[0]=http://127.0.0.1:15173"
})
class ApiKeyRootModeWebIntegrationTest {

    private static final String ROOT_KEY =
            "root-2026-08-14-9f4c2a7b6d1e8a3c";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiKeyManagementService apiKeyManagementService;

    @MockitoBean
    private CollectionIdentityResolver collectionIdentityResolver;

    @Test
    void rootUnlockAndManagementFlowUsesRealFilterAndController() throws Exception {
        when(apiKeyManagementService.listKeys()).thenReturn(List.of());
        when(apiKeyManagementService.generateManagedKey(any())).thenReturn(
                new ApiKeyCreatedResponse(
                        "rag_k_managed",
                        "rag_sk_once_only",
                        "Integration Client",
                        LocalDateTime.of(2026, 9, 13, 0, 0)));

        mockMvc.perform(get("/api/v1/rag/auth/me")
                        .header("Authorization", "Bearer " + ROOT_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principalType")
                        .value("ENVIRONMENT_ROOT"))
                .andExpect(jsonPath("$.capabilities[2]")
                        .value("API_KEY_MANAGE"))
                .andExpect(jsonPath("$.principalRole").value(nullValue()))
                .andExpect(jsonPath("$.collectionAccessMode")
                        .value("UNRESTRICTED"));

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .header("X-API-Key", ROOT_KEY)
                        .header(HttpHeaders.ORIGIN,
                                "http://127.0.0.1:15173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Integration Client",
                                  "expiresAt":"2026-09-13T00:00:00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://127.0.0.1:15173"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.rawKey").value("rag_sk_once_only"));

        mockMvc.perform(get("/api/v1/rag/api-keys")
                        .header("X-API-Key", ROOT_KEY))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(apiKeyManagementService).generateManagedKey(any());
        verify(apiKeyManagementService).listKeys();
    }

    @Test
    void rootManagementWriteRejectsUnconfiguredOrigin() throws Exception {
        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .header("X-API-Key", ROOT_KEY)
                        .header(HttpHeaders.ORIGIN,
                                "http://127.0.0.1:15174")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Blocked Origin",
                                  "expiresAt":"2027-08-15T00:00:00"
                                }
                                """))
                .andExpect(status().isForbidden());

        verify(apiKeyManagementService, never())
                .generateManagedKey(any());
    }

    @Test
    void rootModeProtectsDataPlaneEvenWhenLegacyFlagIsDisabled()
            throws Exception {
        mockMvc.perform(get("/api/v1/rag/auth/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/rag/auth/me")
                        .header("X-API-Key", ROOT_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principalType")
                        .value("ENVIRONMENT_ROOT"));
    }

    @Test
    void businessKeyCanUseDataPlaneButCannotManageKeys() throws Exception {
        AuthenticatedApiPrincipal businessPrincipal =
                new AuthenticatedApiPrincipal(
                        "rag_p_business",
                        "rag_k_business",
                        1,
                        "DATABASE_API_KEY",
                        ApiKeyRole.NORMAL,
                        null,
                        null,
                        1,
                        null);
        when(apiKeyManagementService.authenticate("rag_sk_business"))
                .thenReturn(businessPrincipal);

        mockMvc.perform(get("/api/v1/rag/auth/me")
                        .header("X-API-Key", "rag_sk_business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principalType")
                        .value("DATABASE_API_KEY"))
                .andExpect(jsonPath("$.principalRole").value("NORMAL"))
                .andExpect(jsonPath("$.collectionAccessMode")
                        .value("UNRESTRICTED"));

        mockMvc.perform(get("/api/v1/rag/api-keys")
                        .header("X-API-Key", "rag_sk_business"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void rootModeRejectsLegacyStaticAndQueryCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/rag/auth/me")
                        .header("X-API-Key", "legacy-static-key"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/rag/auth/me")
                        .param("apiKey", ROOT_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(
                        "Query-string API credentials are not accepted. Use Authorization: Bearer or X-API-Key."));
    }
}
