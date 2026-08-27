package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiKeyResponse;
import com.springairag.api.dto.ApiKeyRotationResponse;
import com.springairag.api.dto.ApiPrincipalResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.security.ProvisioningOwnerResolver;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.filter.ApiKeyAuthFilter;

@WebMvcTest(ApiKeyController.class)
@Import({
        ApiVersionConfig.class,
        ProvisioningOwnerResolver.class,
        ApiKeyControllerTest.RagPropertiesTestConfig.class
})
class ApiKeyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ApiKeyManagementService apiKeyService;

    @MockitoBean
    private RagApiKeyRepository apiKeyRepository;

    @MockitoBean
    private EnvironmentRootCredentialResolver rootCredentialResolver;

    @MockitoBean
    private CollectionIdentityResolver collectionIdentityResolver;

    @BeforeEach
    void setUpRootMode() {
        when(rootCredentialResolver.isConfigured()).thenReturn(false);
    }

    /**
     * Creates a mock RagApiKey entity with the given role, for setting as a request attribute.
     */
    private static RagApiKey mockCaller(String keyId, ApiKeyRole role) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId(keyId);
        key.setCredentialVersion(1);
        key.setName("Test Key");
        key.setRole(role);
        key.setEnabled(true);
        return key;
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminCaller() {
        return req -> { req.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY,
                mockCaller("rag_k_admin", ApiKeyRole.ADMIN)); return req; };
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor rootCaller() {
        return req -> {
            req.setAttribute(ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE, true);
            req.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                    ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT);
            return req;
        };
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
                .andExpect(jsonPath("$.warning").value("Save this key now — it will not be shown again."))
                .andExpect(header().string("Cache-Control", "no-store"));
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
    void createKey_restrictedCallerCannotDelegateBroaderAcl() throws Exception {
        RagApiKey caller = mockCaller("rag_k_scoped", ApiKeyRole.NORMAL);
        caller.setAllowedCollectionIds("2,4");

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .with(req -> {
                            req.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY,
                                    caller);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Escalation","allowedCollectionIds":[9]}
                                """))
                .andExpect(status().isForbidden());

        verify(apiKeyService, never()).generateKey(any());
    }

    @Test
    void createKey_restrictedCallerDefaultsChildToSameAcl() throws Exception {
        RagApiKey caller = mockCaller("rag_k_scoped", ApiKeyRole.NORMAL);
        caller.setAllowedCollectionIds("2,4");
        when(apiKeyService.generateKey(any())).thenReturn(
                new ApiKeyCreatedResponse(
                        "rag_k_child", "rag_sk_child", "Child", null,
                        List.of(2L, 4L)));

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .with(req -> {
                            req.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY,
                                    caller);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Child"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowedCollectionIds[0]").value(2))
                .andExpect(jsonPath("$.allowedCollectionIds[1]").value(4));

        verify(apiKeyService).generateKey(argThat(request ->
                List.of(2L, 4L).equals(request.getAllowedCollectionIds())));
    }

    @Test
    void createKey_withAllowedCollectionKeysResolvesAndPersistsIds() throws Exception {
        when(collectionIdentityResolver.resolveActiveIds(
                null, List.of("customer:manual", "customer:faq")))
                .thenReturn(List.of(7L, 3L));
        ApiKeyCreatedResponse created = new ApiKeyCreatedResponse(
                "rag_k_scoped", "rag_sk_scoped", "Scoped", null,
                List.of(3L, 7L));
        created.setAllowedCollectionKeys(
                List.of("customer:faq", "customer:manual"));
        when(apiKeyService.generateKey(any())).thenReturn(created);

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Scoped",
                                  "allowedCollectionKeys":[
                                    "customer:manual",
                                    "customer:faq"
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowedCollectionIds[0]").value(3))
                .andExpect(jsonPath("$.allowedCollectionIds[1]").value(7))
                .andExpect(jsonPath("$.allowedCollectionKeys[0]")
                        .value("customer:faq"));

        verify(apiKeyService).generateKey(argThat(request ->
                List.of(7L, 3L).equals(request.getAllowedCollectionIds())));
    }

    @Test
    void createKey_idAndKeyScopesCompareAsSets() throws Exception {
        when(collectionIdentityResolver.resolveActiveIds(
                null, List.of("two", "one")))
                .thenReturn(List.of(2L, 1L));
        when(apiKeyService.generateKey(any())).thenReturn(
                new ApiKeyCreatedResponse(
                        "rag_k_scoped", "rag_sk_scoped", "Scoped", null,
                        List.of(1L, 2L)));

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Scoped",
                                  "allowedCollectionIds":[1,2],
                                  "allowedCollectionKeys":["two","one"]
                                }
                                """))
                .andExpect(status().isCreated());

        verify(apiKeyService).generateKey(argThat(request ->
                List.of(2L, 1L).equals(request.getAllowedCollectionIds())));
    }

    @Test
    void createKey_mismatchedIdAndKeyScopesReturns400() throws Exception {
        when(collectionIdentityResolver.resolveActiveIds(
                null, List.of("two"))).thenReturn(List.of(2L));

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Mismatch",
                                  "allowedCollectionIds":[1],
                                  "allowedCollectionKeys":["two"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(apiKeyService, never()).generateKey(any());
    }

    @Test
    void createKey_unknownCollectionKeyReturns404ForUnrestrictedCaller() throws Exception {
        when(collectionIdentityResolver.resolveActiveIds(
                null, List.of("missing"))).thenThrow(
                new RagException(ErrorCode.COLLECTION_NOT_FOUND, "missing"));

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Missing",
                                  "allowedCollectionKeys":["missing"]
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("COLLECTION_NOT_FOUND"));

        verify(apiKeyService, never()).generateKey(any());
    }

    @Test
    void createKey_unknownCollectionKeyReturns403ForRestrictedCaller() throws Exception {
        RagApiKey caller = mockCaller("rag_k_scoped", ApiKeyRole.NORMAL);
        caller.setAllowedCollectionIds("2");
        when(collectionIdentityResolver.resolveActiveIdsWithinAllowed(
                eq(List.of("missing")), anySet())).thenThrow(
                new RagException(ErrorCode.COLLECTION_NOT_FOUND, "missing"));

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .with(request -> {
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY,
                                    caller);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Missing",
                                  "allowedCollectionKeys":["missing"]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(apiKeyService, never()).generateKey(any());
    }

    @Test
    void createKey_explicitEmptyAllowedKeysReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Empty",
                                  "allowedCollectionKeys":[]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(apiKeyService, never()).generateKey(any());
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

    @Test
    void rootMode_rootCreatesManagedKey() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        ApiKeyCreatedResponse created = new ApiKeyCreatedResponse(
                "rag_k_managed",
                "rag_sk_managed",
                "Service A",
                LocalDateTime.now().plusDays(30));
        when(apiKeyService.generateManagedKey(any())).thenReturn(created);

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .with(rootCaller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Service A",
                                  "expiresAt":"2026-09-13T00:00:00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.keyId").value("rag_k_managed"));

        verify(apiKeyService).generateManagedKey(any(ApiKeyCreateRequest.class));
        verify(apiKeyService, never()).generateKey(any());
    }

    @Test
    void createKey_withIdempotencyKeyReturnsCreatedThenReplayContract() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        ApiKeyCreatedResponse first = new ApiKeyCreatedResponse(
                "rag_k_idempotent", "rag_sk_once", "Service A",
                LocalDateTime.now().plusDays(30));
        first.setSecretAvailable(true);
        first.setIdempotentReplay(false);
        first.setCurrentCredentialActive(true);
        ApiKeyCreatedResponse replay = new ApiKeyCreatedResponse(
                "rag_k_idempotent", null, "Service A", first.getExpiresAt());
        replay.setPrincipalId("rag_k_idempotent");
        replay.setSecretAvailable(false);
        replay.setIdempotentReplay(true);
        replay.setCurrentCredentialActive(true);
        when(apiKeyService.generateIdempotentKey(
                any(), eq(ApiKeyRole.NORMAL), eq("root:environment-root"),
                anyString(), eq(true)))
                .thenReturn(new ApiKeyManagementService.ProvisioningResult(first, false))
                .thenReturn(new ApiKeyManagementService.ProvisioningResult(replay, true));

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .with(rootCaller())
                        .header("Idempotency-Key", "provision-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Service A","expiresAt":"2026-09-13T00:00:00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rawKey").value("rag_sk_once"))
                .andExpect(jsonPath("$.secretAvailable").value(true))
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andExpect(header().doesNotExist("X-RAG-Idempotent-Replay"));

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .with(rootCaller())
                        .header("Idempotency-Key", "provision-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expiresAt":"2026-09-13T00:00:00","name":"Service A"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawKey").value(nullValue()))
                .andExpect(jsonPath("$.secretAvailable").value(false))
                .andExpect(jsonPath("$.idempotentReplay").value(true))
                .andExpect(header().string("X-RAG-Idempotent-Replay", "true"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void createKey_rejectsInvalidIdempotencyHeader() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .with(rootCaller())
                        .header("Idempotency-Key", "a,b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Service A","expiresAt":"2026-09-13T00:00:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_INVALID"));

        verify(apiKeyService, never()).generateIdempotentKey(
                any(), any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void createKey_reportsIdempotencyFingerprintConflict() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        when(apiKeyService.generateIdempotentKey(
                any(), eq(ApiKeyRole.NORMAL), eq("root:environment-root"),
                anyString(), eq(true)))
                .thenThrow(new RagException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "The Idempotency-Key was already used for a different request"));

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .with(rootCaller())
                        .header("Idempotency-Key", "provision-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Different","expiresAt":"2026-09-13T00:00:00"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void createKey_failsClosedWhenProvisioningLedgerIsUnavailable() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        when(apiKeyService.generateIdempotentKey(
                any(), eq(ApiKeyRole.NORMAL), eq("root:environment-root"),
                anyString(), eq(true)))
                .thenThrow(new RagException(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        "API key provisioning ledger is unavailable"));

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .with(rootCaller())
                        .header("Idempotency-Key", "provision-unavailable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Service A","expiresAt":"2026-09-13T00:00:00"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void rootMode_businessKeyCannotCreateKeys() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);

        mockMvc.perform(post("/api/v1/rag/api-keys")
                        .with(req -> {
                            req.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY,
                                    mockCaller("rag_k_business", ApiKeyRole.NORMAL));
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Escalation",
                                  "expiresAt":"2026-09-13T00:00:00"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(apiKeyService, never()).generateManagedKey(any());
        verify(apiKeyService, never()).generateKey(any());
    }

    @Test
    void rootMode_rootCanListWithoutDatabaseAdminEntity() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        when(apiKeyService.listKeys()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/rag/api-keys").with(rootCaller()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void rootMode_listsStablePrincipals() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        ApiPrincipalResponse principal = new ApiPrincipalResponse();
        principal.setPrincipalId("rag_k_principal");
        principal.setCurrentCredentialId("rag_k_v2");
        principal.setCurrentCredentialVersion(2);
        principal.setPolicyVersion(4L);
        principal.setStatus("ACTIVE");
        when(apiKeyService.listPrincipals()).thenReturn(List.of(principal));

        mockMvc.perform(get("/api/v1/rag/api-keys/principals")
                        .with(rootCaller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].principalId").value("rag_k_principal"))
                .andExpect(jsonPath("$[0].currentCredentialId").value("rag_k_v2"))
                .andExpect(jsonPath("$[0].currentCredentialVersion").value(2))
                .andExpect(jsonPath("$[0].policyVersion").value(4));
    }

    @Test
    void rootMode_policyPutResolvesCollectionKeysAndUsesCasRequest() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        when(collectionIdentityResolver.resolveActiveIds(
                null, List.of("docs:public"))).thenReturn(List.of(7L));
        ApiPrincipalResponse updated = new ApiPrincipalResponse();
        updated.setPrincipalId("rag_k_principal");
        updated.setName("Indexer v2");
        updated.setPolicyVersion(5L);
        updated.setAllowedCollectionKeys(List.of("docs:public"));
        updated.setRequestsPerMinute(80);
        when(apiKeyService.updatePolicy(
                eq("rag_k_principal"), any(), eq(List.of(7L)), eq(true)))
                .thenReturn(updated);

        mockMvc.perform(put("/api/v1/rag/api-keys/principals/rag_k_principal/policy")
                        .with(rootCaller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedPolicyVersion":4,
                                  "name":"Indexer v2",
                                  "expiresAt":"2027-01-01T00:00:00",
                                  "allowedCollectionKeys":["docs:public"],
                                  "requestsPerMinute":80
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyVersion").value(5))
                .andExpect(jsonPath("$.requestsPerMinute").value(80));

        verify(apiKeyService).updatePolicy(
                eq("rag_k_principal"),
                argThat(request -> request.getExpectedPolicyVersion() == 4L),
                eq(List.of(7L)),
                eq(true));
    }

    @Test
    void policyPutRejectsExplicitEmptyCollectionScope() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);

        mockMvc.perform(put("/api/v1/rag/api-keys/principals/rag_k_principal/policy")
                        .with(rootCaller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedPolicyVersion":1,
                                  "name":"Indexer",
                                  "expiresAt":"2027-01-01T00:00:00",
                                  "allowedCollectionKeys":[]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        verify(apiKeyService, never()).updatePolicy(anyString(), any(), any(), anyBoolean());
    }

    @Test
    void rootMode_revokeUsesManagedFamilyRules() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        when(apiKeyService.revokeManagedKey("rag_k_v2")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/rag/api-keys/rag_k_v2")
                        .with(rootCaller()))
                .andExpect(status().isNoContent());

        verify(apiKeyService).revokeManagedKey("rag_k_v2");
        verify(apiKeyService, never()).revokeKey(anyString());
    }

    @Test
    void rootMode_rootRotationUsesManagedRulesAndNoStore() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        when(apiKeyService.rotateManagedKey("rag_k_old")).thenReturn(
                new ApiKeyCreatedResponse(
                        "rag_k_new",
                        "rag_sk_new",
                        "Service A",
                        LocalDateTime.now().plusDays(30)));

        mockMvc.perform(post("/api/v1/rag/api-keys/rag_k_old/rotate")
                        .with(rootCaller()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.keyId").value("rag_k_new"));

        verify(apiKeyService).rotateManagedKey("rag_k_old");
        verify(apiKeyService, never()).rotateKey(anyString());
    }

    @Test
    void stagedPrepareReturnsShownOnceSecretThenMetadataOnlyReplay()
            throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        UUID rotationId = UUID.randomUUID();
        ApiKeyRotationResponse first = rotationResponse(
                rotationId, "PENDING", "rag_k_v2", "rag_sk_once");
        ApiKeyRotationResponse replay = rotationResponse(
                rotationId, "PENDING", "rag_k_v2", null);
        replay.setSecretAvailable(false);
        replay.setIdempotentReplay(true);
        when(apiKeyService.prepareRotation(
                eq("rag_k_v1"), eq(120), anyString(), isNull(), eq(true)))
                .thenReturn(new ApiKeyManagementService.RotationResult(first, false))
                .thenReturn(new ApiKeyManagementService.RotationResult(replay, true));

        mockMvc.perform(post("/api/v1/rag/api-keys/rag_k_v1/rotations")
                        .with(rootCaller())
                        .header("Idempotency-Key", "rotation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overlapSeconds\":120}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("X-RAG-Idempotent-Replay"))
                .andExpect(jsonPath("$.rotationId").value(rotationId.toString()))
                .andExpect(jsonPath("$.rawKey").value("rag_sk_once"))
                .andExpect(jsonPath("$.secretAvailable").value(true));

        mockMvc.perform(post("/api/v1/rag/api-keys/rag_k_v1/rotations")
                        .with(rootCaller())
                        .header("Idempotency-Key", "rotation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overlapSeconds\":120}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-RAG-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.rotationId").value(rotationId.toString()))
                .andExpect(jsonPath("$.rawKey").value(nullValue()))
                .andExpect(jsonPath("$.secretAvailable").value(false));
    }

    @Test
    void stagedEndpointsExposeStatusCompleteAndCancelWithNoStore()
            throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        UUID rotationId = UUID.randomUUID();
        when(apiKeyService.getRotation(
                eq(rotationId), isNull(), eq(true)))
                .thenReturn(rotationResponse(
                        rotationId, "PENDING", "rag_k_v2", null));
        when(apiKeyService.completeRotation(
                eq(rotationId), isNull(), eq(true)))
                .thenReturn(rotationResponse(
                        rotationId, "COMPLETED", "rag_k_v2", null));
        when(apiKeyService.cancelRotation(
                eq(rotationId), isNull(), eq(true)))
                .thenReturn(rotationResponse(
                        rotationId, "CANCELED", "rag_k_v1", null));

        mockMvc.perform(get("/api/v1/rag/api-keys/rotations/" + rotationId)
                        .with(rootCaller()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(post("/api/v1/rag/api-keys/rotations/"
                        + rotationId + "/complete").with(rootCaller()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(post("/api/v1/rag/api-keys/rotations/"
                        + rotationId + "/cancel").with(rootCaller()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    void stagedValidationAndBusinessErrorsAreNoStore() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);

        mockMvc.perform(post("/api/v1/rag/api-keys/rag_k_v1/rotations")
                        .with(rootCaller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overlapSeconds\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        when(apiKeyService.prepareRotation(
                eq("rag_k_v1"), eq(120), anyString(), isNull(), eq(true)))
                .thenThrow(new RagException(
                        ErrorCode.CREDENTIAL_ROTATION_PENDING,
                        "pending"));
        mockMvc.perform(post("/api/v1/rag/api-keys/rag_k_v1/rotations")
                        .with(rootCaller())
                        .header("Idempotency-Key", "rotation-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overlapSeconds\":120}"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error")
                        .value("CREDENTIAL_ROTATION_PENDING"));
    }

    @Test
    void legacyNormalPassesStableOwningPrincipalToRotationService()
            throws Exception {
        RagApiKey caller = mockCaller("rag_k_v1", ApiKeyRole.NORMAL);
        caller.setPrincipalId("rag_p_owner");
        UUID rotationId = UUID.randomUUID();
        when(apiKeyService.prepareRotation(
                eq("rag_k_v1"), isNull(), anyString(), same(caller), eq(false)))
                .thenReturn(new ApiKeyManagementService.RotationResult(
                        rotationResponse(
                                rotationId, "PENDING", "rag_k_v2", "rag_sk_once"),
                        false));

        mockMvc.perform(post("/api/v1/rag/api-keys/rag_k_v1/rotations")
                        .with(request -> {
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY,
                                    caller);
                            return request;
                        })
                        .header("Idempotency-Key", "self-rotation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void rootModeBusinessCredentialCannotUseStagedManagement()
            throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);

        mockMvc.perform(post("/api/v1/rag/api-keys/rag_k_v1/rotations")
                        .with(request -> {
                            request.setAttribute(
                                    ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY,
                                    mockCaller("rag_k_business", ApiKeyRole.NORMAL));
                            return request;
                        })
                        .header("Idempotency-Key", "forbidden-rotation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(apiKeyService, never()).prepareRotation(
                anyString(), any(), anyString(), any(), anyBoolean());
    }

    private ApiKeyRotationResponse rotationResponse(
            UUID rotationId,
            String status,
            String currentCredentialId,
            String rawKey) {
        ApiKeyRotationResponse response = new ApiKeyRotationResponse();
        response.setRotationId(rotationId);
        response.setStatus(status);
        response.setPrincipalId("rag_p_owner");
        response.setKeyId(currentCredentialId);
        response.setCredentialVersion(
                "rag_k_v1".equals(currentCredentialId) ? 1 : 2);
        response.setRawKey(rawKey);
        response.setSecretAvailable(rawKey != null);
        response.setIdempotentReplay(false);
        response.setCurrentCredentialActive(true);
        response.setRotationPending("PENDING".equals(status));
        response.setRetiringCredentialId(
                "PENDING".equals(status) ? "rag_k_v1" : null);
        response.setRetiringCredentialVersion(
                "PENDING".equals(status) ? 1 : null);
        response.setRotationExpiresAt(
                LocalDateTime.of(2026, 8, 27, 23, 0));
        return response;
    }
}
