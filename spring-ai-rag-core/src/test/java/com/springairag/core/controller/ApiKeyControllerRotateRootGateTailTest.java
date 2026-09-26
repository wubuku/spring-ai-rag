package com.springairag.core.controller;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiKeyController rotateKey root 模式门槛长尾（Batch 670，JaCoCo
 * 驱动）：root 凭据已配置且调用方非 root 时 rotateKey 的 403 拒
 * 绝（281）。
 */
class ApiKeyControllerRotateRootGateTailTest {

    @Test
    void rotateKeyRootModeNonRootDenied() {
        EnvironmentRootCredentialResolver rootCredentialResolver =
                mock(EnvironmentRootCredentialResolver.class);
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api-keys/rag_k_1/rotate");
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .ROOT_AUTHENTICATED_ATTRIBUTE,
                Boolean.FALSE);
        RagApiKey caller = new RagApiKey();
        caller.setKeyId("rag_k_caller");
        caller.setRole(ApiKeyRole.NORMAL);
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_KEY_ENTITY,
                caller);
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                new ApiAccessPolicy() {
                    @Override public String getPrincipalId() { return "rag_p_1"; }
                    @Override public String getCredentialId() { return "rag_k_caller"; }
                    @Override public ApiKeyRole getRole() { return ApiKeyRole.NORMAL; }
                    @Override public String getAllowedCollectionIds() { return null; }
                    @Override public java.time.LocalDateTime getExpiresAt() { return null; }
                });

        var controller = new ApiKeyController(
                mock(ApiKeyManagementService.class),
                rootCredentialResolver,
                mock(CollectionIdentityResolver.class),
                mock(ProvisioningOwnerResolver.class));

        var response = controller.rotateKey("rag_k_1", request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
