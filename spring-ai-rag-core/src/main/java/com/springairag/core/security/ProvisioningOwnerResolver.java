package com.springairag.core.security;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 从认证快照派生 provisioning owner，调用方不能覆盖该值。
 */
@Component
public class ProvisioningOwnerResolver {

    public static final String ENVIRONMENT_ROOT_OWNER = "root:environment-root";
    public static final String LEGACY_STATIC_OWNER = "legacy:static";
    public static final String AUTH_DISABLED_OWNER = "local:auth-disabled";

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return AUTH_DISABLED_OWNER;
        }
        Object type = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        Object id = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE);
        Object snapshot = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE);

        if (ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(type)) {
            if (snapshot != null) {
                throw inconsistent();
            }
            return ENVIRONMENT_ROOT_OWNER;
        }
        if (ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(type)) {
            if (!(id instanceof String principalId) || principalId.isBlank()
                    || !(snapshot instanceof AuthenticatedApiPrincipal principal)
                    || !principalId.equals(principal.getPrincipalId())
                    || !ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(
                    principal.principalType())) {
                throw inconsistent();
            }
            return "db:" + principalId;
        }
        if (ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC.equals(type)) {
            if (snapshot != null) {
                throw inconsistent();
            }
            return LEGACY_STATIC_OWNER;
        }
        if (type == null && id == null && snapshot == null
                && ApiKeyCollectionAccess.currentPolicy(request) == null) {
            return AUTH_DISABLED_OWNER;
        }
        throw inconsistent();
    }

    private RagException inconsistent() {
        return new RagException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "The authenticated provisioning owner cannot be resolved completely");
    }
}
