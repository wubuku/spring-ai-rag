package com.springairag.core.security;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProvisioningOwnerResolverTest {

    private final ProvisioningOwnerResolver resolver =
            new ProvisioningOwnerResolver();

    @Test
    void resolvesDeploymentScopedOwners() {
        MockHttpServletRequest root = new MockHttpServletRequest();
        root.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT);
        assertEquals(ProvisioningOwnerResolver.ENVIRONMENT_ROOT_OWNER,
                resolver.resolve(root));

        MockHttpServletRequest legacy = new MockHttpServletRequest();
        legacy.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC);
        assertEquals(ProvisioningOwnerResolver.LEGACY_STATIC_OWNER,
                resolver.resolve(legacy));

        assertEquals(ProvisioningOwnerResolver.AUTH_DISABLED_OWNER,
                resolver.resolve(new MockHttpServletRequest()));
    }

    @Test
    void databaseOwnerUsesStablePrincipalAcrossCredentialRotation() {
        assertEquals("db:principal-1",
                resolver.resolve(databaseRequest("principal-1", "credential-v1")));
        assertEquals("db:principal-1",
                resolver.resolve(databaseRequest("principal-1", "credential-v2")));
    }

    @Test
    void inconsistentDatabaseSnapshotFailsClosed() {
        MockHttpServletRequest request =
                databaseRequest("principal-1", "credential-v1");
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                "different-principal");

        RagException error = assertThrows(
                RagException.class, () -> resolver.resolve(request));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
    }

    @Test
    void unknownPartialIdentityDoesNotFallBackToLegacyOrLocal() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                "partial");

        RagException error = assertThrows(
                RagException.class, () -> resolver.resolve(request));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
    }

    private MockHttpServletRequest databaseRequest(
            String principalId, String credentialId) {
        AuthenticatedApiPrincipal principal = new AuthenticatedApiPrincipal(
                principalId,
                credentialId,
                credentialId.endsWith("v2") ? 2 : 1,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                ApiKeyRole.NORMAL,
                null,
                null,
                1,
                120,
                List.of(ApiCapabilitySupport.RAG_READ,
                        ApiCapabilitySupport.RAG_WRITE));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                principalId);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                principal);
        return request;
    }
}
