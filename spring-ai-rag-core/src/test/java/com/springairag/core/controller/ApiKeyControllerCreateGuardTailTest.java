package com.springairag.core.controller;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.service.ApiKeyManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * ApiKeyController 创建守卫长尾（Batch 576，JaCoCo 驱动）：非环境
 * root 调用方 403、allowedCollectionKeys 解析器缺失 ISE、空允许范
 * 围 IAE。
 */
class ApiKeyControllerCreateGuardTailTest {

    private ApiKeyManagementService apiKeyService;
    private CollectionIdentityResolver collectionIdentityResolver;
    private ApiKeyController controller;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyManagementService.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        var rootResolver = mock(EnvironmentRootCredentialResolver.class);
        when(rootResolver.isConfigured()).thenReturn(true);
        controller = new ApiKeyController(
                apiKeyService,
                rootResolver,
                collectionIdentityResolver, new com.springairag.core.security.ProvisioningOwnerResolver());
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    private MockHttpServletRequest nonRootRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .ROOT_AUTHENTICATED_ATTRIBUTE,
                Boolean.FALSE);
        return request;
    }

    private ApiKeyCreateRequest requestWithKeys() {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setName("ops-key");
        request.setAllowedCollectionKeys(java.util.List.of("kb:v1"));
        return request;
    }

    @Test
    void nonEnvironmentRootCallerReceivesForbidden() {
        var response = controller.createKey(
                requestWithKeys(), nonRootRequest());

        assertEquals(403, ((org.springframework.http.ResponseEntity<?>) response)
                .getStatusCode().value());
    }

    // Batch 825: `missingResolverSurfacesIllegalState` used to stand here. It built a
    // controller with a null CollectionIdentityResolver and asserted the
    // IllegalStateException that guard threw. The resolver is an unconditional
    // @Component, so that branch is unreachable in a running application and the test
    // only ever covered the guard — deleting the guard deleted the test, and the sibling
    // `emptyResolvedScopeSurfacesIllegalArgument` covers the argument validation that
    // the same endpoint still has.

    @Test
    void emptyResolvedScopeSurfacesIllegalArgument() {
        var root = new MockHttpServletRequest();
        root.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .ROOT_AUTHENTICATED_ATTRIBUTE,
                Boolean.TRUE);
        var request = new ApiKeyCreateRequest();
        request.setName("ops-key");
        request.setAllowedCollectionKeys(java.util.List.of("kb:v1"));
        request.setAllowedCollectionIds(java.util.List.of());
        when(collectionIdentityResolver.mapKeys(
                any(java.util.List.class)))
                .thenReturn(java.util.Map.of());

        assertThrows(IllegalArgumentException.class,
                () -> controller.createKey(request, root));
    }

}
