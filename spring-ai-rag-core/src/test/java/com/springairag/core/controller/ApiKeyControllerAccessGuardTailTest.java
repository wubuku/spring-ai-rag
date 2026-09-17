package com.springairag.core.controller;

import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiKeyRotationResponse;
import com.springairag.api.dto.ApiPrincipalPolicyUpdateRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.security.ProvisioningOwnerResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiKeyController 访问守卫矩阵长尾（Batch 478，JaCoCo 驱动）：
 * legacy（root 未配置）模式下的 ADMIN 门槛（listKeys/listPrincipals
 * /updatePolicy/revokeKey）、NORMAL 只能轮换自身的 rotate 规则、
 * updatePolicy 的空键拒绝与委托解析、root 模式的 ROOT 门槛
 * （requireEnvironmentRoot / requireStagedAccess 的 SecurityException
 * 与放行），以及 prepareRotation 的幂等键必需与重放标记。
 */
class ApiKeyControllerAccessGuardTailTest {

    private ApiKeyManagementService apiKeyService;
    private EnvironmentRootCredentialResolver rootCredentialResolver;
    private CollectionIdentityResolver collectionIdentityResolver;
    private ApiKeyController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyManagementService.class);
        rootCredentialResolver = mock(EnvironmentRootCredentialResolver.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        controller = new ApiKeyController(
                apiKeyService,
                rootCredentialResolver,
                collectionIdentityResolver,
                mock(ProvisioningOwnerResolver.class));
        request = new MockHttpServletRequest("POST", "/api/keys");
    }

    private void callerRole(ApiKeyRole role) {
        RagApiKey key = new RagApiKey();
        key.setKeyId("rag_k_caller");
        key.setRole(role);
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_KEY_ENTITY,
                key);
    }

    private void rootMode(boolean rootAuthenticated) {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        if (rootAuthenticated) {
            request.setAttribute(
                    com.springairag.core.filter.ApiKeyAuthFilter
                            .ROOT_AUTHENTICATED_ATTRIBUTE,
                    Boolean.TRUE);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T body(ResponseEntity<?> response) {
        return (T) response.getBody();
    }

    // ── legacy ADMIN 门槛 ─────────────────────────────────────────

    @Test
    void listKeysDeniedForNonAdminCaller() {
        callerRole(ApiKeyRole.NORMAL);

        ResponseEntity<?> response = controller.listKeys(request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        com.springairag.api.dto.ErrorResponse error =
                (com.springairag.api.dto.ErrorResponse) response.getBody();
        assertTrue(error.getMessage()
                .contains("Only ADMIN keys can list all API keys"));
        verify(apiKeyService, never()).listKeys();
    }

    @Test
    void listKeysAllowedForAdminCaller() {
        callerRole(ApiKeyRole.ADMIN);
        when(apiKeyService.listKeys()).thenReturn(List.of());

        ResponseEntity<?> response = controller.listKeys(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(apiKeyService).listKeys();
    }

    @Test
    void listPrincipalsDeniedForNonAdminCaller() {
        callerRole(ApiKeyRole.NORMAL);

        ResponseEntity<?> response = controller.listPrincipals(request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(apiKeyService, never()).listPrincipals();
    }

    @Test
    void updatePolicyDeniedForNonAdminCaller() {
        callerRole(ApiKeyRole.NORMAL);

        ResponseEntity<?> response = controller.updatePolicy(
                "principal-1", new ApiPrincipalPolicyUpdateRequest(), request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(apiKeyService, never()).updatePolicy(
                any(), any(), any(), eq(false));
    }

    @Test
    void updatePolicyEmptyKeysRejected() {
        callerRole(ApiKeyRole.ADMIN);
        ApiPrincipalPolicyUpdateRequest policy =
                new ApiPrincipalPolicyUpdateRequest();
        policy.setAllowedCollectionKeys(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> controller.updatePolicy("principal-1", policy, request));
    }

    @Test
    void updatePolicyResolvesDelegatedKeysAndReturns404WhenUnknown() {
        callerRole(ApiKeyRole.ADMIN);
        RagCollection collection = new RagCollection();
        collection.setId(7L);
        collection.setCollectionKey("kb");
        when(collectionIdentityResolver.requireActive(
                isNull(), eq("kb"))).thenReturn(collection);
        when(apiKeyService.updatePolicy(
                eq("principal-1"), any(), eq(List.of(7L)), eq(false)))
                .thenReturn(null);
        ApiPrincipalPolicyUpdateRequest policy =
                new ApiPrincipalPolicyUpdateRequest();
        policy.setAllowedCollectionKeys(List.of("kb"));

        ResponseEntity<?> response = controller.updatePolicy(
                "principal-1", policy, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void revokeKeyDeniedForNonAdminAndNotFoundPath() {
        callerRole(ApiKeyRole.NORMAL);
        ResponseEntity<?> denied = controller.revokeKey("rag_k_1", request);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());

        callerRole(ApiKeyRole.ADMIN);
        when(apiKeyService.revokeKey("rag_k_1")).thenReturn(false);
        ResponseEntity<?> missing = controller.revokeKey("rag_k_1", request);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());

        when(apiKeyService.revokeKey("rag_k_1")).thenReturn(true);
        ResponseEntity<?> revoked = controller.revokeKey("rag_k_1", request);
        assertEquals(HttpStatus.NO_CONTENT, revoked.getStatusCode());
    }

    @Test
    void rotateKeyNormalCallerCanOnlyRotateSelf() {
        callerRole(ApiKeyRole.NORMAL);

        ResponseEntity<?> denied = controller.rotateKey("rag_k_other", request);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        verify(apiKeyService, never()).rotateKey(any());

        // keyId 与调用者凭证一致 → 放行并返回 201。
        when(apiKeyService.rotateKey("rag_k_caller"))
                .thenReturn(new ApiKeyCreatedResponse());
        ResponseEntity<?> created =
                controller.rotateKey("rag_k_caller", request);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertTrue(created.getHeaders().getCacheControl().contains("no-store"));
    }

    // ── root 模式门槛（requireStagedAccess）──────────────────────

    @Test
    void getRotationWithoutCallerInLegacyModeThrowsSecurity() {
        when(rootCredentialResolver.isConfigured()).thenReturn(false);

        SecurityException error = assertThrows(SecurityException.class,
                () -> controller.getRotation(UUID.randomUUID(), request));
        assertTrue(error.getMessage()
                .contains("database-backed ADMIN or owning credential"));
    }

    @Test
    void completeRotationInRootModeRequiresRootAttribute() {
        rootMode(false);

        SecurityException error = assertThrows(SecurityException.class,
                () -> controller.completeRotation(UUID.randomUUID(), request));
        assertTrue(error.getMessage().contains("environment root"));
    }

    @Test
    void cancelRotationInRootModeWithRootAttributeSucceeds() {
        rootMode(true);
        UUID rotationId = UUID.randomUUID();
        when(apiKeyService.cancelRotation(
                eq(rotationId), any(), eq(true)))
                .thenReturn(new ApiKeyRotationResponse());

        ResponseEntity<ApiKeyRotationResponse> response =
                controller.cancelRotation(rotationId, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // ── prepareRotation 幂等门槛 ──────────────────────────────────

    @Test
    void prepareRotationRequiresIdempotencyKey() {
        callerRole(ApiKeyRole.ADMIN);

        RagException error = assertThrows(RagException.class,
                () -> controller.prepareRotation(
                        "rag_k_1", null, request));
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void prepareRotationReturnsReplayHeaderAndCreatedStatus() {
        callerRole(ApiKeyRole.ADMIN);
        ApiKeyRotationResponse response = new ApiKeyRotationResponse();
        when(apiKeyService.prepareRotation(
                eq("rag_k_1"), any(), any(), any(), eq(false)))
                .thenReturn(new ApiKeyManagementService.RotationResult(
                        response, true));
        request.addHeader("Idempotency-Key",
                UUID.randomUUID().toString());

        ResponseEntity<?> replay = controller.prepareRotation(
                "rag_k_1", null, request);
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertEquals("true",
                replay.getHeaders().getFirst("X-RAG-Idempotent-Replay"));

        when(apiKeyService.prepareRotation(
                eq("rag_k_1"), any(), any(), any(), eq(false)))
                .thenReturn(new ApiKeyManagementService.RotationResult(
                        response, false));
        request = new MockHttpServletRequest("POST", "/api/keys");
        callerRole(ApiKeyRole.ADMIN);
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<?> created = controller.prepareRotation(
                "rag_k_1", null, request);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
    }
}
