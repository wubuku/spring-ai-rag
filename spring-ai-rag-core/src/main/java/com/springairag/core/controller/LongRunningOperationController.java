package com.springairag.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.springairag.api.dto.LongRunningOperationResponse;
import com.springairag.core.security.ApiRoutePolicy;
import com.springairag.core.service.LongRunningOperationService;
import com.springairag.core.versioning.ApiVersion;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 跨文档、持久化 Collection Operation 的客户端控制面。 */
@RestController
@ApiVersion("v1")
@ApiRoutePolicy
@RequestMapping("/rag/operations")
@Tag(name = "Long-running Operations", description = "Durable Collection operations")
public class LongRunningOperationController {

    private final LongRunningOperationService service;

    public LongRunningOperationController(LongRunningOperationService service) {
        this.service = service;
    }

    @GetMapping("/{operationId}")
    @Timed("rag.operations.get")
    @Operation(summary = "Get a durable operation receipt")
    public ResponseEntity<LongRunningOperationResponse> get(
            @PathVariable UUID operationId,
            HttpServletRequest request) {
        return ResponseEntity.ok(service.get(operationId, request));
    }

    @GetMapping("/{operationId}/result")
    @Timed("rag.operations.result")
    @Operation(summary = "Get a completed durable operation result")
    public ResponseEntity<JsonNode> result(
            @PathVariable UUID operationId,
            HttpServletRequest request) {
        return ResponseEntity.ok(service.getResult(operationId, request));
    }

    @PostMapping("/{operationId}/cancel")
    @Timed("rag.operations.cancel")
    @Operation(summary = "Request cancellation of a durable operation")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Cancellation accepted",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = LongRunningOperationResponse.class))),
            @ApiResponse(responseCode = "403", description = "Operation access denied"),
            @ApiResponse(responseCode = "404", description = "Operation not found"),
            @ApiResponse(responseCode = "409", description = "Operation state conflict")
    })
    public ResponseEntity<LongRunningOperationResponse> cancel(
            @PathVariable UUID operationId,
            HttpServletRequest request) {
        return ResponseEntity.accepted().body(service.cancel(operationId, request));
    }

    @PostMapping("/{operationId}/retry")
    @Timed("rag.operations.retry")
    @Operation(summary = "Retry a recoverable durable operation")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Retry accepted",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = LongRunningOperationResponse.class))),
            @ApiResponse(responseCode = "403", description = "Operation access denied"),
            @ApiResponse(responseCode = "404", description = "Operation not found"),
            @ApiResponse(responseCode = "409", description = "Operation state conflict"),
            @ApiResponse(responseCode = "429", description = "Operation capacity exhausted",
                    headers = @Header(name = "Retry-After",
                            description = "Suggested retry delay in seconds",
                            schema = @Schema(type = "integer")))
    })
    public ResponseEntity<LongRunningOperationResponse> retry(
            @PathVariable UUID operationId,
            HttpServletRequest request) {
        return ResponseEntity.accepted().body(service.retry(operationId, request));
    }
}
