package com.springairag.core.controller;

import com.springairag.api.dto.CollectionEmbeddingProfileOperationRequest;
import com.springairag.api.dto.LongRunningOperationResponse;
import com.springairag.core.security.ApiRoutePolicy;
import com.springairag.core.service.EmbeddingProfileOperationService;
import com.springairag.core.versioning.ApiVersion;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/** Collection 级 Embedding Profile 切换入口。 */
@RestController
@ApiVersion("v1")
@ApiRoutePolicy
@RequestMapping("/rag/collections/by-key/embedding-profile/operations")
@Tag(name = "Embedding Profiles", description = "Collection Profile migration operations")
public class EmbeddingProfileOperationController {

    private final EmbeddingProfileOperationService service;

    public EmbeddingProfileOperationController(EmbeddingProfileOperationService service) {
        this.service = service;
    }

    @PostMapping
    @Timed("rag.embedding-profile-operation.create")
    @Operation(summary = "Start a durable Collection Embedding Profile switch")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Operation accepted",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = LongRunningOperationResponse.class)),
                    headers = @Header(name = "Location",
                            description = "URL of the durable operation receipt",
                            schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "400", description = "Invalid request or idempotency key"),
            @ApiResponse(responseCode = "403", description = "Collection access denied"),
            @ApiResponse(responseCode = "409", description = "Collection or operation conflict"),
            @ApiResponse(responseCode = "429", description = "Operation capacity exhausted",
                    headers = @Header(name = "Retry-After",
                            description = "Suggested retry delay in seconds",
                            schema = @Schema(type = "integer")))
    })
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER,
            required = true, description = "Caller-generated key reused for exact request replay")
    public ResponseEntity<LongRunningOperationResponse> create(
            @Valid @RequestBody CollectionEmbeddingProfileOperationRequest requestBody,
            HttpServletRequest request) {
        List<String> values = request == null
                ? List.of()
                : Collections.list(request.getHeaders("Idempotency-Key"));
        LongRunningOperationResponse response = service.create(requestBody, values, request);
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, response.statusUrl())
                .body(response);
    }
}
