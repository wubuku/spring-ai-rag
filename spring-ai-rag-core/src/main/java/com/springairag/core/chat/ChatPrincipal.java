package com.springairag.core.chat;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Stable authenticated identity used for chat history and memory namespaces.
 *
 * <p>Raw credentials never enter this object. The identity is captured while
 * the servlet request is active so asynchronous streaming work does not need
 * ThreadLocal or request access.</p>
 */
public record ChatPrincipal(String id, String type, boolean admin) {

    public ChatPrincipal {
        if (id == null || id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("principal id must contain 1-128 characters");
        }
    }

    public static ChatPrincipal local() {
        return new ChatPrincipal("local:auth-disabled", "AUTH_DISABLED", false);
    }

    public static ChatPrincipal fromCurrentRequest() {
        ServletRequestAttributes attributes = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes current) {
            attributes = current;
        }
        return from(attributes != null ? attributes.getRequest() : null);
    }

    public static ChatPrincipal from(HttpServletRequest request) {
        if (request == null) {
            return local();
        }
        Object id = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE);
        Object type = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        Object authenticated = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE);
        String principalType = type != null ? String.valueOf(type) : null;

        if (ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(principalType)) {
            return new ChatPrincipal("root:environment-root", principalType, true);
        }
        if (ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(principalType)) {
            String keyId = id != null ? String.valueOf(id) : "unknown";
            boolean admin = authenticated instanceof AuthenticatedApiPrincipal principal
                    && principal.getRole() == ApiKeyRole.ADMIN;
            return new ChatPrincipal("db:" + keyId, principalType, admin);
        }
        if (ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC.equals(principalType)) {
            return new ChatPrincipal("legacy:static", principalType, false);
        }
        return local();
    }

    /**
     * Deterministic, bounded namespace accepted by Spring AI's VARCHAR(36) memory key.
     */
    public String memoryConversationId(String sessionId) {
        String input = id + "\u0000" + sessionId;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(36);
            for (int i = 0; i < 18; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
