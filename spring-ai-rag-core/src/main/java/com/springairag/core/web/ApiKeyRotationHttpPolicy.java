package com.springairag.core.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** 分阶段 credential 轮换 HTTP 响应的统一防缓存策略。 */
public final class ApiKeyRotationHttpPolicy {

    public static final String SENSITIVE_REQUEST_ATTRIBUTE =
            ApiKeyRotationHttpPolicy.class.getName() + ".sensitive";

    private ApiKeyRotationHttpPolicy() {
    }

    public static boolean isStagedRotationPath(String path) {
        return path != null
                && path.startsWith("/api/v1/rag/api-keys/")
                && path.contains("/rotations");
    }

    public static void mark(
            HttpServletRequest request,
            HttpServletResponse response) {
        if (!isStagedRotationPath(request.getRequestURI())) {
            return;
        }
        request.setAttribute(SENSITIVE_REQUEST_ATTRIBUTE, Boolean.TRUE);
        response.setHeader("Cache-Control", "no-store");
    }

    public static boolean isSensitive(HttpServletRequest request) {
        return Boolean.TRUE.equals(
                request.getAttribute(SENSITIVE_REQUEST_ATTRIBUTE))
                || isStagedRotationPath(request.getRequestURI());
    }

    public static ResponseEntity.BodyBuilder apply(
            ResponseEntity.BodyBuilder builder,
            HttpServletRequest request) {
        return isSensitive(request)
                ? builder.cacheControl(CacheControl.noStore())
                : builder;
    }
}
