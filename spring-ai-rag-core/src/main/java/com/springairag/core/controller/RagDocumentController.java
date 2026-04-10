package com.springairag.core.controller;

import com.springairag.api.dto.BatchCreateAndEmbedRequest;
import com.springairag.api.dto.BatchCreateAndEmbedResponse;
import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.BatchDeleteResponse;
import com.springairag.api.dto.BatchDocumentRequest;
import com.springairag.api.dto.DocumentDeleteResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.FileUploadResponse;
import com.springairag.api.dto.BatchEmbedProgressEvent;
import com.springairag.api.dto.EmbedProgressEvent;
import com.springairag.api.dto.ReembedMissingResponse;
import com.springairag.api.dto.ReembedResultResponse;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.util.DigestUtils;
import com.springairag.core.util.DocumentMapper;
import com.springairag.core.util.SseEmitters;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Document management controller.
 *
 * <p>Provides document CRUD operations and embedding vector management.
 * Business logic delegates to {@link DocumentEmbedService} and {@link BatchDocumentService}.
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/documents")
@Tag(name = "RAG Documents", description = "Document management (CRUD + embedding vector generation)")
public class RagDocumentController {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentController.class);

    private final RagDocumentRepository documentRepository;
    private final RagEmbeddingRepository embeddingRepository;
    private final RagCollectionRepository collectionRepository;
    private final DocumentEmbedService documentEmbedService;
    private final BatchDocumentService batchDocumentService;
    private final DocumentVersionService documentVersionService;
    private AuditLogService auditLogService;  // optional: null when RagAuditLogRepository unavailable

    public RagDocumentController(RagDocumentRepository documentRepository,
                                  RagEmbeddingRepository embeddingRepository,
                                  RagCollectionRepository collectionRepository,
                                  @Lazy DocumentEmbedService documentEmbedService,
                                  @Lazy BatchDocumentService batchDocumentService,
                                  DocumentVersionService documentVersionService,
                                  @Autowired(required = false) AuditLogService auditLogService) {
        this.documentRepository = documentRepository;
        this.embeddingRepository = embeddingRepository;
        this.collectionRepository = collectionRepository;
        this.documentEmbedService = documentEmbedService;
        this.batchDocumentService = batchDocumentService;
        this.documentVersionService = documentVersionService;
        this.auditLogService = auditLogService;
    }

    // ==================== CRUD ====================

    @Operation(summary = "Create document", description = "Upload document content; content hash is computed for deduplication. Embedding vectors must be generated separately via POST /{id}/embed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document created (or duplicate detected)"),
            @ApiResponse(responseCode = "400", description = "Request parameter validation failed")
    })
    @PostMapping
    public ResponseEntity<Map<String, Object>> createDocument(@Valid @RequestBody DocumentRequest request) {
        log.info("Creating document: title={}", request.getTitle());

        String content = request.getContent();
        String contentHash = DigestUtils.sha256(content);

        // Deduplication check
        List<RagDocument> existing = documentRepository.findByContentHash(contentHash);
        if (!existing.isEmpty()) {
            RagDocument dup = existing.get(0);
            log.info("Duplicate content detected: existing doc id={}, hash={}", dup.getId(), contentHash);
            return ResponseEntity.ok(Map.of(
                    "id", dup.getId(),
                    "title", dup.getTitle(),
                    "status", "DUPLICATE",
                    "message", "Content already exists, documentId: " + dup.getId(),
                    "existingDocumentId", dup.getId(),
                    "contentHash", contentHash
            ));
        }

        RagDocument doc = new RagDocument();
        doc.setTitle(request.getTitle());
        doc.setContent(content);
        doc.setSource(request.getSource());
        doc.setDocumentType(request.getDocumentType());
        doc.setMetadata(request.getMetadata());
        doc.setContentHash(contentHash);
        doc = documentRepository.save(doc);

        log.info("Document created: id={}, hash={}", doc.getId(), contentHash);
        auditCreate(AuditLogService.ENTITY_DOCUMENT,
                String.valueOf(doc.getId()),
                "Document created: " + doc.getTitle());

        return ResponseEntity.ok(Map.of(
                "id", doc.getId(),
                "title", doc.getTitle(),
                "status", "CREATED",
                "message", "Document created, to generate embedding call POST /api/v1/rag/documents/{id}/embed",
                "contentHash", contentHash
        ));
    }

    @Operation(summary = "Get document details", description = "Query document content, metadata, and embedding vector count.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document details returned"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getDocument(@PathVariable Long id) {
        log.info("Getting document: id={}", id);

        return documentRepository.findById(id)
                .map(doc -> {
                    Map<String, Object> result = DocumentMapper.toMap(doc, collectionRepository, embeddingRepository);
                    return ResponseEntity.ok(result);
                })
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Operation(summary = "Delete document", description = "Delete document and its associated embedding vectors (cascading delete).")
    @ApiResponse(responseCode = "200", description = "Document deleted")
    @DeleteMapping("/{id}")
    public ResponseEntity<DocumentDeleteResponse> deleteDocument(@PathVariable Long id) {
        return ResponseEntity.ok(batchDocumentService.deleteDocument(id));
    }

    @Operation(summary = "List documents", description = "Paginated document list with filtering by title/type/status and sorting by creation time descending.")
    @ApiResponse(responseCode = "200", description = "Paginated document list returned")
    @GetMapping
    public ResponseEntity<Map<String, Object>> listDocuments(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String processingStatus,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Long collectionId) {

        int page = offset / limit;
        var pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        var pageResult = documentRepository.searchDocuments(
                title, documentType, processingStatus, enabled, collectionId, pageable);

        // Batch-fetch collection names to avoid N+1 queries (one findById per document)
        List<Long> collectionIds = pageResult.getContent().stream()
                .map(RagDocument::getCollectionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> collectionNameMap = collectionIds.isEmpty()
                ? Map.of()
                : collectionRepository.findAllById(collectionIds).stream()
                        .collect(Collectors.toMap(RagCollection::getId, RagCollection::getName));

        List<Map<String, Object>> docs = pageResult.getContent().stream()
                .map(doc -> DocumentMapper.toMap(doc, collectionNameMap, embeddingRepository))
                .toList();

        return ResponseEntity.ok(Map.of(
                "documents", docs,
                "total", pageResult.getTotalElements(),
                "offset", offset,
                "limit", limit
        ));
    }

    @Operation(summary = "Document statistics", description = "Get document count statistics by processing status.")
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getDocumentStats() {
        List<Object[]> statusCounts = documentRepository.countByProcessingStatus();
        Map<String, Long> counts = new HashMap<>();
        long total = 0;
        for (Object[] row : statusCounts) {
            String status = (String) row[0];
            long count = (Long) row[1];
            counts.put(status != null ? status : "UNKNOWN", count);
            total += count;
        }
        return ResponseEntity.ok(Map.of("total", total, "byStatus", counts));
    }

    // ==================== Embedding Vectors ====================

    @Operation(summary = "Generate embedding vectors", description = "Chunk document and generate embedding vectors stored in rag_embeddings. Skips existing embeddings by default; set force=true to re-embed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Embedding vectors generated"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    @PostMapping("/{id}/embed")
    public ResponseEntity<Object> embedDocument(
            @PathVariable Long id,
            @Parameter(description = "Force re-embedding, bypassing the cache")
            @RequestParam(defaultValue = "false") boolean force) {
        try {
            Map<String, Object> result = documentEmbedService.embedDocument(id, force);

            auditCreate(AuditLogService.ENTITY_EMBED_CACHE,
                    String.valueOf(id),
                    "Embed document: id=" + id + ", force=" + force,
                    Map.of("chunks", result.getOrDefault("chunks", 0),
                            "embeddings", result.getOrDefault("embeddings", 0)));

            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
        }
    }

    /**
     * Query embedding vector status.
     */
    @Operation(summary = "Embedding vector status", description = "Query how many documents lack embedding vectors, to help determine if re-embedding is needed")
    @GetMapping("/embed-vector-status")
    public ResponseEntity<Map<String, Object>> embeddingStatus() {
        long total = documentRepository.count();
        long withoutEmbedding = documentRepository.countDocumentsWithoutEmbeddings();
        long withEmbedding = total - withoutEmbedding;
        return ResponseEntity.ok(Map.of(
                "totalDocuments", total,
                "withEmbeddings", withEmbedding,
                "withoutEmbeddings", withoutEmbedding,
                "hasMissing", withoutEmbedding > 0
        ));
    }

    /**
     * Batch re-embed documents lacking embedding vectors.
     */
    @Operation(summary = "Batch re-embed", description = "Automatically find all documents lacking embedding vectors and batch generate/store vectors. Used for data migration fixes or forced re-embedding.")
    @PostMapping("/embed-vector-reembed")
    public ResponseEntity<ReembedMissingResponse> reembedMissing(
            @Parameter(description = "Whether to force re-embedding (skip existing vectors)")
            @RequestParam(defaultValue = "false") boolean force) {
        List<RagDocument> missing = documentRepository.findDocumentsWithoutEmbeddings();
        if (missing.isEmpty()) {
            return ResponseEntity.ok(new ReembedMissingResponse(0, 0, 0, List.of()));
        }

        log.info("Re-embedding {} documents without embeddings (force={})", missing.size(), force);
        List<ReembedResultResponse> results = executeReembeddingBatch(missing, force);

        long success = results.stream().filter(r -> "COMPLETED".equals(r.status())).count();
        long failed = results.size() - success;

        auditCreate(AuditLogService.ENTITY_EMBED_CACHE,
                "batch",
                "Reembed missing: force=" + force,
                Map.of("success", success, "failed", failed, "total", missing.size()));

        return ResponseEntity.ok(new ReembedMissingResponse(
                missing.size(),
                (int) success,
                (int) failed,
                results
        ));
    }

    private List<ReembedResultResponse> executeReembeddingBatch(List<RagDocument> documents, boolean force) {
        List<ReembedResultResponse> results = new ArrayList<>(documents.size());
        for (RagDocument doc : documents) {
            ReembedResultResponse result = buildReembedResult(doc, force);
            results.add(result);
        }
        return results;
    }

    private ReembedResultResponse buildReembedResult(RagDocument doc, boolean force) {
        try {
            Map<String, Object> result = documentEmbedService.embedDocument(doc.getId(), force);
            return new ReembedResultResponse(
                    doc.getId(),
                    doc.getTitle(),
                    String.valueOf(result.getOrDefault("status", "UNKNOWN")),
                    ((Number) result.getOrDefault("chunksCreated", 0)).intValue(),
                    String.valueOf(result.getOrDefault("message", ""))
            );
        } catch (Exception e) {
            log.warn("Failed to re-embed document {}: {}", doc.getId(), e.getMessage());
            return new ReembedResultResponse(
                    doc.getId(),
                    doc.getTitle(),
                    "error",
                    0,
                    e.getMessage()
            );
        }
    }

    /**
     * SSE streaming endpoint for embedding progress.
     *
     * <p>Clients can listen for the following SSE events:
     * <ul>
     *   <li>"progress" — EmbeddingProgressEvent with current stage, processed count, total count</li>
     *   <li>"done"     — Final confirmation with documentId when embedding completes successfully</li>
     *   <li>"error"    — Error details (only sent before completeWithError, not after)</li>
     * </ul>
     *
     * <p>Error handling strategy:
     * <ul>
     *   <li>IllegalArgumentException (e.g., document not found): send "error" event then completeWithError</li>
     *   <li>Other exceptions: only completeWithError (no "error" event, to avoid duplicate payloads)</li>
     *   <li>Callback exceptions during progress: best-effort log (client disconnected, not a real error)</li>
     * </ul>
     *
     * @param id    document ID to embed
     * @param force skip embedding cache and re-embed from scratch
     * @return SSE emitter bound to the request lifecycle
     */
    @Operation(summary = "Generate embeddings via SSE streaming with progress events",
            description = "Similar to POST /embed, but pushes real-time progress via Server-Sent Events. "
                    + "Clients listen for 'progress' events to track current stage, processed count, and total count.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE stream established; progress events will follow"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    @PostMapping("/{id}/embed/stream")
    public SseEmitter embedDocumentStream(
            @PathVariable Long id,
            @Parameter(description = "Force re-embedding, bypassing the cache")
            @RequestParam(defaultValue = "false") boolean force) {
        SseEmitter emitter = SseEmitters.create();
        try {
            documentEmbedService.embedDocumentWithProgress(id, force, event -> {
                SseEmitters.sendProgress(emitter, "progress", event, "document " + id);
            });
            SseEmitters.sendDone(emitter, Map.of("documentId", id));
        } catch (IllegalArgumentException e) {
            SseEmitters.sendError(emitter, e.getMessage(), Map.of("documentId", id));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @Operation(summary = "Generate embedding vectors via VectorStore",
            description = "Use VectorStore.add() to automatically generate and store embeddings with simpler code. Stored in rag_vector_store table. Skips existing embeddings by default; set force=true to re-embed.")
    @PostMapping("/{id}/embed/vs")
    public ResponseEntity<Object> embedDocumentViaVectorStore(
            @PathVariable Long id,
            @Parameter(description = "Force re-embedding, bypassing the cache")
            @RequestParam(defaultValue = "false") boolean force) {
        try {
            Map<String, Object> result = documentEmbedService.embedDocumentViaVectorStore(id, force);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
        }
    }

    // ==================== Batch Operations ====================

    @Operation(summary = "Batch create documents",
               description = "Upload multiple documents at once (up to 100), auto-deduplication."
                           + " Set embed=true to create and embed in one step (no need to call /batch/embed)."
                           + " Single document failure does not affect other documents.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Creation results returned"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters (ids empty or exceeds limit)")
    })
    @PostMapping("/batch")
    public ResponseEntity<BatchCreateResponse> batchCreateDocuments(
            @Valid @RequestBody BatchDocumentRequest request) {
        log.info("Batch create: docs={}, embed={}, collectionId={}, force={}",
                request.getDocuments().size(), request.isEmbed(),
                request.getCollectionId(), request.isForce());

        BatchCreateResponse result = batchDocumentService.batchCreateDocuments(
                request.getDocuments(),
                request.isEmbed(),
                request.getCollectionId(),
                request.isForce());

        auditCreate(AuditLogService.ENTITY_DOCUMENT,
                "batch",
                "Batch create: " + result.created() + " created, "
                        + result.skipped() + " skipped, "
                        + (request.getCollectionId() != null ? "collectionId=" + request.getCollectionId() : "no collection"),
                Map.of("created", result.created(), "skipped", result.skipped(),
                        "collectionId", request.getCollectionId() != null ? request.getCollectionId() : ""));

        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Batch delete documents", description = "Batch delete documents and their embedding vectors by ID list. Missing IDs don't affect other deletions.")
    @DeleteMapping("/batch")
    public ResponseEntity<BatchDeleteResponse> batchDeleteDocuments(
            @RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids list cannot be empty");
        }
        BatchDeleteResponse result = batchDocumentService.batchDeleteDocuments(ids);

        auditDelete(AuditLogService.ENTITY_DOCUMENT,
                "batch:" + ids.size(),
                "Batch delete: " + ids.size() + " documents",
                Map.of("deleted", result.summary().deleted(),
                        "notFound", result.summary().notFound()));

        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Batch generate embedding vectors", description = "Batch chunk and generate embeddings for multiple documents. Single document failure doesn't affect others.")
    @PostMapping("/batch/embed")
    public ResponseEntity<Map<String, Object>> batchEmbedDocuments(
            @RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids list cannot be empty");
        }
        if (ids.size() > 50) {
            throw new IllegalArgumentException("Batch embedding limited to 50 documents per request (API rate limit)");
        }
        Map<String, Object> result = documentEmbedService.batchEmbedDocuments(ids);

        auditCreate(AuditLogService.ENTITY_EMBED_CACHE,
                "batch:" + ids.size(),
                "Batch embed: " + ids.size() + " documents",
                Map.of("succeeded", result.getOrDefault("succeeded", 0),
                        "failed", result.getOrDefault("failed", 0)));

        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Batch generate embeddings via SSE streaming with progress",
            description = "Similar to POST /batch/embed, but pushes real-time progress via Server-Sent Events. "
                    + "Clients listen for 'progress' events to track current document, overall percentage, and counts.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE stream established; progress events will follow")
    })
    @PostMapping("/batch/embed/stream")
    public SseEmitter batchEmbedDocumentsStream(
            @RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids list cannot be empty");
        }
        if (ids.size() > 50) {
            throw new IllegalArgumentException("Batch embedding limited to 50 documents per request (API rate limit)");
        }

        SseEmitter emitter = SseEmitters.create();
        try {
            documentEmbedService.batchEmbedDocumentsWithProgress(ids, event -> {
                SseEmitters.sendProgress(emitter, "progress", event, "batch embed");
            });
            SseEmitters.sendDone(emitter, Map.of("total", ids.size(), "status", "completed"));
        } catch (IllegalArgumentException e) {
            SseEmitters.sendError(emitter, e.getMessage(), Map.of());
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    // ==================== Batch Create and Embed (deprecated, use /batch?embed=true instead) ====================

    /**
     * @deprecated Please use {@link #batchCreateDocuments(BatchDocumentRequest)} with embed=true instead.
     *             Functionality is identical and requires no extra endpoint.
     *             e.g.: POST /batch + body { "documents": [...], "embed": true, "collectionId": 1 }
     */
    @Deprecated
    @Operation(summary = "Batch create and embed documents (deprecated)",
               description = "@deprecated Please use POST /batch + embed=true instead. Functionality is identical.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Creation and embedding results returned"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    })
    @PostMapping("/batch/create-and-embed")
    public ResponseEntity<BatchCreateAndEmbedResponse> batchCreateAndEmbed(
            @Valid @RequestBody BatchCreateAndEmbedRequest request) {
        log.info("Batch create and embed (deprecated): collectionId={}, docs={}, force={}",
                request.getCollectionId(), request.getDocuments().size(), request.isForce());

        // Delegates to service layer (unified embed=true)
        BatchCreateResponse resp = batchDocumentService.batchCreateDocuments(
                request.getDocuments(), true, request.getCollectionId(), request.isForce());

        // Convert to legacy response format
        List<BatchCreateAndEmbedResponse.DocumentResult> results = resp.results().stream()
                .map(r -> new BatchCreateAndEmbedResponse.DocumentResult(
                        r.documentId(), r.title(), r.newlyCreated(), 0, r.error()))
                .toList();

        return ResponseEntity.ok(new BatchCreateAndEmbedResponse(
                resp.created(), resp.created(), resp.skipped(), resp.failed(), results));
    }

    // ==================== File Upload and Embed ====================

    @Operation(summary = "Upload file and embed", description = "Upload text files (txt/md etc.) and auto-create document with embedding vectors.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "File processing completed"),
            @ApiResponse(responseCode = "400", description = "No file or unsupported file format")
    })
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileUploadResponse> uploadAndEmbed(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "collectionId", required = false) Long collectionId,
            @RequestParam(value = "force", defaultValue = "false") boolean force) {

        log.info("File upload request: {} files, collectionId={}", files.length, collectionId);

        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(
                    new FileUploadResponse(0, 0, 0, List.of(
                            new FileUploadResponse.FileResult("N/A", null, null, false, 0, "No file uploaded"))));
        }

        List<FileUploadResponse.FileResult> results = new java.util.ArrayList<>();
        int success = 0, failed = 0;

        for (MultipartFile file : files) {
            FileUploadResponse.FileResult result = processUploadedFile(file, collectionId, force);
            results.add(result);
            if (result.error() == null) {
                success++;
            } else {
                failed++;
            }
        }

        log.info("File upload completed: {} success, {} failed", success, failed);

        auditCreate(AuditLogService.ENTITY_DOCUMENT,
                "upload:" + files.length,
                "File upload: " + files.length + " files, success=" + success + ", failed=" + failed,
                Map.of("total", files.length, "success", success, "failed", failed,
                        "collectionId", collectionId != null ? collectionId : ""));

        return ResponseEntity.ok(new FileUploadResponse(files.length, success, failed, results));
    }

    private FileUploadResponse.FileResult processUploadedFile(
            MultipartFile file, Long collectionId, boolean force) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "unnamed";
        }

        try {
            FileValidationResult validation = validateTextFile(file, filename);
            if (validation.errorMessage != null) {
                return new FileUploadResponse.FileResult(
                        filename, null, null, false, 0, validation.errorMessage);
            }

            FileContentResult content = readFileContent(file, filename);
            if (content.errorMessage != null) {
                return new FileUploadResponse.FileResult(
                        filename, null, null, false, 0, content.errorMessage);
            }

            DocumentRequest docReq = new DocumentRequest(content.title, content.content);
            if (collectionId != null) {
                docReq.setCollectionId(collectionId);
            }

            BatchCreateResponse resp = batchDocumentService.batchCreateDocuments(
                    List.of(docReq), true, collectionId, force);
            BatchCreateResponse.DocumentResult r = resp.results().getFirst();

            if (r.documentId() != null) {
                return new FileUploadResponse.FileResult(filename, r.documentId(), content.title, true, 0, null);
            } else {
                return new FileUploadResponse.FileResult(
                        filename, null, content.title, false, 0,
                        r.error() != null ? r.error() : "Creation failed");
            }

        } catch (Exception e) {
            log.error("Failed to process uploaded file '{}': {}", filename, e.getMessage());
            return new FileUploadResponse.FileResult(
                    filename, null, null, false, 0,
                    "Processing failed: " + e.getMessage());
        }
    }

    private record FileValidationResult(boolean isText, String extension, String errorMessage) {}

    private FileValidationResult validateTextFile(MultipartFile file, String filename) {
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
        }

        String contentType = file.getContentType();
        boolean isText = contentType != null && (
                contentType.startsWith("text/") ||
                contentType.equals("application/json") ||
                contentType.equals("application/xml") ||
                contentType.equals("application/javascript") ||
                extension.equals("txt") || extension.equals("md") ||
                extension.equals("markdown") || extension.equals("json") ||
                extension.equals("xml") || extension.equals("html") ||
                extension.equals("csv") || extension.equals("log")
        );

        if (!isText && !file.isEmpty()) {
            try {
                file.getBytes();
            } catch (Exception e) {
                return new FileValidationResult(false, extension,
                        "Unsupported file type: " + contentType + ", only text files supported (txt/md/json/xml/html/csv/log)");
            }
        }
        return new FileValidationResult(isText, extension, null);
    }

    private record FileContentResult(String title, String content, String errorMessage) {}

    private FileContentResult readFileContent(MultipartFile file, String filename) {
        try {
            String content = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return new FileContentResult(null, null, "File content is empty");
            }

            String title = filename;
            if (title.toLowerCase().endsWith(".txt") || title.toLowerCase().endsWith(".md")) {
                title = title.substring(0, title.lastIndexOf('.'));
            }
            return new FileContentResult(title, content, null);
        } catch (Exception e) {
            return new FileContentResult(null, null, "Failed to read file: " + e.getMessage());
        }
    }

    // ==================== Version History ====================

    @Operation(summary = "Get document version history", description = "Paginated version history of document content changes, newest first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Version history returned"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    @GetMapping("/{id}/versions")
    public ResponseEntity<Map<String, Object>> getVersionHistory(
            @Parameter(description = "Document ID") @PathVariable Long id,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {

        if (!documentRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        var versions = documentVersionService.getVersionHistory(id, PageRequest.of(page, size));
        Map<String, Object> result = new HashMap<>();
        result.put("documentId", id);
        result.put("totalVersions", versions.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        result.put("versions", versions.getContent().stream().map(DocumentMapper::toVersionMap).toList());
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Get specific version", description = "Query specific version details of a document (including content snapshot).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Version details returned"),
            @ApiResponse(responseCode = "404", description = "Version not found")
    })
    @GetMapping("/{id}/versions/{versionNumber}")
    public ResponseEntity<Map<String, Object>> getVersion(
            @Parameter(description = "Document ID") @PathVariable Long id,
            @Parameter(description = "Version number") @PathVariable int versionNumber) {

        return documentVersionService.getVersion(id, versionNumber)
                .map(v -> ResponseEntity.ok(DocumentMapper.toVersionMap(v)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Audit Logging Helpers ====================

    // Null-safe audit logging helpers (AuditLogService is optional)
    private void auditCreate(String entityType, String entityId, String message) {
        if (auditLogService != null) auditLogService.logCreate(entityType, entityId, message);
    }
    private void auditCreate(String entityType, String entityId, String message, Map<String, Object> details) {
        if (auditLogService != null) auditLogService.logCreate(entityType, entityId, message, details);
    }
    private void auditDelete(String entityType, String entityId, String message) {
        if (auditLogService != null) auditLogService.logDelete(entityType, entityId, message);
    }
    private void auditDelete(String entityType, String entityId, String message, Map<String, Object> details) {
        if (auditLogService != null) auditLogService.logDelete(entityType, entityId, message, details);
    }
}
