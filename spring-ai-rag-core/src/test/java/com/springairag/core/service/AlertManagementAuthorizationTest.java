package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertManagementAuthorizationTest {

    private final AlertManagementAuthorization authorization =
            new AlertManagementAuthorization();

    @Test
    void allowsRootAdminLegacyAndDirectLoopbackWhenAuthIsDisabled() {
        MockHttpServletRequest root = request();
        root.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT);
        assertTrue(authorization.isAllowed(root));

        MockHttpServletRequest admin = request();
        attachDatabasePrincipal(admin, ApiKeyRole.ADMIN);
        assertTrue(authorization.isAllowed(admin));

        MockHttpServletRequest legacy = request();
        legacy.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC);
        assertTrue(authorization.isAllowed(legacy));

        MockHttpServletRequest loopback = request();
        loopback.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATION_REQUIRED_ATTRIBUTE,
                false);
        assertTrue(authorization.isAllowed(loopback));
    }

    @Test
    void rejectsNormalPrincipalAndNonLoopbackAuthDisabledRequest() {
        MockHttpServletRequest normal = request();
        attachDatabasePrincipal(normal, ApiKeyRole.NORMAL);
        assertFalse(authorization.isAllowed(normal));

        MockHttpServletRequest remote = request();
        remote.setRemoteAddr("198.51.100.5");
        remote.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATION_REQUIRED_ATTRIBUTE,
                false);
        assertFalse(authorization.isAllowed(remote));

        RagException forbidden = assertThrows(
                RagException.class,
                () -> authorization.requireAllowed(normal));
        assertEquals(ErrorCode.FORBIDDEN, forbidden.getErrorCodeEnum());
        assertEquals(
                "Alert management requires operator access",
                forbidden.getMessage());
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private void attachDatabasePrincipal(
            MockHttpServletRequest request, ApiKeyRole role) {
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                new AuthenticatedApiPrincipal(
                        "principal-1",
                        "credential-1",
                        1,
                        ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                        role,
                        null,
                        null,
                        1,
                        null));
    }
}
