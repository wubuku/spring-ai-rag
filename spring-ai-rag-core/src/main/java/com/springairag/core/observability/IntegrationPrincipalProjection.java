package com.springairag.core.observability;

import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 将认证上下文投影为不含 credential 的固定身份维度。
 */
public final class IntegrationPrincipalProjection {

    private IntegrationPrincipalProjection() {
    }

    public static Projection from(HttpServletRequest request) {
        if (request == null) {
            return new Projection("ANONYMOUS", "ANONYMOUS");
        }
        Object type = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        Object id = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE);
        String principalType = type == null ? null : String.valueOf(type);
        if (ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(principalType)
                && id instanceof String principalId
                && request.getAttribute(
                        ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE)
                instanceof AuthenticatedApiPrincipal principal
                && principalId.equals(principal.getPrincipalId())) {
            return new Projection(principalType, principal.getPrincipalId());
        }
        if (ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(principalType)) {
            return new Projection(principalType, "ENVIRONMENT_ROOT");
        }
        if (ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC.equals(principalType)) {
            return new Projection(principalType, "LEGACY_STATIC");
        }
        if (type == null && id == null) {
            Object authenticationRequired = request.getAttribute(
                    ApiKeyAuthFilter.AUTHENTICATION_REQUIRED_ATTRIBUTE);
            if (Boolean.TRUE.equals(authenticationRequired)
                    || (authenticationRequired != null
                    && !(authenticationRequired instanceof Boolean))) {
                return new Projection("ANONYMOUS", "ANONYMOUS");
            }
            return new Projection("LOCAL_AUTH_DISABLED", "LOCAL_AUTH_DISABLED");
        }
        return new Projection("ANONYMOUS", "ANONYMOUS");
    }

    public record Projection(String type, String ref) {
    }
}
