package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiKeyResponse;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.filter.ApiKeyAuthFilter;

@WebMvcTest(ApiKeyController.class)
@Import({ApiVersionConfig.class, ApiKeyControllerTest.RagPropertiesTestConfig.class})
class ApiKeyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ApiKeyManagementService apiKeyService;

    @MockitoBean
    private RagApiKeyRepository apiKeyRepository;

    /**
     * Creates a mock RagApiKey entity with the given role, for setting as a request attribute.
     */
    private static RagApiKey mockCaller(String keyId, ApiKeyRole role) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setName("Test Key");
        key.setRole(role);
        key.setEnabled(true);
        return key;
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminCaller() {
        return req -> { req.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY,
                mockCaller("rag_k_admin", ApiKeyRole.ADMIN)); return req; };
    }

    @TestConfiguration
    static class RagPropertiesTestConfig {
        @Bean
        RagProperties ragProperties() {
            return new RagProperties();
        }
    }

    @Test
    void createKey_returns201WithRawKey() throws Exception {
        ApiKeyCreatedResponse created = new ApiKeyCreatedResponse(
                "rag_k_abc123",
                "rag_sk_rawkey456",
                "Production Server",
                null
        );
        when(apiKeyService.generateKey(any(ApiKeyCreateRequest.class))).thenReturn(created);

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Production Server\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.keyId").value("rag_k_abc123"))
                .andExpect(jsonPath("$.rawKey").value("rag_sk_rawkey456"))
                .andExpect(jsonPath("$.name").value("Production Server"))
                .andExpect(jsonPath("$.warning").value("Save this key now — it will not be shown again."));
    }

    @Test
    void createKey_withExpiration_includesExpiresAt() throws Exception {
        LocalDateTime expires = LocalDateTime.of(2027, 1, 1, 0, 0);
        ApiKeyCreatedResponse created = new ApiKeyCreatedResponse(
                "rag_k_abc", "rag_sk_raw", "Expiring Key", expires);
        when(apiKeyService.generateKey(any(ApiKeyCreateRequest.class))).thenReturn(created);

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Expiring Key\", \"expiresAt\": \"2027-01-01T00:00:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void listKeys_returnsAllKeys() throws Exception {
        ApiKeyResponse key1 = new ApiKeyResponse("rag_k_1", "Key 1",
                LocalDateTime.of(2026, 1, 1, 0, 0), null, null, true);
        ApiKeyResponse key2 = new ApiKeyResponse("rag_k_2", "Key 2",
                LocalDateTime.of(2026, 2, 1, 0, 0), LocalDateTime.of(2026, 3, 1, 0, 0), null, false);
        when(apiKeyService.listKeys()).thenReturn(List.of(key1, key2));

        mockMvc.perform(get("/api/v1/rag/api-keys")
                        .with(req -> { req.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY,
                                mockCaller("rag_k_admin", ApiKeyRole.ADMIN)); return req; }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].keyId").value("rag_k_1"))
                .andExpect(jsonPath("$[0].rawKey").doesNotExist())
                .andExpect(jsonPath("$[1].keyId").value("rag_k_2"));
    }

    @Test
    void revokeKey_existing_returns204() throws Exception {
        when(apiKeyService.revokeKey("rag_k_abc")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/rag/api-keys/rag_k_abc")
                        .with(req -> { req.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY,
                                mockCaller("rag_k_admin", ApiKeyRole.ADMIN)); return req; }))
                .andExpect(status().isNoContent());

        verify(apiKeyService).revokeKey("rag_k_abc");
    }

    @Test
    void revokeKey_nonExistent_returns404() throws Exception {
        when(apiKeyService.revokeKey("rag_k_unknown")).thenReturn(false);

        mockMvc.perform(delete("/api/v1/rag/api-keys/rag_k_unknown")
                        .with(req -> { req.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY,
                                mockCaller("rag_k_admin", ApiKeyRole.ADMIN)); return req; }))
                .andExpect(status().isNotFound());
    }

    @Test
    void rotateKey_existing_returns201WithNewKey() throws Exception {
        ApiKeyCreatedResponse rotated = new ApiKeyCreatedResponse(
                "rag_k_new", "rag_sk_newraw", "My Key", null);
        when(apiKeyService.rotateKey("rag_k_old")).thenReturn(rotated);

        mockMvc.perform(post("/api/v1/rag/api-keys/rag_k_old/rotate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.keyId").value("rag_k_new"))
                .andExpect(jsonPath("$.rawKey").value("rag_sk_newraw"));
    }

    @Test
    void rotateKey_nonExistent_returns404() throws Exception {
        when(apiKeyService.rotateKey("rag_k_unknown")).thenReturn(null);

        mockMvc.perform(post("/api/v1/rag/api-keys/rag_k_unknown/rotate"))
                .andExpect(status().isNotFound());
    }
}
