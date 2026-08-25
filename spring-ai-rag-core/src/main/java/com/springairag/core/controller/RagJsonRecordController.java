package com.springairag.core.controller;

import com.springairag.api.dto.JsonRecordBatchUpsertRequest;
import com.springairag.api.dto.JsonRecordBatchUpsertResponse;
import com.springairag.api.dto.JsonRecordDetailResponse;
import com.springairag.api.dto.JsonRecordSearchRequest;
import com.springairag.api.dto.JsonRecordSearchResponse;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertResponse;
import com.springairag.api.dto.ExternalDocumentDeleteResponse;
import com.springairag.api.validation.ValidCollectionKey;
import com.springairag.api.validation.ValidSourceNamespace;
import com.springairag.core.service.JsonRecordService;
import com.springairag.core.versioning.ApiVersion;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Dedicated API for JSONB structured records.
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/json-records")
@Validated
@Tag(name = "RAG JSON Records", description = "Structured JSONB records with caller-supplied retrieval text")
public class RagJsonRecordController {

    private final JsonRecordService jsonRecordService;

    public RagJsonRecordController(JsonRecordService jsonRecordService) {
        this.jsonRecordService = jsonRecordService;
    }

    @Operation(summary = "Upsert a JSON structured record",
            description = "Persists jsonbPayload by collection/externalId and embeds retrievalText only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Record persisted"),
            @ApiResponse(responseCode = "400", description = "Invalid JSON record"),
            @ApiResponse(responseCode = "403", description = "Collection access denied"),
            @ApiResponse(responseCode = "409", description = "Structured record conflict")
    })
    @PostMapping("/upsert")
    @Timed(value = "rag.json-records.upsert", description = "Upsert JSON structured record")
    public ResponseEntity<JsonRecordUpsertResponse> upsert(
            @Valid @RequestBody JsonRecordUpsertRequest request) {
        return ResponseEntity.ok(jsonRecordService.upsert(request));
    }

    @Operation(summary = "Batch upsert JSON structured records",
            description = "Processes each item independently and preserves input order.")
    @PostMapping("/batch-upsert")
    @Timed(value = "rag.json-records.batch-upsert", description = "Batch upsert JSON structured records")
    public ResponseEntity<JsonRecordBatchUpsertResponse> batchUpsert(
            @Valid @RequestBody JsonRecordBatchUpsertRequest request) {
        return ResponseEntity.ok(jsonRecordService.batchUpsert(request.getItems()));
    }

    @Operation(summary = "Search JSON structured records",
            description = "Collection-scoped hybrid retrieval with optional reranking and JSONB enrichment.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Search results returned"),
            @ApiResponse(responseCode = "400", description = "Invalid search request"),
            @ApiResponse(responseCode = "403", description = "Collection access denied")
    })
    @PostMapping("/search")
    @Timed(value = "rag.json-records.search", description = "Search JSON structured records")
    public ResponseEntity<JsonRecordSearchResponse> search(
            @Valid @RequestBody JsonRecordSearchRequest request) {
        return ResponseEntity.ok(jsonRecordService.search(request));
    }

    @Operation(summary = "Get a JSON structured record",
            description = "Returns the current retrieval text and JSONB payload by document ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Record returned"),
            @ApiResponse(responseCode = "403", description = "Document access denied"),
            @ApiResponse(responseCode = "404", description = "JSON record not found")
    })
    @GetMapping("/{documentId}")
    @Timed(value = "rag.json-records.detail", description = "Get JSON structured record")
    public ResponseEntity<JsonRecordDetailResponse> getDetail(
            @PathVariable Long documentId) {
        return ResponseEntity.ok(jsonRecordService.getDetail(documentId));
    }

    @Operation(summary = "Get a JSON record by external source identity")
    @GetMapping("/by-external-id")
    public ResponseEntity<JsonRecordDetailResponse> getByExternalIdentity(
            @Parameter(
                    description = "Stable target Collection key",
                    required = true,
                    schema = @Schema(
                            minLength = 1,
                            maxLength = 128,
                            pattern = "^[\\x21-\\x7e]+$"))
            @RequestParam @ValidCollectionKey String collectionKey,
            @Parameter(
                    description = "External source namespace; omitted or blank values normalize to default",
                    schema = @Schema(
                            defaultValue = "default",
                            minLength = 0,
                            maxLength = 128,
                            pattern = "^(?:\\s*|[\\x20-\\x7e]+)$"))
            @RequestParam(defaultValue = "default")
            @Size(max = 128) @ValidSourceNamespace String sourceNamespace,
            @Parameter(
                    description = "Caller-supplied opaque external identity",
                    required = true,
                    schema = @Schema(minLength = 1, maxLength = 255))
            @RequestParam @NotBlank @Size(min = 1, max = 255) String externalId) {
        return ResponseEntity.ok(jsonRecordService.getByExternalIdentity(
                collectionKey, sourceNamespace, externalId));
    }

    @Operation(summary = "Tombstone a JSON record by external source identity")
    @DeleteMapping("/by-external-id")
    public ResponseEntity<ExternalDocumentDeleteResponse> sourceDelete(
            @Parameter(
                    description = "Stable target Collection key",
                    required = true,
                    schema = @Schema(
                            minLength = 1,
                            maxLength = 128,
                            pattern = "^[\\x21-\\x7e]+$"))
            @RequestParam @ValidCollectionKey String collectionKey,
            @Parameter(
                    description = "External source namespace; omitted or blank values normalize to default",
                    schema = @Schema(
                            defaultValue = "default",
                            minLength = 0,
                            maxLength = 128,
                            pattern = "^(?:\\s*|[\\x20-\\x7e]+)$"))
            @RequestParam(defaultValue = "default")
            @Size(max = 128) @ValidSourceNamespace String sourceNamespace,
            @Parameter(
                    description = "Caller-supplied opaque external identity",
                    required = true,
                    schema = @Schema(minLength = 1, maxLength = 255))
            @RequestParam @NotBlank @Size(min = 1, max = 255) String externalId,
            @Parameter(
                    description = "New opaque source revision for the tombstone",
                    required = true,
                    schema = @Schema(minLength = 1, maxLength = 255))
            @RequestParam @NotBlank @Size(min = 1, max = 255) String sourceRevision,
            @Parameter(
                    description = "Expected current source revision for compare-and-set",
                    schema = @Schema(minLength = 0, maxLength = 255))
            @RequestParam(required = false)
            @Size(max = 255) String expectedSourceRevision) {
        return ResponseEntity.ok(jsonRecordService.sourceDelete(
                collectionKey, sourceNamespace, externalId,
                sourceRevision, expectedSourceRevision));
    }
}
