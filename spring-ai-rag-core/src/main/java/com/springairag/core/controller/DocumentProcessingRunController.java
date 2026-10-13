package com.springairag.core.controller;

import com.springairag.api.dto.DocumentProcessingRunPageResponse;
import com.springairag.api.dto.DocumentProcessingRunResponse;
import com.springairag.api.enums.DocumentProcessingRunStatus;
import com.springairag.core.documentprocessing.DocumentProcessingRunService;
import com.springairag.core.versioning.ApiVersion;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 文档处理 Run 的查询、取消和重试 API。 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/document-processing-runs")
@Tag(name = "Document Processing Runs",
        description = "Auditable document derivation processing runs")
public class DocumentProcessingRunController {

    private final DocumentProcessingRunService service;

    public DocumentProcessingRunController(DocumentProcessingRunService service) {
        this.service = service;
    }

    @GetMapping("/{runId}")
    @Timed("rag.document-processing-runs.get")
    @Operation(summary = "Get a document processing run")
    public ResponseEntity<DocumentProcessingRunResponse> get(
            @PathVariable UUID runId) {
        return ResponseEntity.ok(service.get(runId));
    }

    @GetMapping
    @Timed("rag.document-processing-runs.list")
    @Operation(summary = "List document processing runs")
    public ResponseEntity<DocumentProcessingRunPageResponse> list(
            @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) String collectionKey,
            @RequestParam(required = false) DocumentProcessingRunStatus status,
            @RequestParam(required = false) Boolean deadLettered,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(service.list(
                documentId, collectionKey, status, deadLettered, page, size));
    }

    @PostMapping("/{runId}/cancel")
    @Timed("rag.document-processing-runs.cancel")
    @Operation(summary = "Cancel a document processing run")
    public ResponseEntity<DocumentProcessingRunResponse> cancel(
            @PathVariable UUID runId) {
        return ResponseEntity.ok(service.cancel(runId));
    }

    @PostMapping("/{runId}/retry")
    @Timed("rag.document-processing-runs.retry")
    @Operation(summary = "Retry a document processing run")
    public ResponseEntity<DocumentProcessingRunResponse> retry(
            @PathVariable UUID runId,
            @RequestParam(required = false) Integer maxAttempts) {
        return ResponseEntity.accepted().body(service.retry(runId, maxAttempts));
    }
}
