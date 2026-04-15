package com.springairag.core.controller;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiKeyResponse;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.service.ApiKeyManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API Key management REST controller.
 *
 * <p>Provides CRUD operations for API keys used in programmatic authentication.
 * Raw keys are returned only at creation time — they cannot be retrieved again.
 *
 * <p>Authorization:
 * <ul>
 *   <li>ADMIN keys: can list and revoke any key</li>
 *   <li>NORMAL keys: can create new NORMAL keys (self-service), but cannot list or revoke</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/rag/api-keys")
@Tag(name = "API Key Management", description = "Create, list, revoke, and rotate API keys")
public class ApiKeyController {

    private final ApiKeyManagementService apiKeyService;
    private final RagApiKeyRepository apiKeyRepository;

    public ApiKeyController(ApiKeyManagementService apiKeyService,
                            RagApiKeyRepository apiKeyRepository) {
        this.apiKeyService = apiKeyService;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Operation(summary = "Create a new API key",
               description = "Generates a new API key. The raw key is returned only in this response — save it securely.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "API key created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ApiKeyCreatedResponse> createKey(
            @Valid @RequestBody ApiKeyCreateRequest request) {
        ApiKeyCreatedResponse response = apiKeyService.generateKey(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
        if (getCallerRole(request) != ApiKeyRole.ADMIN) {
            return ResponseEntity.status(403)
                    .body(ErrorResponse.of("Only ADMIN keys can list all API keys"));
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
        if (getCallerRole(request) != ApiKeyRole.ADMIN) {
            return ResponseEntity.status(403)
                    .body(ErrorResponse.of("Only ADMIN keys can revoke API keys"));
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
    public ResponseEntity<ApiKeyCreatedResponse> rotateKey(@PathVariable String keyId) {
        ApiKeyCreatedResponse response = apiKeyService.rotateKey(keyId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
        // Primary: entity set by filter after DB validation
        Object entityAttr = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY);
        if (entityAttr instanceof RagApiKey caller) {
            return caller.getRole();
        }
        // Fallback: legacy static key (no DB entity) → treat as NORMAL
        return ApiKeyRole.NORMAL;
    }
}
