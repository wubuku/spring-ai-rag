package com.springairag.core.controller;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiKeyResponse;
import com.springairag.api.dto.ApiKeyRotationPrepareRequest;
import com.springairag.api.dto.ApiKeyRotationResponse;
import com.springairag.api.dto.ApiPrincipalPolicyUpdateRequest;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.chat.IdempotencyKeyValidator;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.security.ProvisioningOwnerResolver;
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
import java.util.UUID;

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
    private final CollectionIdentityResolver collectionIdentityResolver;  // Batch 851：`required = false` 已删。Batch 825 移除了那句 `if (x == null) throw`，于是这个参数从一开始就是必填；注解表达的"可能不存在"没有任何代码路径对应，留着只会让读代码的人以为存在一种"解析器缺失"的部署形态
    private final ProvisioningOwnerResolver provisioningOwnerResolver;

    @org.springframework.beans.factory.annotation.Autowired
    public ApiKeyController(ApiKeyManagementService apiKeyService,
                            EnvironmentRootCredentialResolver rootCredentialResolver,
                            CollectionIdentityResolver collectionIdentityResolver,
                            ProvisioningOwnerResolver provisioningOwnerResolver) {
        this.apiKeyService = apiKeyService;
        this.rootCredentialResolver = rootCredentialResolver;
        this.collectionIdentityResolver = collectionIdentityResolver;
        this.provisioningOwnerResolver = provisioningOwnerResolver;
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
            // Batch 825: this used to be
            //   `if (collectionIdentityResolver == null) throw new IllegalStateException(...)`.
            // `CollectionIdentityResolver` is an unconditional @Component, so that
            // branch was a claim about deployment shape that no running application
            // can satisfy — and it pushed a wiring mistake into a 500 with a message
            // about a "resolver" rather than a startup failure. Same rule Batch 822
            // applied to the other throwing guards.
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
                            provisioningOwnerResolver.resolve(httpRequest),
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
                    .body(forbidden("Only ADMIN keys can list all API keys", request));
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
                    .body(forbidden("Only ADMIN keys can list API principals", request));
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
                    .body(forbidden("Only ADMIN keys can update API principal policy", request));
        }
        List<String> requestedKeys = policy.getAllowedCollectionKeys();
        if (requestedKeys != null && requestedKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowedCollectionKeys must be null or contain at least one key");
        }
        List<Long> allowedIds = null;
        if (requestedKeys != null) {
            // Batch 825: the same throwing guard stood here too; see the note at
            // the other call site for why a claim about deployment shape that no
            // running application can satisfy does not belong in the request path.
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
                    .body(forbidden("Only ADMIN keys can revoke API keys", request));
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
                            "NORMAL keys can only rotate themselves", request));
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

    @PostMapping("/{keyId}/rotations")
    public ResponseEntity<?> prepareRotation(
            @PathVariable String keyId,
            @Valid @RequestBody(required = false)
            ApiKeyRotationPrepareRequest body,
            HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = requireEnvironmentRoot(request);
        if (denied != null) {
            return denied;
        }
        String idempotencyKey = IdempotencyKeyValidator.normalize(
                Collections.list(request.getHeaders("Idempotency-Key")));
        if (idempotencyKey == null) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_KEY_INVALID,
                    "Idempotency-Key is required for staged credential rotation");
        }
        ApiKeyManagementService.RotationResult result =
                apiKeyService.prepareRotation(
                        keyId,
                        body == null ? null : body.getOverlapSeconds(),
                        IdempotencyKeyValidator.hash(idempotencyKey),
                        getCaller(request),
                        rootCredentialResolver.isConfigured());
        if (result == null) {
            return ResponseEntity.notFound()
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(
                        result.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore());
        if (result.replay()) {
            builder.header("X-RAG-Idempotent-Replay", "true");
        }
        return builder.body(result.response());
    }

    @GetMapping("/rotations/{rotationId}")
    public ResponseEntity<ApiKeyRotationResponse> getRotation(
            @PathVariable UUID rotationId,
            HttpServletRequest request) {
        requireStagedAccess(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(apiKeyService.getRotation(
                        rotationId,
                        getCaller(request),
                        rootCredentialResolver.isConfigured()));
    }

    @PostMapping("/rotations/{rotationId}/complete")
    public ResponseEntity<ApiKeyRotationResponse> completeRotation(
            @PathVariable UUID rotationId,
            HttpServletRequest request) {
        requireStagedAccess(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(apiKeyService.completeRotation(
                        rotationId,
                        getCaller(request),
                        rootCredentialResolver.isConfigured()));
    }

    @PostMapping("/rotations/{rotationId}/cancel")
    public ResponseEntity<ApiKeyRotationResponse> cancelRotation(
            @PathVariable UUID rotationId,
            HttpServletRequest request) {
        requireStagedAccess(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(apiKeyService.cancelRotation(
                        rotationId,
                        getCaller(request),
                        rootCredentialResolver.isConfigured()));
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

    private ResponseEntity<ErrorResponse> requireEnvironmentRoot(
            HttpServletRequest request) {
        if (!rootCredentialResolver.isConfigured()) {
            return null;
        }
        if (Boolean.TRUE.equals(request.getAttribute(
                ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE))) {
            return null;
        }
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(HttpStatus.FORBIDDEN);
        if (request.getRequestURI().contains("/rotations")) {
            builder.cacheControl(CacheControl.noStore());
        }
        return builder
                .body(forbidden("Only the environment root can manage API keys", request));
    }

    private void requireStagedAccess(HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = requireEnvironmentRoot(request);
        if (denied != null) {
            throw new SecurityException(
                    "Only the environment root can manage API keys");
        }
        if (!rootCredentialResolver.isConfigured() && getCaller(request) == null) {
            throw new SecurityException(
                    "A database-backed ADMIN or owning credential is required");
        }
    }

    /**
     * Batch 873. The `instance` field is the request path; RFC 7807 calls it
     * optional, but {@code ErrorResponse} documents all five standard fields and
     * calls itself the format "all API errors" use. All six call sites already
     * held the request, so this closes the last gap in the contract instead of
     * leaving a sixth shape for a client to discover.
     */
    private ErrorResponse forbidden(String detail, HttpServletRequest request) {
        return ErrorResponse.builder()
                .error("FORBIDDEN")
                .status(HttpStatus.FORBIDDEN.value())
                .message(detail)
                .path(request == null ? null : request.getRequestURI())
                .build();
    }
}
