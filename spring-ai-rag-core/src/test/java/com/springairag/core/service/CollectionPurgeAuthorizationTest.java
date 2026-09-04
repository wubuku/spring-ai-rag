package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CollectionPurgeAuthorization} 的纯单元测试：覆盖 purge 这个高风险
 * 操作的调用方判定（environment root、数据库 ADMIN、auth-disabled 回退）。
 */
class CollectionPurgeAuthorizationTest {

    private RagProperties properties;
    private CollectionPurgeAuthorization authorization;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getCollectionPurge().setEnabled(true);
        authorization = new CollectionPurgeAuthorization(properties);
    }

    private AuthenticatedApiPrincipal principal(ApiKeyRole role) {
        return new AuthenticatedApiPrincipal(
                "principal-1", "credential-1", 1, "DATABASE_API_KEY", role,
                null, null, 1L, null);
    }

    private MockHttpServletRequest requestWithPrincipalType(String type) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE, type);
        return request;
    }

    @Test
    void disabledFeatureRejectsEveryCaller() {
        properties.getCollectionPurge().setEnabled(false);

        assertFalse(authorization.isAllowed(
                requestWithPrincipalType(ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT)));
        RagException exception = assertThrows(RagException.class,
                () -> authorization.requireAllowed(new MockHttpServletRequest()));
        assertEquals(ErrorCode.COLLECTION_PURGE_DISABLED.name(), exception.getErrorCode());
    }

    @Test
    void nullRequestIsNeverAllowed() {
        assertFalse(authorization.isAllowed(null));
    }

    @Test
    void environmentRootIsAllowed() {
        assertTrue(authorization.isAllowed(
                requestWithPrincipalType(ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT)));
    }

    @Test
    void databaseAdminIsAllowedButNormalPrincipalIsNot() {
        MockHttpServletRequest admin = requestWithPrincipalType(
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        admin.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                principal(ApiKeyRole.ADMIN));
        assertTrue(authorization.isAllowed(admin));

        MockHttpServletRequest normal = requestWithPrincipalType(
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        normal.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                principal(ApiKeyRole.NORMAL));
        assertFalse(authorization.isAllowed(normal));
    }

    @Test
    void databasePrincipalTypeWithoutPrincipalAttributeIsRejected() {
        assertFalse(authorization.isAllowed(
                requestWithPrincipalType(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY)));
    }

    @Test
    void authDisabledFallbackRequiresExplicitOptInAndLoopback() {
        MockHttpServletRequest anonymous = new MockHttpServletRequest();

        assertFalse(authorization.isAllowed(anonymous));

        properties.getCollectionPurge().setAllowAuthDisabled(true);
        assertTrue(authorization.isAllowed(anonymous));

        MockHttpServletRequest remote = new MockHttpServletRequest();
        remote.setRemoteAddr("10.1.2.3");
        assertFalse(authorization.isAllowed(remote));
    }

    @Test
    void authDisabledFallbackRejectsLegacyStaticKeyCallers() {
        properties.getCollectionPurge().setAllowAuthDisabled(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "legacy-key");

        assertFalse(authorization.isAllowed(request));
    }

    @Test
    void requireAllowedDistinguishesDisabledFromForbidden() {
        RagException forbidden = assertThrows(RagException.class,
                () -> authorization.requireAllowed(new MockHttpServletRequest()));
        assertEquals(ErrorCode.COLLECTION_PURGE_FORBIDDEN.name(), forbidden.getErrorCode());

        properties.getCollectionPurge().setEnabled(true);
        authorization.requireAllowed(
                requestWithPrincipalType(ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT));
    }

    @Test
    void loopbackDetectionHandlesNullBlankInvalidAndIpv6() {
        assertFalse(CollectionPurgeAuthorization.isDirectLoopback(null));
        assertFalse(CollectionPurgeAuthorization.isDirectLoopback("  "));
        assertFalse(CollectionPurgeAuthorization.isDirectLoopback("not-an-address"));
        assertTrue(CollectionPurgeAuthorization.isDirectLoopback("127.0.0.1"));
        assertTrue(CollectionPurgeAuthorization.isDirectLoopback("::1"));
        assertFalse(CollectionPurgeAuthorization.isDirectLoopback("192.168.1.10"));
    }
}
