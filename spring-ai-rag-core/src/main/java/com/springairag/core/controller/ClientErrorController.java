package com.springairag.core.controller;

import com.springairag.api.dto.ClientErrorRequest;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.service.ClientErrorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rag/client-errors")
@Tag(name = "Client Errors", description = "Client-side error reporting from WebUI")
public class ClientErrorController {

    private static final Logger log = LoggerFactory.getLogger(ClientErrorController.class);

    private final ClientErrorService clientErrorService;

    public ClientErrorController(ClientErrorService clientErrorService) {
        this.clientErrorService = clientErrorService;
    }

    @PostMapping
    @Operation(
        summary = "Report a client-side error",
        description = "Receives and records client-side errors from the WebUI for server-side aggregation and analysis"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Error accepted and recorded"),
        @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    public ResponseEntity<Void> reportError(
            @Valid @RequestBody ClientErrorRequest request,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {

        if (userAgent != null && request.getUserAgent() == null) {
            request.setUserAgent(userAgent);
        }

        clientErrorService.recordError(request);

        return ResponseEntity.accepted().build();
    }

    @GetMapping("/count")
    @Operation(summary = "Get total client error count")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Total error count returned")
    })
    public ResponseEntity<ErrorResponse> getErrorCount() {
        long count = clientErrorService.getErrorCount();
        return ResponseEntity.ok(ErrorResponse.of("Total client errors: " + count));
    }
}
