package com.springairag.core.observability;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntegrationPrincipalProjectionTest {

    @Test
    void projectsStableDatabasePrincipalOnlyWhenSnapshotMatches() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AuthenticatedApiPrincipal principal = principal("principal-1");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                "principal-1");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                principal);

        assertEquals(
                new IntegrationPrincipalProjection.Projection(
                        ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                        "principal-1"),
                IntegrationPrincipalProjection.from(request));
    }

    @Test
    void projectsEnvironmentRootAndLegacyAsFixedSyntheticValues() {
        MockHttpServletRequest root = new MockHttpServletRequest();
        root.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT);
        root.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                "environment-root");
        assertEquals(
                new IntegrationPrincipalProjection.Projection(
                        ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT,
                        "ENVIRONMENT_ROOT"),
                IntegrationPrincipalProjection.from(root));

        MockHttpServletRequest legacy = new MockHttpServletRequest();
        legacy.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC);
        legacy.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                "legacy-static");
        assertEquals(
                new IntegrationPrincipalProjection.Projection(
                        ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC,
                        "LEGACY_STATIC"),
                IntegrationPrincipalProjection.from(legacy));
    }

    @Test
    void distinguishesAuthenticationFailureFromDisabledAuthentication() {
        MockHttpServletRequest required = new MockHttpServletRequest();
        required.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATION_REQUIRED_ATTRIBUTE,
                true);
        assertEquals(
                new IntegrationPrincipalProjection.Projection("ANONYMOUS", "ANONYMOUS"),
                IntegrationPrincipalProjection.from(required));

        MockHttpServletRequest disabled = new MockHttpServletRequest();
        disabled.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATION_REQUIRED_ATTRIBUTE,
                false);
        assertEquals(
                new IntegrationPrincipalProjection.Projection(
                        "LOCAL_AUTH_DISABLED",
                        "LOCAL_AUTH_DISABLED"),
                IntegrationPrincipalProjection.from(disabled));

        assertEquals(
                new IntegrationPrincipalProjection.Projection("ANONYMOUS", "ANONYMOUS"),
                IntegrationPrincipalProjection.from(null));
    }

    @Test
    void failsClosedForMalformedOrUntrustedAttributes() {
        MockHttpServletRequest wrongId = new MockHttpServletRequest();
        wrongId.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        wrongId.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                "other-principal");
        wrongId.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                principal("principal-1"));
        assertEquals(
                new IntegrationPrincipalProjection.Projection("ANONYMOUS", "ANONYMOUS"),
                IntegrationPrincipalProjection.from(wrongId));

        MockHttpServletRequest wrongSnapshot = new MockHttpServletRequest();
        wrongSnapshot.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        wrongSnapshot.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                "principal-1");
        wrongSnapshot.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                "not-a-principal");
        assertEquals(
                new IntegrationPrincipalProjection.Projection("ANONYMOUS", "ANONYMOUS"),
                IntegrationPrincipalProjection.from(wrongSnapshot));
    }

    private AuthenticatedApiPrincipal principal(String principalId) {
        return new AuthenticatedApiPrincipal(
                principalId,
                "credential-1",
                1,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                ApiKeyRole.NORMAL,
                null,
                null,
                1L,
                null);
    }
}
