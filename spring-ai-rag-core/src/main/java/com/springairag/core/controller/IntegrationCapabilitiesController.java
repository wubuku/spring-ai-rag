package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.IntegrationCapabilitiesResponse;
import com.springairag.core.service.IntegrationCapabilityCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime integration capability discovery endpoint.
 */
@RestController
@RequestMapping("/api/v1/rag/integration-capabilities")
@Tag(name = "Integration", description = "Runtime integration contracts")
public class IntegrationCapabilitiesController {

    private final IntegrationCapabilityCatalog catalog;

    public IntegrationCapabilitiesController(IntegrationCapabilityCatalog catalog) {
        this.catalog = catalog;
    }

    @Operation(
            summary = "Discover runtime integration capabilities",
            description = "Returns the versioned capabilities and limits visible to the current caller.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Capability contract",
                    content = @Content(schema = @Schema(
                            implementation = IntegrationCapabilitiesResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Capability contract unavailable",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<IntegrationCapabilitiesResponse> capabilities(
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(catalog.describe(request));
    }
}
