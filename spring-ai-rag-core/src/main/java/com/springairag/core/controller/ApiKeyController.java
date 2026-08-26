package com.springairag.core.controller;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiKeyResponse;
import com.springairag.api.dto.ApiPrincipalPolicyUpdateRequest;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.chat.IdempotencyKeyValidator;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.CollectionIdentityResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Collections;

/**
 * API Key management REST controller.
 *
 * <p>Provides CRUD operations for API keys used in programmatic authentication.
 * Raw keys are returned only at creation time — they cannot be retrieved again.
 *
 * <p>配置 environment root 后，只有 root 可调用本控制器；数据库业务 Key只能访问
 * RAG 数据面。未配置 root 时保留 legacy 管理语义。
 */
@RestController
@RequestMapping("/api/v1/rag/api-keys")
@Tag(name = "API Key Management", description = "Create, list, revoke, and rotate API keys")
public class ApiKeyController {

    private final ApiKeyManagementService apiKeyService;
    private final EnvironmentRootCredentialResolver rootCredentialResolver;
    private final CollectionIdentityResolver collectionIdentityResolver;

    public ApiKeyController(ApiKeyManagementService apiKeyService,
                            EnvironmentRootCredentialResolver rootCredentialResolver,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            CollectionIdentityResolver collectionIdentityResolver) {
        this.apiKeyService = apiKeyService;
        this.rootCredentialResolver = rootCredentialResolver;
        this.collectionIdentityResolver = collectionIdentityResolver;
    }

    @Operation(
            summary = "Create a new API key",
            description = "Generates a new API key. The raw key is returned only for the first "
                    + "successful request and is never returned by an idempotent replay.",
            parameters = @Parameter(
                    name = "Idempotency-Key",
                    in = ParameterIn.HEADER,
                    required = false,
                    description = "Optional provisioning idempotency key. Reuse it only with "
                            + "the same normalized request.",
                    schema = @Schema(type = "string", minLength = 1, maxLength = 255)))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Existing provisioning result replayed",
                content = @Content(schema = @Schema(
                        implementation = ApiKeyCreatedResponse.class))),
        @ApiResponse(responseCode = "201", description = "API key created successfully",
                content = @Content(schema = @Schema(
                        implementation = ApiKeyCreatedResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409",
                description = "Idempotency key reused for a different request",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503",
                description = "Provisioning idempotency ledger unavailable or disabled",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<?> createKey(
            @Valid @RequestBody ApiKeyCreateRequest request,
            HttpServletRequest httpRequest) {
        ResponseEntity<ErrorResponse> denied = requireEnvironmentRoot(httpRequest);
        if (denied != null) {
            return denied;
        }
        List<Long> requestedIds = request.getAllowedCollectionIds();
        if (request.getAllowedCollectionKeys() != null) {
            if (collectionIdentityResolver == null) {
                throw new IllegalStateException("Collection key resolver is unavailable");
            }
            List<Long> keyIds = ApiKeyCollectionAccess.resolveCollectionIds(
                    null,
                    request.getAllowedCollectionKeys(),
                    getCaller(httpRequest),
                    collectionIdentityResolver);
            if (requestedIds != null) {
                if (requestedIds.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Allowed collection scope must not be empty");
                }
                List<Long> idIds = ApiKeyCollectionAccess.resolveCollectionIds(
                        requestedIds, getCaller(httpRequest));
                if (!new LinkedHashSet<>(idIds).equals(new LinkedHashSet<>(keyIds))) {
                    throw new IllegalArgumentException(
                            "allowedCollectionIds and allowedCollectionKeys identify different collections");
                }
            }
            requestedIds = keyIds;
        }
        request.setAllowedCollectionIds(
                ApiKeyCollectionAccess.resolveDelegatedAllowedIds(
                        requestedIds, getCaller(httpRequest)));
        String idempotencyKey = IdempotencyKeyValidator.normalize(
                Collections.list(httpRequest.getHeaders("Idempotency-Key")));
        if (idempotencyKey != null) {
            ApiKeyManagementService.ProvisioningResult result =
                    apiKeyService.generateIdempotentKey(
                            request,
                            ApiKeyRole.NORMAL,
                            provisioningOwner(httpRequest),
                            IdempotencyKeyValidator.hash(idempotencyKey),
                            rootCredentialResolver.isConfigured());
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(
                    result.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore());
            if (result.replay()) {
                builder.header("X-RAG-Idempotent-Replay", "true");
            }
            return builder.body(result.response());
        }
        ApiKeyCreatedResponse response = rootCredentialResolver.isConfigured()
                ? apiKeyService.generateManagedKey(request)
                : apiKeyService.generateKey(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @Operation(summary = "List all API keys",
               description = "Returns metadata for all API keys. ADMIN only.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of API keys"),
        @ApiResponse(responseCode = "403", description = "Not an ADMIN key",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<?> listKeys(HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = requireEnvironmentRoot(request);
        if (denied != null) {
            return denied;
        }
        if (!rootCredentialResolver.isConfigured()
                && getCallerRole(request) != ApiKeyRole.ADMIN) {
            return ResponseEntity.status(403)
                    .body(forbidden("Only ADMIN keys can list all API keys"));
        }
        return ResponseEntity.ok(apiKeyService.listKeys());
    }

    @GetMapping("/principals")
    public ResponseEntity<?> listPrincipals(HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = requireEnvironmentRoot(request);
        if (denied != null) {
            return denied;
        }
        if (!rootCredentialResolver.isConfigured()
                && getCallerRole(request) != ApiKeyRole.ADMIN) {
            return ResponseEntity.status(403)
                    .body(forbidden("Only ADMIN keys can list API principals"));
        }
        return ResponseEntity.ok(apiKeyService.listPrincipals());
    }

    @PutMapping("/principals/{principalId}/policy")
    public ResponseEntity<?> updatePolicy(
            @PathVariable String principalId,
            @Valid @RequestBody ApiPrincipalPolicyUpdateRequest policy,
            HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = requireEnvironmentRoot(request);
        if (denied != null) {
            return denied;
        }
        if (!rootCredentialResolver.isConfigured()
                && getCallerRole(request) != ApiKeyRole.ADMIN) {
            return ResponseEntity.status(403)
                    .body(forbidden("Only ADMIN keys can update API principal policy"));
        }
        List<String> requestedKeys = policy.getAllowedCollectionKeys();
        if (requestedKeys != null && requestedKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowedCollectionKeys must be null or contain at least one key");
        }
        List<Long> allowedIds = null;
        if (requestedKeys != null) {
            if (collectionIdentityResolver == null) {
                throw new IllegalStateException("Collection key resolver is unavailable");
            }
            allowedIds = ApiKeyCollectionAccess.resolveDelegatedAllowedKeys(
                    requestedKeys, getCaller(request), collectionIdentityResolver);
        }
        var response = apiKeyService.updatePolicy(
                principalId,
                policy,
                allowedIds,
                rootCredentialResolver.isConfigured());
        return response == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(response);
    }

    @Operation(summary = "Revoke an API key",
               description = "Immediately disables the specified API key. ADMIN only.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Key revoked successfully"),
        @ApiResponse(responseCode = "403", description = "Not an ADMIN key",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Key not found",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{keyId}")
    public ResponseEntity<?> revokeKey(@PathVariable String keyId,
                                        HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = requireEnvironmentRoot(request);
        if (denied != null) {
            return denied;
        }
        if (!rootCredentialResolver.isConfigured()
                && getCallerRole(request) != ApiKeyRole.ADMIN) {
            return ResponseEntity.status(403)
                    .body(forbidden("Only ADMIN keys can revoke API keys"));
        }
        boolean found = rootCredentialResolver.isConfigured()
                ? apiKeyService.revokeManagedKey(keyId)
                : apiKeyService.revokeKey(keyId);
        if (!found) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Rotate an API key",
               description = "Disables the current key and creates a new one with the same name and expiration. Returns the new raw key.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "New key created, old key disabled"),
        @ApiResponse(responseCode = "404", description = "Key not found",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{keyId}/rotate")
    public ResponseEntity<?> rotateKey(@PathVariable String keyId,
                                       HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = requireEnvironmentRoot(request);
        if (denied != null) {
            return denied;
        }
        ApiAccessPolicy caller = getCaller(request);
        if (caller != null
                && caller.getRole() != ApiKeyRole.ADMIN
                && !keyId.equals(caller.getCredentialId())) {
            return ResponseEntity.status(403)
                    .body(forbidden(
                            "NORMAL keys can only rotate themselves"));
        }
        ApiKeyCreatedResponse response = rootCredentialResolver.isConfigured()
                ? apiKeyService.rotateManagedKey(keyId)
                : apiKeyService.rotateKey(keyId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    /**
     * Determine the role of the authenticated caller.
     *
     * Legacy static API keys have no database policy and are treated as NORMAL.
     */
    private ApiKeyRole getCallerRole(HttpServletRequest request) {
        ApiAccessPolicy caller = getCaller(request);
        return caller != null && caller.getRole() != null
                ? caller.getRole()
                : ApiKeyRole.NORMAL;
    }

    private ApiAccessPolicy getCaller(HttpServletRequest request) {
        return ApiKeyCollectionAccess.currentPolicy(request);
    }

    private String provisioningOwner(HttpServletRequest request) {
        Object type = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        Object id = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE);
        if (ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(type)) {
            return "root:environment-root";
        }
        if (ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(type)
                && id instanceof String principalId && !principalId.isBlank()) {
            return "db:" + principalId;
        }
        if (ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC.equals(type)
                || getCaller(request) != null) {
            return "legacy:static";
        }
        return "local:auth-disabled";
    }

    private ResponseEntity<ErrorResponse> requireEnvironmentRoot(
            HttpServletRequest request) {
        if (!rootCredentialResolver.isConfigured()) {
            return null;
        }
        if (Boolean.TRUE.equals(request.getAttribute(
                ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE))) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(forbidden("Only the environment root can manage API keys"));
    }

    private ErrorResponse forbidden(String detail) {
        return ErrorResponse.builder()
                .error("FORBIDDEN")
                .status(HttpStatus.FORBIDDEN.value())
                .message(detail)
                .build();
    }
}
