package com.springairag.core.controller;

import com.springairag.api.dto.ApiPrincipalPolicyUpdateRequest;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.security.ProvisioningOwnerResolver;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiKeyController 管理面守卫矩阵长尾（Batch 665，JaCoCo 驱
 * 动）：legacy 模式下 listPrincipals/updatePolicy/revokeKey 的
 * ADMIN 门槛拒绝、resolver 缺失拒绝、rotate 未知密钥的 404 +
 * no-store、匿名 legacy 调用方默认 NORMAL。
 */
class ApiKeyControllerGuardMatrixTailTest {

    private ApiKeyManagementService apiKeyService;
    private EnvironmentRootCredentialResolver rootCredentialResolver;
    private CollectionIdentityResolver collectionIdentityResolver;
    private MockHttpServletRequest request;

    private ApiKeyController controller(
            CollectionIdentityResolver resolver) {
        return new ApiKeyController(
                mock(ApiKeyManagementService.class),
                rootCredentialResolver,
                resolver,
                mock(ProvisioningOwnerResolver.class));
    }

    private ApiKeyController controllerWithoutResolver() {
        return new ApiKeyController(
                mock(ApiKeyManagementService.class),
                rootCredentialResolver,
                null,
                mock(ProvisioningOwnerResolver.class));
    }

    private static ApiAccessPolicy policyWithRole(ApiKeyRole role) {
        return new ApiAccessPolicy() {
            @Override public String getPrincipalId() { return "rag_p_1"; }
            @Override public String getCredentialId() { return "rag_k_1"; }
            @Override public ApiKeyRole getRole() { return role; }
            @Override public String getAllowedCollectionIds() { return null; }
            @Override public java.time.LocalDateTime getExpiresAt() { return null; }
        };
    }

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyManagementService.class);
        rootCredentialResolver = mock(EnvironmentRootCredentialResolver.class);
        // legacy 模式：root 凭据未配置。
        when(rootCredentialResolver.isConfigured()).thenReturn(false);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        request = new MockHttpServletRequest("POST", "/api-keys");
    }

    @Test
    void listPrincipalsLegacyNonAdminDenied() {
        var response = controller(collectionIdentityResolver)
                .listPrincipals(request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody()).contains("Only ADMIN keys"));
    }

    @Test
    void updatePolicyLegacyNonAdminDenied() {
        var response = controller(collectionIdentityResolver)
                .updatePolicy("rag_p_1",
                        new ApiPrincipalPolicyUpdateRequest(),
                        request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody()).contains("Only ADMIN keys"));
    }

    @Test
    void updatePolicyWithoutResolverIsRejected() {
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                policyWithRole(ApiKeyRole.ADMIN));
        var controllerWithoutResolver = controllerWithoutResolver();
        var policy = new ApiPrincipalPolicyUpdateRequest();
        policy.setAllowedCollectionKeys(java.util.List.of("kb"));

        assertThrows(IllegalStateException.class,
                () -> controllerWithoutResolver.updatePolicy(
                        "rag_p_1", policy, request));
    }

    @Test
    void revokeKeyLegacyNonAdminDenied() {
        var response = controller(collectionIdentityResolver)
                .revokeKey("rag_k_1", request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody()).contains("Only ADMIN keys"));
    }

    @Test
    void rotateUnknownKeyReturns404WithNoStore() {
        var adminRequest = new MockHttpServletRequest("POST", "/api-keys");
        adminRequest.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                policyWithRole(ApiKeyRole.ADMIN));
        when(apiKeyService.prepareRotation(
                any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(null);

        adminRequest.addHeader("Idempotency-Key",
                java.util.UUID.randomUUID().toString());
        var response = controller(collectionIdentityResolver)
                .prepareRotation("rag_k_ghost", null, adminRequest);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
    }

    @Test
    void anonymousLegacyCallerDefaultsToNormalRole() {
        // legacy 模式（root 未配置）+ 无策略属性 → 默认 NORMAL →
        // 管理接口一律 403。
        var response = controller(collectionIdentityResolver)
                .listKeys(request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
