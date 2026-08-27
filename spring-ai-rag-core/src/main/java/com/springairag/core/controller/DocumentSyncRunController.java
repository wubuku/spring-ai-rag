package com.springairag.core.controller;

import com.springairag.api.contract.DocumentSyncRunLimits;
import com.springairag.api.dto.DocumentSyncRunBatchUpsertRequest;
import com.springairag.api.dto.DocumentSyncRunBatchUpsertResponse;
import com.springairag.api.dto.DocumentSyncRunBeginRequest;
import com.springairag.api.dto.DocumentSyncRunCompleteRequest;
import com.springairag.api.dto.DocumentSyncRunItemPageResponse;
import com.springairag.api.dto.DocumentSyncRunPreviewResponse;
import com.springairag.api.dto.DocumentSyncRunResponse;
import com.springairag.api.dto.DocumentSyncRunStatusResponse;
import com.springairag.api.enums.DocumentSyncItemStatus;
import com.springairag.core.service.DocumentSyncRunService;
import com.springairag.core.versioning.ApiVersion;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * API for authoritative external-source snapshot reconciliation.
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/document-sync-runs")
@Tag(name = "Document Sync Runs",
        description = "Authoritative external document snapshot reconciliation")
public class DocumentSyncRunController {

    public static final String LEASE_HEADER = "X-RAG-Sync-Lease";

    private final DocumentSyncRunService service;

    public DocumentSyncRunController(DocumentSyncRunService service) {
        this.service = service;
    }

    @Operation(summary = "Begin an authoritative document snapshot")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Run created or replayed"),
            @ApiResponse(responseCode = "409", description = "Lease or active-run conflict"),
            @ApiResponse(responseCode = "503", description = "Sync runs are disabled")
    })
    @PostMapping
    @Timed(value = "rag.document-sync-runs.begin",
            description = "Begin document sync run")
    public ResponseEntity<DocumentSyncRunResponse> begin(
            @RequestHeader(LEASE_HEADER) String leaseToken,
            @Valid @RequestBody DocumentSyncRunBeginRequest request) {
        return ResponseEntity.ok(service.begin(request, leaseToken));
    }

    @Operation(summary = "Apply a bounded snapshot item batch")
    @PostMapping("/{runId}/batch-upsert")
    @Timed(value = "rag.document-sync-runs.batch-upsert",
            description = "Apply document sync run batch")
    public ResponseEntity<DocumentSyncRunBatchUpsertResponse> batchUpsert(
            @PathVariable UUID runId,
            @RequestHeader(LEASE_HEADER) String leaseToken,
            @Valid @RequestBody DocumentSyncRunBatchUpsertRequest request) {
        return ResponseEntity.ok(service.batchUpsert(runId, leaseToken, request));
    }

    @Operation(summary = "Preview identities missing from the snapshot")
    @PostMapping("/{runId}/preview-missing")
    @Timed(value = "rag.document-sync-runs.preview",
            description = "Preview missing document identities")
    public ResponseEntity<DocumentSyncRunPreviewResponse> preview(
            @PathVariable UUID runId,
            @RequestHeader(LEASE_HEADER) String leaseToken) {
        return ResponseEntity.ok(service.preview(runId, leaseToken));
    }

    @Operation(summary = "Complete an authoritative snapshot")
    @PostMapping("/{runId}/complete")
    @Timed(value = "rag.document-sync-runs.complete",
            description = "Complete document sync run")
    public ResponseEntity<DocumentSyncRunResponse> complete(
            @PathVariable UUID runId,
            @RequestHeader(LEASE_HEADER) String leaseToken,
            @Valid @RequestBody DocumentSyncRunCompleteRequest request) {
        return ResponseEntity.ok(service.complete(runId, leaseToken, request));
    }

    @Operation(summary = "Abort an authoritative snapshot")
    @PostMapping("/{runId}/abort")
    @Timed(value = "rag.document-sync-runs.abort",
            description = "Abort document sync run")
    public ResponseEntity<DocumentSyncRunResponse> abort(
            @PathVariable UUID runId,
            @RequestHeader(LEASE_HEADER) String leaseToken) {
        return ResponseEntity.ok(service.abort(runId, leaseToken));
    }

    @Operation(summary = "Get one sync run status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Run status returned"),
            @ApiResponse(responseCode = "404", description = "Run not found")
    })
    @GetMapping("/{runId}")
    @Timed(value = "rag.document-sync-runs.get",
            description = "Get document sync run")
    public ResponseEntity<DocumentSyncRunResponse> get(
            @PathVariable UUID runId,
            @RequestParam @NotBlank @Size(min = 1, max = 128)
            String collectionKey,
            @RequestParam(defaultValue = "default")
            @Size(max = 128) String sourceNamespace) {
        return ResponseEntity.ok(service.get(runId, collectionKey, sourceNamespace));
    }

    @Operation(summary = "List durable item receipts for one sync run",
            description = "Terminal runs provide stable cursor traversal. "
                    + "Active runs are eventually consistent and must be "
                    + "rescanned from the beginning after reaching a terminal state.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Item receipt page returned"),
            @ApiResponse(responseCode = "400", description = "Invalid page parameter or cursor"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Read capability or Collection denied"),
            @ApiResponse(responseCode = "404", description = "Bound sync run not found"),
            @ApiResponse(responseCode = "503", description = "Sync runs are disabled")
    })
    @GetMapping("/{runId}/items")
    @Timed(value = "rag.document-sync-runs.items",
            description = "List document sync run item receipts")
    public ResponseEntity<DocumentSyncRunItemPageResponse> listItems(
            @PathVariable UUID runId,
            @Parameter(schema = @Schema(
                    type = "string", minLength = 1, maxLength = 128))
            @RequestParam @NotBlank @Size(min = 1, max = 128)
            String collectionKey,
            @Parameter(schema = @Schema(
                    type = "string", minLength = 0, maxLength = 128))
            @RequestParam(defaultValue = "default")
            @Size(max = 128) String sourceNamespace,
            @RequestParam(required = false) DocumentSyncItemStatus status,
            @Parameter(schema = @Schema(
                    type = "integer",
                    minimum = "1",
                    maximum = DocumentSyncRunLimits.MAX_ITEM_RECEIPT_PAGE_ITEMS_TEXT,
                    defaultValue =
                            DocumentSyncRunLimits.DEFAULT_ITEM_RECEIPT_PAGE_ITEMS_TEXT))
            @RequestParam(
                    defaultValue =
                            DocumentSyncRunLimits.DEFAULT_ITEM_RECEIPT_PAGE_ITEMS_TEXT)
            @Min(1)
            @Max(DocumentSyncRunLimits.MAX_ITEM_RECEIPT_PAGE_ITEMS)
            int limit,
            @Parameter(schema = @Schema(
                    type = "string", minLength = 0, maxLength = 1024))
            @RequestParam(required = false)
            @Size(max = 1024) String cursor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listItems(
                        runId,
                        collectionKey,
                        sourceNamespace,
                        status,
                        limit,
                        cursor));
    }

    @Operation(summary = "List sync runs for an authorized collection")
    @GetMapping
    @Timed(value = "rag.document-sync-runs.list",
            description = "List document sync runs")
    public ResponseEntity<DocumentSyncRunStatusResponse> list(
            @RequestParam @NotBlank @Size(max = 128) String collectionKey,
            @RequestParam(required = false) @Size(max = 128)
            String sourceNamespace,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(schema = @Schema(
                    type = "integer",
                    minimum = "1",
                    maximum = DocumentSyncRunLimits.MAX_RUN_LIST_PAGE_ITEMS_TEXT,
                    defaultValue =
                            DocumentSyncRunLimits.DEFAULT_RUN_LIST_PAGE_ITEMS_TEXT))
            @RequestParam(
                    defaultValue =
                            DocumentSyncRunLimits.DEFAULT_RUN_LIST_PAGE_ITEMS_TEXT)
            @Min(1)
            @Max(DocumentSyncRunLimits.MAX_RUN_LIST_PAGE_ITEMS)
            int size) {
        return ResponseEntity.ok(service.list(
                collectionKey, sourceNamespace, page, size));
    }
}
