package com.springairag.core.controller;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiKeyResponse;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.service.ApiKeyManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 */
@RestController
@RequestMapping("/api/v1/rag/api-keys")
@Tag(name = "API Key Management", description = "Create, list, revoke, and rotate API keys")
public class ApiKeyController {

    private final ApiKeyManagementService apiKeyService;

    public ApiKeyController(ApiKeyManagementService apiKeyService) {
        this.apiKeyService = apiKeyService;
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
               description = "Returns metadata for all API keys. Raw keys are never included.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of API keys")
    })
    @GetMapping
    public ResponseEntity<List<ApiKeyResponse>> listKeys() {
        return ResponseEntity.ok(apiKeyService.listKeys());
    }

    @Operation(summary = "Revoke an API key",
               description = "Immediately disables the specified API key. This action cannot be undone.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Key revoked successfully"),
        @ApiResponse(responseCode = "404", description = "Key not found",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revokeKey(@PathVariable String keyId) {
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
}
