package com.springairag.core.controller;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiKeyResponse;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.CollectionIdentityResolver;
import io.swagger.v3.oas.annotations.Operation;
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

    @Operation(summary = "Create a new API key",
               description = "Generates a new API key. The raw key is returned only in this response — save it securely.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "API key created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request",
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
        boolean found = apiKeyService.revokeKey(keyId);
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
        RagApiKey caller = getCaller(request);
        if (caller != null
                && caller.getRole() != ApiKeyRole.ADMIN
                && !keyId.equals(caller.getKeyId())) {
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
     * <p>First checks {@link ApiKeyAuthFilter#AUTHENTICATED_API_KEY_ENTITY} (the full
     * RagApiKey entity set by the filter after DB validation). Falls back to a DB
     * lookup using the String keyId stored in {@link ApiKeyAuthFilter#AUTHENTICATED_KEY_ATTRIBUTE}.
     *
     * <p>Legacy static API keys (configured in application.yml) have no associated entity,
     * so they are treated as NORMAL.
     */
    private ApiKeyRole getCallerRole(HttpServletRequest request) {
        RagApiKey caller = getCaller(request);
        return caller != null && caller.getRole() != null
                ? caller.getRole()
                : ApiKeyRole.NORMAL;
    }

    private RagApiKey getCaller(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object entityAttr = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY);
        return entityAttr instanceof RagApiKey caller ? caller : null;
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
