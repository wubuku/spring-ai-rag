package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.IntegrationObservabilityResponse;
import com.springairag.core.observability.IntegrationObservabilityQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bounded, principal-scoped operation rollup query for integration operators.
 */
@RestController
@RequestMapping("/api/v1/rag/integration-observability")
@Tag(name = "Integration", description = "Runtime integration contracts")
public class IntegrationObservabilityController {

    private final IntegrationObservabilityQueryService queryService;

    public IntegrationObservabilityController(
            IntegrationObservabilityQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(
            summary = "Query integration operation observability",
            description = "Returns bounded UTC rollups. Normal principals are restricted to "
                    + "their own current Collection scope; root and ADMIN principals may query "
                    + "the global or a selected principal scope.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Aggregate observability result",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(
                            implementation = IntegrationObservabilityResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid query window or filter",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(
                            implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Scope is not authorized",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(
                            implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Observability is disabled or "
                    + "the current authorization scope cannot be resolved",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(
                            implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<IntegrationObservabilityResponse> query(
            @Parameter(description = "Inclusive ISO-8601 instant; defaults to 24 hours before to")
            @RequestParam(required = false) String from,
            @Parameter(description = "Exclusive ISO-8601 instant; defaults to now")
            @RequestParam(required = false) String to,
            @Parameter(description = "HOUR or DAY; defaults to HOUR")
            @RequestParam(required = false) String bucket,
            @Parameter(description = "Fixed IntegrationOperation name")
            @RequestParam(required = false) String operation,
            @Parameter(description = "Current active Collection key filter")
            @RequestParam(required = false) String collectionKey,
            @Parameter(description = "Stable database principal ID; root/ADMIN only")
            @RequestParam(required = false) String principalId,
            HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(queryService.query(
                        request,
                        from,
                        to,
                        bucket,
                        operation,
                        collectionKey,
                        principalId));
    }
}
