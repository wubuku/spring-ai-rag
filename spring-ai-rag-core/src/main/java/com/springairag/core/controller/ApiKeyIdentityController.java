package com.springairag.core.controller;

import com.springairag.api.dto.ApiKeyIdentityResponse;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.enums.CollectionAccessMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.service.CollectionIdentityResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * WebUI 解锁时使用的当前 API principal 探测端点。
 */
@RestController
@RequestMapping("/api/v1/rag/auth")
public class ApiKeyIdentityController {

    private static final List<String> ROOT_CAPABILITIES =
            List.of("RAG_READ", "RAG_WRITE", "API_KEY_MANAGE");

    private final EnvironmentRootCredentialResolver rootCredentialResolver;
    private final CollectionIdentityResolver collectionIdentityResolver;

    public ApiKeyIdentityController(
            EnvironmentRootCredentialResolver rootCredentialResolver,
            CollectionIdentityResolver collectionIdentityResolver) {
        this.rootCredentialResolver = rootCredentialResolver;
        this.collectionIdentityResolver = collectionIdentityResolver;
    }

    @Operation(summary = "Inspect the current authenticated API principal")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Current principal and its effective Collection access",
                    content = @Content(schema = @Schema(
                            implementation = ApiKeyIdentityResponse.class))),
            @ApiResponse(
                    responseCode = "401",
                    description = "Missing or invalid API credential",
                    content = @Content(schema = @Schema(
                            implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "503",
                    description = "The current principal policy cannot be resolved completely",
                    content = @Content(schema = @Schema(
                            implementation = ErrorResponse.class)))
    })
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
                environmentRoot
                        ? ROOT_CAPABILITIES
                        : ApiCapabilitySupport.fullCapabilities());
        Object authenticated = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE);
        if (ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(type)) {
            if (!(authenticated instanceof AuthenticatedApiPrincipal principal)
                    || !id.equals(principal.getPrincipalId())
                    || !type.equals(principal.principalType())) {
                return unauthorized();
            }
            ResponseEntity<ErrorResponse> policyError =
                    populateDatabasePolicy(response, principal);
            if (policyError != null) {
                return policyError;
            }
        } else if (environmentRoot
                || ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC.equals(type)) {
            response.setPrincipalRole(null);
            response.setCollectionAccessMode(CollectionAccessMode.UNRESTRICTED);
            response.setAllowedCollectionKeys(null);
        } else {
            return unauthorized();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    private ResponseEntity<ErrorResponse> populateDatabasePolicy(
            ApiKeyIdentityResponse response,
            AuthenticatedApiPrincipal principal) {
        response.setCredentialId(principal.getCredentialId());
        response.setCredentialVersion(principal.getCredentialVersion());
        response.setPolicyVersion(principal.getPolicyVersion());
        response.setPrincipalRole(principal.getRole().name());
        response.setCapabilities(ApiCapabilitySupport.effectiveForRole(
                principal.getRole(), principal.getCapabilities()));
        if (ApiKeyCollectionAccess.isUnrestricted(principal)) {
            response.setCollectionAccessMode(CollectionAccessMode.UNRESTRICTED);
            response.setAllowedCollectionKeys(null);
            return null;
        }

        try {
            List<Long> allowedIds = ApiKeyCollectionAccess.parseAllowedIds(
                    principal.getAllowedCollectionIds());
            Map<Long, String> keysById = collectionIdentityResolver.mapKeys(allowedIds);
            if (keysById.size() != allowedIds.size()) {
                return policyUnavailable();
            }
            response.setCollectionAccessMode(CollectionAccessMode.RESTRICTED);
            response.setAllowedCollectionKeys(
                    allowedIds.stream().map(keysById::get).toList());
            return null;
        } catch (IllegalStateException | DataAccessException error) {
            return policyUnavailable();
        }
    }

    private ResponseEntity<ErrorResponse> unauthorized() {
        ErrorResponse error = ErrorResponse.builder()
                .error("UNAUTHORIZED")
                .status(HttpStatus.UNAUTHORIZED.value())
                .message("A valid API credential is required")
                .build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .body(error);
    }

    private ResponseEntity<ErrorResponse> policyUnavailable() {
        ErrorCode code = ErrorCode.SERVICE_UNAVAILABLE;
        ErrorResponse error = ErrorResponse.builder()
                .error(code.getCode())
                .status(code.getHttpStatus())
                .detail("The current API principal policy cannot be resolved completely")
                .build();
        error.setTitle(code.getTitle());
        return ResponseEntity.status(code.getHttpStatus())
                .cacheControl(CacheControl.noStore())
                .body(error);
    }
}
