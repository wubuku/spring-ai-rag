package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagCollectionPurgeProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * Collection purge 的高风险调用方判定。
 */
@Component
public class CollectionPurgeAuthorization {

    private final RagCollectionPurgeProperties properties;

    public CollectionPurgeAuthorization(RagProperties properties) {
        this.properties = properties.getCollectionPurge();
    }

    public boolean isAllowed(HttpServletRequest request) {
        if (!properties.isEnabled() || request == null) {
            return false;
        }
        Object type = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        if (ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(type)) {
            return true;
        }
        if (ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(type)
                && request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE)
                instanceof AuthenticatedApiPrincipal principal) {
            return principal.getRole() == ApiKeyRole.ADMIN;
        }
        return type == null
                && request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE) == null
                && properties.isAllowAuthDisabled()
                && isDirectLoopback(request.getRemoteAddr());
    }

    public void requireAllowed(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            throw new RagException(
                    ErrorCode.COLLECTION_PURGE_DISABLED,
                    "Collection purge is disabled");
        }
        if (!isAllowed(request)) {
            throw new RagException(
                    ErrorCode.COLLECTION_PURGE_FORBIDDEN,
                    "Collection purge requires environment root or database ADMIN");
        }
    }

    static boolean isDirectLoopback(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddress).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }
}
