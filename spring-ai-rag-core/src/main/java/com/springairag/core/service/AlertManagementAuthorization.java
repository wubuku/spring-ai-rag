package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/** Alerts 全路由统一使用的 operator 管理面授权。 */
@Component
public class AlertManagementAuthorization {

    public boolean isAllowed(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        Object type = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        if (ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(type)
                || ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC.equals(type)) {
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
                && Boolean.FALSE.equals(request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATION_REQUIRED_ATTRIBUTE))
                && isDirectLoopback(request.getRemoteAddr());
    }

    public void requireAllowed(HttpServletRequest request) {
        if (!isAllowed(request)) {
            throw new RagException(
                    ErrorCode.FORBIDDEN,
                    "Alert management requires operator access");
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
