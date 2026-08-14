package com.springairag.core.controller;

import com.springairag.api.dto.ApiKeyIdentityResponse;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * WebUI 解锁时使用的当前 API principal 探测端点。
 */
@RestController
@RequestMapping("/api/v1/rag/auth")
public class ApiKeyIdentityController {

    private static final List<String> DATA_PLANE_CAPABILITIES =
            List.of("RAG_READ", "RAG_WRITE");
    private static final List<String> ROOT_CAPABILITIES =
            List.of("RAG_READ", "RAG_WRITE", "API_KEY_MANAGE");

    private final EnvironmentRootCredentialResolver rootCredentialResolver;

    public ApiKeyIdentityController(
            EnvironmentRootCredentialResolver rootCredentialResolver) {
        this.rootCredentialResolver = rootCredentialResolver;
    }

    @GetMapping("/me")
    public ResponseEntity<?> currentIdentity(HttpServletRequest request) {
        Object principalType = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        Object principalId = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE);
        if (!(principalType instanceof String type)
                || !(principalId instanceof String id)) {
            ErrorResponse error = ErrorResponse.builder()
                    .error("UNAUTHORIZED")
                    .status(HttpStatus.UNAUTHORIZED.value())
                    .message("A valid API credential is required")
                    .build();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .cacheControl(CacheControl.noStore())
                    .body(error);
        }

        boolean environmentRoot = ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(type);
        ApiKeyIdentityResponse response = new ApiKeyIdentityResponse(
                type,
                id,
                rootCredentialResolver.isConfigured(),
                environmentRoot ? ROOT_CAPABILITIES : DATA_PLANE_CAPABILITIES);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
