package com.springairag.core.controller;

import com.springairag.api.dto.ApiPrincipalPolicyUpdateRequest;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiKeyController root 模式管理门槛长尾（Batch 669，JaCoCo 驱
 * 动）：root 凭据已配置且调用方非 root 时 listPrincipals /
 * updatePolicy / revokeKey / rotate 的 403 拒绝（192 / 209 / 253
 * / 281）。
 */
class ApiKeyControllerRootGateTailTest {

    private EnvironmentRootCredentialResolver rootCredentialResolver;
    private CollectionIdentityResolver collectionIdentityResolver;
    private MockHttpServletRequest request;

    private ApiKeyController controller() {
        return new ApiKeyController(
                mock(ApiKeyManagementService.class),
                rootCredentialResolver,
                collectionIdentityResolver,
                mock(ProvisioningOwnerResolver.class));
    }

    private void rootMode() {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .ROOT_AUTHENTICATED_ATTRIBUTE,
                Boolean.FALSE);
    }

    private void callerPolicy(ApiKeyRole role) {
        RagApiKey key = new RagApiKey();
        key.setKeyId("rag_k_caller");
        key.setRole(role);
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_KEY_ENTITY,
                key);
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                new ApiAccessPolicy() {
                    @Override public String getPrincipalId() { return "rag_p_1"; }
                    @Override public String getCredentialId() { return "rag_k_1"; }
                    @Override public ApiKeyRole getRole() { return role; }
                    @Override public String getAllowedCollectionIds() { return "10"; }
                    @Override public java.time.LocalDateTime getExpiresAt() { return null; }
                });
    }

    @BeforeEach
    void setUp() {
        rootCredentialResolver = mock(EnvironmentRootCredentialResolver.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        request = new MockHttpServletRequest("POST", "/api-keys");
        rootMode();
        callerPolicy(ApiKeyRole.NORMAL);
    }

    @Test
    void listPrincipalsRootModeNonRootDenied() {
        var response = controller().listPrincipals(request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void updatePolicyRootModeNonRootDenied() {
        var response = controller().updatePolicy(
                "rag_p_1",
                new com.springairag.api.dto.ApiPrincipalPolicyUpdateRequest(),
                request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void revokeKeyRootModeNonRootDenied() {
        var response = controller().revokeKey("rag_k_1", request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void rotateKeyRootModeNonRootDenied() {
        request.addHeader("Idempotency-Key",
                java.util.UUID.randomUUID().toString());

        var response = controller().prepareRotation(
                "rag_k_1", null, request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
