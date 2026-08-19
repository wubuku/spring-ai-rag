package com.springairag.core.controller;

import com.springairag.api.dto.BatchCreateAndEmbedRequest;
import com.springairag.api.dto.BatchCreateAndEmbedResponse;
import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.BatchDeleteResponse;
import com.springairag.api.dto.BatchDocumentRequest;
import com.springairag.api.dto.BatchEmbedResponse;
import com.springairag.api.dto.DocumentCreateResponse;
import com.springairag.api.dto.DocumentDeleteResponse;
import com.springairag.api.dto.DocumentDisableRequest;
import com.springairag.api.dto.DocumentDetailResponse;
import com.springairag.api.dto.DocumentListResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.DocumentRestoreRequest;
import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentUpdateRequest;
import com.springairag.api.dto.DocumentStatsResponse;
import com.springairag.api.dto.DocumentSummary;
import com.springairag.api.dto.EmbeddingStatusResponse;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.FileUploadResponse;
import com.springairag.api.dto.VersionHistoryResponse;
import com.springairag.api.dto.DocumentVersionResponse;
import com.springairag.api.dto.BatchEmbedProgressEvent;
import com.springairag.api.dto.EmbedProgressEvent;
import com.springairag.api.dto.ReembedMissingResponse;
import com.springairag.api.dto.ReembedResultResponse;
import com.springairag.api.dto.ExternalDocumentBatchUpsertRequest;
import com.springairag.api.dto.ExternalDocumentBatchUpsertResponse;
import com.springairag.api.dto.ExternalDocumentDeleteResponse;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.dto.ExternalDocumentUpsertResponse;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.service.DocumentLifecycleService;
import com.springairag.core.service.DocumentMutationService;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.embeddingjob.EmbeddingPolicyResolver;
import com.springairag.core.embeddingjob.EmbeddingPolicySupport;
import com.springairag.core.service.ExternalDocumentService;
import com.springairag.core.util.DocumentMapper;
import com.springairag.core.util.SseEmitters;
import com.springairag.core.versioning.ApiVersion;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
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
@Validated
@Tag(name = "RAG Documents", description = "Document management (CRUD + embedding vector generation)")
public class RagDocumentController {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentController.class);

    private final RagDocumentRepository documentRepository;
    private final RagEmbeddingRepository embeddingRepository;
    private final RagCollectionRepository collectionRepository;
    private final DocumentEmbedService documentEmbedService;
    private final BatchDocumentService batchDocumentService;
    private final DocumentVersionService documentVersionService;
    private final EmbeddingProfileProvider embeddingProfileProvider;
    private final CollectionIdentityResolver collectionIdentityResolver;
    private AuditLogService auditLogService;  // optional: null when RagAuditLogRepository unavailable
    private ExternalDocumentService externalDocumentService;
    private EmbeddingDispatchService dispatchService;
    private DocumentMutationService documentMutationService;
    private DocumentLifecycleService documentLifecycleService;
    private DocumentDerivationDescriptorProvider derivationDescriptorProvider;

    @Autowired
    public RagDocumentController(RagDocumentRepository documentRepository,
                                  RagEmbeddingRepository embeddingRepository,
                                  RagCollectionRepository collectionRepository,
                                  @Lazy DocumentEmbedService documentEmbedService,
                                  @Lazy BatchDocumentService batchDocumentService,
                                  DocumentVersionService documentVersionService,
                                  EmbeddingProfileProvider embeddingProfileProvider,
                                  CollectionIdentityResolver collectionIdentityResolver,
                                  @Autowired(required = false) AuditLogService auditLogService) {
        this.documentRepository = documentRepository;
        this.embeddingRepository = embeddingRepository;
        this.collectionRepository = collectionRepository;
        this.documentEmbedService = documentEmbedService;
        this.batchDocumentService = batchDocumentService;
        this.documentVersionService = documentVersionService;
        this.embeddingProfileProvider = embeddingProfileProvider;
        this.collectionIdentityResolver = collectionIdentityResolver;
        this.auditLogService = auditLogService;
    }

    @Autowired(required = false)
    public void setExternalDocumentService(ExternalDocumentService externalDocumentService) {
        this.externalDocumentService = externalDocumentService;
    }

    @Autowired(required = false)
    public void setDispatchService(EmbeddingDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Autowired(required = false)
    public void setDocumentMutationService(
            DocumentMutationService documentMutationService) {
        this.documentMutationService = documentMutationService;
    }

    @Autowired(required = false)
    public void setDocumentLifecycleService(
            DocumentLifecycleService documentLifecycleService) {
        this.documentLifecycleService = documentLifecycleService;
    }

    @Autowired(required = false)
    public void setDerivationDescriptorProvider(
            DocumentDerivationDescriptorProvider derivationDescriptorProvider) {
        this.derivationDescriptorProvider = derivationDescriptorProvider;
    }

    public RagDocumentController(RagDocumentRepository documentRepository,
                                 RagEmbeddingRepository embeddingRepository,
                                 RagCollectionRepository collectionRepository,
                                 @Lazy DocumentEmbedService documentEmbedService,
                                 @Lazy BatchDocumentService batchDocumentService,
                                 DocumentVersionService documentVersionService,
                                 EmbeddingProfileProvider embeddingProfileProvider,
                                 AuditLogService auditLogService) {
        this(documentRepository, embeddingRepository, collectionRepository,
                documentEmbedService, batchDocumentService, documentVersionService,
                embeddingProfileProvider,
                new CollectionIdentityResolver(collectionRepository),
                auditLogService);
    }

    @Operation(summary = "Upsert an externally managed document",
            description = "Idempotently create or update an ordinary document by collectionKey and externalId.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document persisted"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "403", description = "Collection access denied"),
            @ApiResponse(responseCode = "409", description = "Source revision conflict")
    })
    @PostMapping("/upsert")
    @Timed(value = "rag.documents.external-upsert", description = "Upsert external document")
    public ResponseEntity<ExternalDocumentUpsertResponse> upsertExternalDocument(
            @Valid @RequestBody ExternalDocumentUpsertRequest request) {
        return ResponseEntity.ok(requireExternalDocumentService().upsert(request));
    }

    @Operation(summary = "Batch upsert externally managed documents",
            description = "Processes each external document independently and preserves input order.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch processed"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "403", description = "Collection access denied")
    })
    @PostMapping("/batch-upsert")
    @Timed(value = "rag.documents.external-batch-upsert",
            description = "Batch upsert external documents")
    public ResponseEntity<ExternalDocumentBatchUpsertResponse> batchUpsertExternalDocuments(
            @Valid @RequestBody ExternalDocumentBatchUpsertRequest request) {
        return ResponseEntity.ok(requireExternalDocumentService().batchUpsert(request.getItems()));
    }

    @Operation(summary = "Get an externally managed document by source identity",
            description = "Returns the current document state by collectionKey and externalId.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document returned"),
            @ApiResponse(responseCode = "400", description = "Invalid query"),
            @ApiResponse(responseCode = "403", description = "Collection access denied"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    @GetMapping("/by-external-id")
    @Timed(value = "rag.documents.external-get", description = "Get external document")
    public ResponseEntity<DocumentDetailResponse> getExternalDocument(
            @RequestParam @NotBlank @Size(max = 128) String collectionKey,
            @RequestParam(defaultValue = "default") @Size(max = 128)
            String sourceNamespace,
            @RequestParam @NotBlank @Size(max = 255) String externalId) {
        return ResponseEntity.ok(requireExternalDocumentService()
                .getByExternalIdentity(
                        collectionKey, sourceNamespace, externalId));
    }

    @Operation(summary = "Tombstone an externally managed document",
            description = "Idempotently disable a document for source-managed deletion.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Source deletion applied"),
            @ApiResponse(responseCode = "400", description = "Invalid query"),
            @ApiResponse(responseCode = "403", description = "Collection access denied"),
            @ApiResponse(responseCode = "404", description = "Document not found"),
            @ApiResponse(responseCode = "409", description = "Source revision conflict")
    })
    @DeleteMapping("/by-external-id")
    @Timed(value = "rag.documents.external-delete", description = "Delete external document")
    public ResponseEntity<ExternalDocumentDeleteResponse> deleteExternalDocument(
            @RequestParam @NotBlank @Size(max = 128) String collectionKey,
            @RequestParam(defaultValue = "default") @Size(max = 128)
            String sourceNamespace,
            @RequestParam @NotBlank @Size(max = 255) String externalId,
            @RequestParam @NotBlank @Size(max = 255) String sourceRevision,
            @RequestParam(required = false) @Size(max = 255) String expectedSourceRevision) {
        return ResponseEntity.ok(requireExternalDocumentService().sourceDelete(
                collectionKey, sourceNamespace, externalId,
                sourceRevision, expectedSourceRevision));
    }

    // ==================== CRUD ====================

    @Operation(summary = "Create document", description = "Upload document content; content hash is computed for deduplication. Embedding vectors must be generated separately via POST /{id}/embed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document created (or duplicate detected)"),
            @ApiResponse(responseCode = "400", description = "Request parameter validation failed")
    })
    @PostMapping
    @Timed(value = "rag.documents.create", description = "Create a new document", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<DocumentCreateResponse> createDocument(
            @Valid @RequestBody DocumentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey) {
        log.info("Creating document: title={}", request.getTitle());
        var currentKey = ApiKeyCollectionAccess.currentKey();
        Long collectionId = resolveWritableCollectionId(
                request.getCollectionId(), request.getCollectionKey(), currentKey);

        if (documentMutationService != null) {
            DocumentMutationService.CreatedLocal created =
                    documentMutationService.createLocal(
                            request,
                            collectionId,
                            request.getEmbeddingPolicy(),
                            false,
                            "LOCAL_CREATE",
                            idempotencyKey,
                            null,
                            null,
                            null);
            RagDocument doc = created.document();
            auditCreate(AuditLogService.ENTITY_DOCUMENT,
                    String.valueOf(doc.getId()),
                    "Document created: " + doc.getTitle());
            return ResponseEntity.ok(DocumentCreateResponse.mutation(
                    doc.getId(),
                    doc.getTitle(),
                    doc.getContentHash(),
                    created.mutation()));
        }

        String content = request.getContent();
        String contentHash = com.springairag.core.util.DigestUtils.sha256(content);
        List<RagDocument> existing = documentRepository.findByContentHash(contentHash);
        if (!existing.isEmpty()) {
            RagDocument dup = existing.getFirst();
            return ResponseEntity.ok(DocumentCreateResponse.duplicate(
                    dup.getId(), dup.getTitle(), dup.getContentHash()));
        }
        RagDocument doc = new RagDocument();
        doc.setTitle(request.getTitle());
        doc.setContent(content);
        doc.setSource(request.getSource());
        doc.setDocumentType(request.getDocumentType());
        doc.setMetadata(request.getMetadata());
        doc.setContentHash(contentHash);
        doc.setCollectionId(collectionId);
        doc = documentRepository.save(doc);

        log.info("Document created: id={}, hash={}", doc.getId(), contentHash);
        auditCreate(AuditLogService.ENTITY_DOCUMENT,
                String.valueOf(doc.getId()),
                "Document created: " + doc.getTitle());

        return ResponseEntity.ok(DocumentCreateResponse.created(doc.getId(), doc.getTitle(), contentHash));
    }

    public ResponseEntity<DocumentCreateResponse> createDocument(
            DocumentRequest request) {
        return createDocument(request, null);
    }

    @Operation(summary = "Get document details", description = "Query document content, metadata, and embedding vector count.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document details returned"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    @GetMapping("/{id}")
    @Timed(value = "rag.documents.get", description = "Get document details", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<DocumentDetailResponse> getDocument(@PathVariable Long id) {
        log.info("Getting document: id={}", id);

        return documentRepository.findById(id)
            .map(doc -> {
                    ApiKeyCollectionAccess.requireDocumentAccess(
                            doc, ApiKeyCollectionAccess.currentKey());
                    Long collectionId = doc.getCollectionId();
                    Map<Long, String> collectionNameMap = collectionId != null
                            ? collectionRepository.findById(collectionId)
                                    .map(collection -> Map.of(collectionId, collection.getName()))
                                    .orElseGet(Map::of)
                            : Map.of();
                    Map<Long, String> collectionKeyMap = collectionId != null
                            ? collectionRepository.findById(collectionId)
                                    .map(collection -> Map.of(collectionId, collection.getCollectionKey()))
                                    .orElseGet(Map::of)
                            : Map.of();
                    DocumentDetailResponse result = DocumentMapper.toDetailResponse(
                            doc, collectionNameMap, collectionKeyMap, embeddingRepository,
                            activeEmbeddingProfileId(),
                            documentLifecycleService == null
                                    ? null : documentLifecycleService.read(doc));
                    return ResponseEntity.ok(result);
                })
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Operation(summary = "Delete document", description = "Delete document and its associated embedding vectors (cascading delete).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document deleted"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    @DeleteMapping("/{id}")
    @Timed(value = "rag.documents.delete", description = "Delete a document", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<DocumentDeleteResponse> deleteDocument(
            @PathVariable Long id,
            @RequestParam(required = false) Long expectedDocumentRevision) {
        requireDocumentAccess(id);
        if (documentMutationService != null) {
            if (expectedDocumentRevision == null) {
                throw new IllegalArgumentException(
                        "expectedDocumentRevision is required for permanent deletion");
            }
            DocumentMutationService.DeletedLocal deleted =
                    documentMutationService.hardDeleteLocal(
                            id, expectedDocumentRevision);
            return ResponseEntity.ok(new DocumentDeleteResponse(
                    "Document permanently deleted",
                    id,
                    deleted.embeddingsRemoved(),
                    deleted.documentRevision()));
        }
        return ResponseEntity.ok(batchDocumentService.deleteDocument(id));
    }

    public ResponseEntity<DocumentDeleteResponse> deleteDocument(Long id) {
        return deleteDocument(id, null);
    }

    @Operation(summary = "Update a locally managed document with CAS")
    @PatchMapping("/{id}")
    @Timed(value = "rag.documents.update", description = "Update a local document")
    public ResponseEntity<DocumentMutationResponse> updateDocument(
            @PathVariable Long id,
            @Valid @RequestBody DocumentUpdateRequest request) {
        return ResponseEntity.ok(requireDocumentMutationService()
                .updateLocal(id, request));
    }

    @Operation(summary = "Disable a locally managed document")
    @PostMapping("/{id}/disable")
    @Timed(value = "rag.documents.disable", description = "Disable a local document")
    public ResponseEntity<DocumentMutationResponse> disableDocument(
            @PathVariable Long id,
            @Valid @RequestBody DocumentDisableRequest request) {
        return ResponseEntity.ok(requireDocumentMutationService()
                .disableLocal(id, request));
    }

    @Operation(summary = "Restore a disabled locally managed document")
    @PostMapping("/{id}/restore")
    @Timed(value = "rag.documents.restore", description = "Restore a local document")
    public ResponseEntity<DocumentMutationResponse> restoreDocument(
            @PathVariable Long id,
            @Valid @RequestBody DocumentRestoreRequest request) {
        return ResponseEntity.ok(requireDocumentMutationService()
                .restoreLocal(id, request));
    }

    @Operation(summary = "List documents", description = "Paginated document list with filtering by title/type/status and sorting by creation time descending.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated document list returned")
    })
    @GetMapping
    @Timed(value = "rag.documents.list", description = "List documents with pagination and filters", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<DocumentListResponse> listDocuments(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String processingStatus,
            @RequestParam(required = false) Boolean enabled,
            @Parameter(description = "Deprecated numeric Collection filter; use collectionKey",
                    deprecated = true)
            @RequestParam(required = false) Long collectionId,
            @RequestParam(required = false) String collectionKey,
            @Parameter(description = "Filter documents created at or after this timestamp (ISO-8601, e.g. 2024-01-01T00:00:00)")
            @RequestParam(required = false) String createdAfter,
            @Parameter(description = "Filter documents created at or before this timestamp (ISO-8601, e.g. 2024-12-31T23:59:59)")
            @RequestParam(required = false) String createdBefore) {

        int page = offset / limit;
        var pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"));

        LocalDateTime createdAfterDt = parseDateParam(createdAfter);
        LocalDateTime createdBeforeDt = parseDateParam(createdBefore);

        var currentKey = ApiKeyCollectionAccess.currentKey();
        var restrictedIds = ApiKeyCollectionAccess.restrictedCollectionIds(currentKey);
        collectionId = resolveOptionalCollectionId(collectionId, collectionKey, currentKey);
        final Long resolvedCollectionId = collectionId;
        var pageResult = restrictedIds.isPresent()
                ? documentRepository.searchDocumentsByCollectionIds(
                        resolvedCollectionId != null
                                ? List.of(resolvedCollectionId)
                                : List.copyOf(restrictedIds.orElseThrow()),
                        title, documentType, processingStatus, enabled,
                        createdAfterDt, createdBeforeDt, pageable)
                : documentRepository.searchDocuments(
                        title, documentType, processingStatus, enabled, resolvedCollectionId,
                        createdAfterDt, createdBeforeDt, pageable);

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
        Map<Long, String> collectionKeyMap = collectionIds.isEmpty()
                ? Map.of()
                : collectionRepository.findAllById(collectionIds).stream()
                        .collect(Collectors.toMap(RagCollection::getId, RagCollection::getCollectionKey));

        List<DocumentSummary> docs = pageResult.getContent().stream()
                .map(doc -> DocumentMapper.toSummary(
                        doc, collectionNameMap, collectionKeyMap, embeddingRepository,
                        activeEmbeddingProfileId(),
                        documentLifecycleService == null
                                ? null : documentLifecycleService.read(doc)))
                .toList();

        return ResponseEntity.ok(new DocumentListResponse(
                docs,
                pageResult.getTotalElements(),
                offset,
                limit
        ));
    }

    public ResponseEntity<DocumentListResponse> listDocuments(
            int offset, int limit, String title, String documentType,
            String processingStatus, Boolean enabled, Long collectionId,
            String createdAfter, String createdBefore) {
        return listDocuments(offset, limit, title, documentType, processingStatus,
                enabled, collectionId, null, createdAfter, createdBefore);
    }

    @Operation(summary = "Document statistics", description = "Get document count statistics by processing status.")
    @GetMapping("/stats")
    @Timed(value = "rag.documents.stats", description = "Get document statistics by processing status", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<DocumentStatsResponse> getDocumentStats() {
        List<Object[]> statusCounts = ApiKeyCollectionAccess.restrictedCollectionIds(
                        ApiKeyCollectionAccess.currentKey())
                .map(ids -> documentRepository.countByProcessingStatusAndCollectionIds(
                        List.copyOf(ids)))
                .orElseGet(documentRepository::countByProcessingStatus);
        Map<String, Long> counts = new HashMap<>();
        long total = 0;
        for (Object[] row : statusCounts) {
            String status = (String) row[0];
            long count = (Long) row[1];
            counts.put(status != null ? status : "UNKNOWN", count);
            total += count;
        }
        return ResponseEntity.ok(new DocumentStatsResponse(total, counts));
    }

    // ==================== Embedding Vectors ====================

    public ResponseEntity<Object> embedDocument(Long id, boolean force) {
        return embedDocument(id, force, null);
    }

    @Operation(summary = "Generate embedding vectors", description = "Chunk document and generate embedding vectors stored in rag_embeddings. Skips existing embeddings by default; set force=true to re-embed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Embedding vectors generated"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    @PostMapping("/{id}/embed")
    @Timed(value = "rag.documents.embed", description = "Generate embedding vectors for a document", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<Object> embedDocument(
            @PathVariable Long id,
            @Parameter(description = "Force re-embedding, bypassing the cache")
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(required = false) EmbeddingPolicy embeddingPolicy) {
        requireDocumentAccess(id);
        try {
            EmbeddingPolicy policy = EmbeddingPolicySupport.requireEmbed(embeddingPolicy, true);
            Map<String, Object> result;
            if (policy == EmbeddingPolicy.ASYNC) {
                EmbeddingPolicySupport.requireJobsEnabled(dispatchService);
                RagDocument document = documentRepository.findById(id)
                        .orElseThrow(() -> new DocumentNotFoundException(id));
                EmbeddingDispatchService.Result queued =
                        dispatchService.enqueueInCurrentTransaction(
                                document, false, force, "DOCUMENT_EMBED");
                result = embedDispatchMap(queued, id);
            } else {
                result = documentEmbedService.embedDocument(id, force);
            }

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
    @Timed(value = "rag.documents.embedding-status", description = "Query embedding vector status", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<EmbeddingStatusResponse> embeddingStatus() {
        long embeddingProfileId = activeEmbeddingProfileId();
        var restrictedIds = ApiKeyCollectionAccess.restrictedCollectionIds(
                ApiKeyCollectionAccess.currentKey());
        long total = restrictedIds
                .map(ids -> documentRepository.countByCollectionIdIn(List.copyOf(ids)))
                .orElseGet(documentRepository::count);
        long withoutEmbedding = countDocumentsWithoutCurrentEmbedding(
                restrictedIds, embeddingProfileId);
        long withEmbedding = total - withoutEmbedding;
        return ResponseEntity.ok(new EmbeddingStatusResponse(
                total, withEmbedding, withoutEmbedding, withoutEmbedding > 0));
    }

    /**
     * Batch re-embed documents lacking embedding vectors.
     */
    public ResponseEntity<ReembedMissingResponse> reembedMissing(boolean force) {
        return reembedMissing(force, null);
    }

    @Operation(summary = "Batch re-embed", description = "Automatically find all documents lacking embedding vectors and batch generate/store vectors. Used for data migration fixes or forced re-embedding.")
    @PostMapping("/embed-vector-reembed")
    @Timed(value = "rag.documents.reembed-missing", description = "Batch re-embed documents without embedding vectors", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<ReembedMissingResponse> reembedMissing(
            @Parameter(description = "Whether to force re-embedding (skip existing vectors)")
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(required = false) EmbeddingPolicy embeddingPolicy) {
        EmbeddingPolicy policy = EmbeddingPolicySupport.requireEmbed(embeddingPolicy, true);
        long embeddingProfileId = activeEmbeddingProfileId();
        List<RagDocument> missing = findDocumentsWithoutCurrentEmbedding(
                ApiKeyCollectionAccess.restrictedCollectionIds(
                        ApiKeyCollectionAccess.currentKey()),
                embeddingProfileId);
        if (missing.isEmpty()) {
            return ResponseEntity.ok(new ReembedMissingResponse(0, 0, 0, List.of()));
        }

        log.info("Re-embedding {} documents without embeddings (force={}, policy={})",
                missing.size(), force, policy);
        List<ReembedResultResponse> results = executeReembeddingBatch(missing, force, policy);

        long success = results.stream()
                .filter(r -> "COMPLETED".equals(r.status()) || "QUEUED".equals(r.status()))
                .count();
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

    private List<ReembedResultResponse> executeReembeddingBatch(
            List<RagDocument> documents, boolean force, EmbeddingPolicy policy) {
        List<ReembedResultResponse> results = new ArrayList<>(documents.size());
        for (RagDocument doc : documents) {
            ReembedResultResponse result = buildReembedResult(doc, force, policy);
            results.add(result);
        }
        return results;
    }

    private ReembedResultResponse buildReembedResult(
            RagDocument doc, boolean force, EmbeddingPolicy policy) {
        try {
            if (policy == EmbeddingPolicy.ASYNC) {
                EmbeddingPolicySupport.requireJobsEnabled(dispatchService);
                EmbeddingDispatchService.Result queued =
                        dispatchService.enqueueInCurrentTransaction(
                                doc, false, force, "REEMBED_MISSING");
                return new ReembedResultResponse(
                        doc.getId(),
                        doc.getTitle(),
                        queued.embeddingStatus(),
                        0,
                        queued.action().name());
            }
            Map<String, Object> result = documentEmbedService.embedDocument(doc.getId(), force);
            return new ReembedResultResponse(
                    doc.getId(),
                    doc.getTitle(),
                    String.valueOf(result.getOrDefault("status", "UNKNOWN")),
                    ((Number) result.getOrDefault("chunksCreated", 0)).intValue(),
                    String.valueOf(result.getOrDefault("message", ""))
            );
        } catch (Exception e) { // Best-effort: individual document errors do not affect other documents in the batch
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

    private long activeEmbeddingProfileId() {
        return embeddingProfileProvider.getActiveProfile().id();
    }

    private long countDocumentsWithoutCurrentEmbedding(
            java.util.Optional<java.util.Set<Long>> restrictedIds,
            long embeddingProfileId) {
        if (derivationDescriptorProvider == null) {
            return restrictedIds
                    .map(ids -> documentRepository
                            .countDocumentsWithoutEmbeddingsByCollectionIds(
                                    List.copyOf(ids), embeddingProfileId))
                    .orElseGet(() -> documentRepository
                            .countDocumentsWithoutEmbeddings(
                                    embeddingProfileId));
        }
        String textVersion = derivationDescriptorProvider
                .textDescriptor().chunkerVersion();
        String jsonVersion = derivationDescriptorProvider
                .jsonRecordDescriptor().chunkerVersion();
        return restrictedIds
                .map(ids -> documentRepository
                        .countDocumentsWithoutCurrentEmbeddingsByCollectionIds(
                                List.copyOf(ids), embeddingProfileId,
                                textVersion, jsonVersion))
                .orElseGet(() -> documentRepository
                        .countDocumentsWithoutCurrentEmbeddings(
                                embeddingProfileId, textVersion, jsonVersion));
    }

    private List<RagDocument> findDocumentsWithoutCurrentEmbedding(
            java.util.Optional<java.util.Set<Long>> restrictedIds,
            long embeddingProfileId) {
        if (derivationDescriptorProvider == null) {
            return restrictedIds
                    .map(ids -> documentRepository
                            .findDocumentsWithoutEmbeddingsByCollectionIds(
                                    List.copyOf(ids), embeddingProfileId))
                    .orElseGet(() -> documentRepository
                            .findDocumentsWithoutEmbeddings(
                                    embeddingProfileId));
        }
        String textVersion = derivationDescriptorProvider
                .textDescriptor().chunkerVersion();
        String jsonVersion = derivationDescriptorProvider
                .jsonRecordDescriptor().chunkerVersion();
        return restrictedIds
                .map(ids -> documentRepository
                        .findDocumentsWithoutCurrentEmbeddingsByCollectionIds(
                                List.copyOf(ids), embeddingProfileId,
                                textVersion, jsonVersion))
                .orElseGet(() -> documentRepository
                        .findDocumentsWithoutCurrentEmbeddings(
                                embeddingProfileId, textVersion, jsonVersion));
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
    @Timed(value = "rag.documents.embed-stream", description = "Generate embeddings via SSE streaming with progress events", percentiles = {0.5, 0.95, 0.99})
    public SseEmitter embedDocumentStream(
            @PathVariable Long id,
            @Parameter(description = "Force re-embedding, bypassing the cache")
            @RequestParam(defaultValue = "false") boolean force) {
        requireDocumentAccess(id);
        SseEmitter emitter = SseEmitters.create();
        try {
            documentEmbedService.embedDocumentWithProgress(id, force, event -> {
                SseEmitters.sendProgress(emitter, "progress", event, "document " + id);
            });
            SseEmitters.sendDone(emitter, Map.of("documentId", id));
        } catch (IllegalArgumentException e) {
            SseEmitters.sendError(emitter, e.getMessage(), Map.of("documentId", id));
        } catch (Exception e) { // SSE resilience: unexpected errors terminate the stream gracefully
            emitter.completeWithError(e);
        }
        return emitter;
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
    @Timed(value = "rag.documents.batch-create", description = "Batch create documents", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<BatchCreateResponse> batchCreateDocuments(
            @Valid @RequestBody BatchDocumentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey) {
        Long batchCollectionId = resolveWritableCollectionId(
                request.getCollectionId(), request.getCollectionKey(),
                ApiKeyCollectionAccess.currentKey());
        normalizeDocumentCollectionScopes(request.getDocuments(), batchCollectionId,
                ApiKeyCollectionAccess.currentKey());
        request.setCollectionId(batchCollectionId);
        request.setCollectionKey(null);
        log.info("Batch create: docs={}, embed={}, collectionId={}, force={}",
                request.getDocuments().size(), request.isEmbed(),
                request.getCollectionId(), request.isForce());

        BatchCreateResponse result = batchDocumentService.batchCreateDocuments(
                request.getDocuments(),
                request.isEmbed(),
                request.getCollectionId(),
                request.isForce(),
                request.getEmbeddingPolicy(),
                idempotencyKey);

        auditCreate(AuditLogService.ENTITY_DOCUMENT,
                "batch",
                "Batch create: " + result.created() + " created, "
                        + result.skipped() + " skipped, "
                        + (request.getCollectionId() != null ? "collectionId=" + request.getCollectionId() : "no collection"),
                Map.of("created", result.created(), "skipped", result.skipped(),
                        "collectionId", request.getCollectionId() != null ? request.getCollectionId() : ""));

        return ResponseEntity.ok(result);
    }

    public ResponseEntity<BatchCreateResponse> batchCreateDocuments(
            BatchDocumentRequest request) {
        return batchCreateDocuments(request, null);
    }

    private Long resolveWritableCollectionId(Long collectionId, String collectionKey,
                                             com.springairag.core.entity.RagApiKey currentKey) {
        Long resolved;
        if (collectionKey != null) {
            List<Long> resolvedIds = ApiKeyCollectionAccess.resolveCollectionIds(
                    collectionId == null ? null : List.of(collectionId),
                    List.of(collectionKey),
                    currentKey,
                    collectionIdentityResolver);
            resolved = resolvedIds.getFirst();
        } else {
            resolved = collectionId;
        }
        return ApiKeyCollectionAccess.resolveWritableCollectionId(resolved, currentKey);
    }

    private Long resolveOptionalCollectionId(Long collectionId, String collectionKey,
                                             com.springairag.core.entity.RagApiKey currentKey) {
        if (collectionId == null && collectionKey == null) {
            return null;
        }
        return resolveWritableCollectionId(collectionId, collectionKey, currentKey);
    }

    private void normalizeDocumentCollectionScopes(List<DocumentRequest> requests,
                                                   Long defaultCollectionId,
                                                   com.springairag.core.entity.RagApiKey currentKey) {
        if (requests == null) {
            return;
        }
        for (DocumentRequest document : requests) {
            Long effective;
            if (document.getCollectionId() == null && document.getCollectionKey() == null) {
                effective = defaultCollectionId;
            } else {
                effective = resolveWritableCollectionId(
                        document.getCollectionId(), document.getCollectionKey(), currentKey);
            }
            document.setCollectionId(effective);
            document.setCollectionKey(null);
        }
    }

    @Operation(summary = "Batch delete documents", description = "Batch delete documents and their embedding vectors by ID list. Missing IDs don't affect other deletions.")
    @DeleteMapping("/batch")
    @Timed(value = "rag.documents.batch-delete", description = "Batch delete documents", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<BatchDeleteResponse> batchDeleteDocuments(
            @RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids list cannot be empty");
        }
        requireDocumentAccess(ids);
        BatchDeleteResponse result = batchDocumentService.batchDeleteDocuments(ids);

        auditDelete(AuditLogService.ENTITY_DOCUMENT,
                "batch:" + ids.size(),
                "Batch delete: " + ids.size() + " documents",
                Map.of("deleted", result.summary().deleted(),
                        "notFound", result.summary().notFound()));

        return ResponseEntity.ok(result);
    }

    public ResponseEntity<BatchEmbedResponse> batchEmbedDocuments(
            Map<String, List<Long>> request) {
        return batchEmbedDocuments(request, null);
    }

    @Operation(summary = "Batch generate embedding vectors", description = "Batch chunk and generate embeddings for multiple documents. Single document failure doesn't affect others.")
    @PostMapping("/batch/embed")
    @Timed(value = "rag.documents.batch-embed", description = "Batch generate embedding vectors", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<BatchEmbedResponse> batchEmbedDocuments(
            @RequestBody Map<String, List<Long>> request,
            @RequestParam(required = false) EmbeddingPolicy embeddingPolicy) {
        List<Long> ids = request.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids list cannot be empty");
        }
        if (ids.size() > 50) {
            throw new IllegalArgumentException("Batch embedding limited to 50 documents per request (API rate limit)");
        }
        requireDocumentAccess(ids);
        EmbeddingPolicy policy = EmbeddingPolicySupport.requireEmbed(embeddingPolicy, true);
        if (policy == EmbeddingPolicy.ASYNC) {
            EmbeddingPolicySupport.requireJobsEnabled(dispatchService);
            List<RagDocument> documents = documentRepository.findAllById(ids);
            List<BatchEmbedResponse.BatchEmbedResultItem> items = new ArrayList<>();
            int queued = 0;
            for (RagDocument document : documents) {
                EmbeddingDispatchService.Result result =
                        dispatchService.enqueueInCurrentTransaction(
                                document, false, false, "BATCH_EMBED");
                items.add(new BatchEmbedResponse.BatchEmbedResultItem(
                        document.getId(),
                        result.embeddingStatus(),
                        null,
                        null,
                        result.error(),
                        result.action().name()));
                queued++;
            }
            return ResponseEntity.ok(new BatchEmbedResponse(
                    items,
                    new BatchEmbedResponse.BatchEmbedSummary(
                            ids.size(), queued, 0, 0, ids.size() - queued)));
        }
        Map<String, Object> raw = documentEmbedService.batchEmbedDocuments(ids);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawResults = (List<Map<String, Object>>) raw.get("results");
        @SuppressWarnings("unchecked")
        Map<String, Object> rawSummary = (Map<String, Object>) raw.get("summary");

        List<BatchEmbedResponse.BatchEmbedResultItem> items = rawResults.stream()
                .map(r -> new BatchEmbedResponse.BatchEmbedResultItem(
                        ((Number) r.get("documentId")).longValue(),
                        (String) r.get("status"),
                        r.get("chunksCreated") != null ? ((Number) r.get("chunksCreated")).intValue() : null,
                        r.get("embeddingsStored") != null ? ((Number) r.get("embeddingsStored")).intValue() : null,
                        (String) r.get("error"),
                        (String) r.get("reason")))
                .toList();

        BatchEmbedResponse.BatchEmbedSummary summary = new BatchEmbedResponse.BatchEmbedSummary(
                ((Number) rawSummary.get("total")).intValue(),
                ((Number) rawSummary.get("success")).intValue(),
                ((Number) rawSummary.get("cached")).intValue(),
                ((Number) rawSummary.get("failed")).intValue(),
                ((Number) rawSummary.get("skipped")).intValue()
        );

        auditCreate(AuditLogService.ENTITY_EMBED_CACHE,
                "batch:" + ids.size(),
                "Batch embed: " + ids.size() + " documents",
                Map.of("succeeded", summary.success(),
                        "failed", summary.failed()));

        return ResponseEntity.ok(new BatchEmbedResponse(items, summary));
    }

    @Operation(summary = "Batch generate embeddings via SSE streaming with progress",
            description = "Similar to POST /batch/embed, but pushes real-time progress via Server-Sent Events. "
                    + "Clients listen for 'progress' events to track current document, overall percentage, and counts.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE stream established; progress events will follow")
    })
    @PostMapping("/batch/embed/stream")
    @Timed(value = "rag.documents.batch-embed-stream", description = "Batch generate embeddings via SSE streaming with progress", percentiles = {0.5, 0.95, 0.99})
    public SseEmitter batchEmbedDocumentsStream(
            @RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids list cannot be empty");
        }
        if (ids.size() > 50) {
            throw new IllegalArgumentException("Batch embedding limited to 50 documents per request (API rate limit)");
        }
        requireDocumentAccess(ids);

        SseEmitter emitter = SseEmitters.create();
        try {
            documentEmbedService.batchEmbedDocumentsWithProgress(ids, event -> {
                SseEmitters.sendProgress(emitter, "progress", event, "batch embed");
            });
            SseEmitters.sendDone(emitter, Map.of("total", ids.size(), "status", "completed"));
        } catch (IllegalArgumentException e) {
            SseEmitters.sendError(emitter, e.getMessage(), Map.of());
        } catch (Exception e) { // SSE resilience: unexpected errors terminate the stream gracefully
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
    @Timed(value = "rag.documents.batch-create-and-embed", description = "Batch create and embed documents (deprecated)", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<BatchCreateAndEmbedResponse> batchCreateAndEmbed(
            @Valid @RequestBody BatchCreateAndEmbedRequest request) {
        Long batchCollectionId = resolveWritableCollectionId(
                request.getCollectionId(), request.getCollectionKey(),
                ApiKeyCollectionAccess.currentKey());
        normalizeDocumentCollectionScopes(request.getDocuments(), batchCollectionId,
                ApiKeyCollectionAccess.currentKey());
        request.setCollectionId(batchCollectionId);
        request.setCollectionKey(null);
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
    @Timed(value = "rag.documents.upload", description = "Upload file and auto-embed", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<FileUploadResponse> uploadAndEmbed(
            @RequestParam("files") MultipartFile[] files,
            @Parameter(description = "Deprecated numeric Collection ID; use collectionKey",
                    deprecated = true)
            @RequestParam(value = "collectionId", required = false) Long collectionId,
            @RequestParam(value = "collectionKey", required = false) String collectionKey,
            @RequestParam(value = "force", defaultValue = "false") boolean force,
            @RequestParam(value = "embeddingPolicy", required = false) EmbeddingPolicy embeddingPolicy,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey) {
        collectionId = resolveWritableCollectionId(
                collectionId, collectionKey, ApiKeyCollectionAccess.currentKey());

        log.info("File upload request: {} files, collectionId={}, policy={}",
                files.length, collectionId, embeddingPolicy);

        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(
                    new FileUploadResponse(0, 0, 0, List.of(
                            new FileUploadResponse.FileResult("N/A", null, null, false, 0, "No file uploaded"))));
        }

        List<FileUploadResponse.FileResult> results = new java.util.ArrayList<>();
        int success = 0, failed = 0;

        for (int index = 0; index < files.length; index++) {
            MultipartFile file = files[index];
            FileUploadResponse.FileResult result = processUploadedFile(
                    file, collectionId, force, embeddingPolicy,
                    itemIdempotencyKey(idempotencyKey, index));
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

    public ResponseEntity<FileUploadResponse> uploadAndEmbed(
            MultipartFile[] files, Long collectionId, boolean force) {
        return uploadAndEmbed(files, collectionId, null, force, null, null);
    }

    private FileUploadResponse.FileResult processUploadedFile(
            MultipartFile file, Long collectionId, boolean force,
            EmbeddingPolicy embeddingPolicy,
            String idempotencyKey) {
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
            docReq.setSource("upload:" + filename);
            if (collectionId != null) {
                docReq.setCollectionId(collectionId);
            }
            docReq.setDeduplicationScope(
                    com.springairag.api.enums.DocumentDeduplicationScope.NONE);
            EmbeddingPolicy policy = EmbeddingPolicyResolver.resolve(
                    embeddingPolicy, true);
            if (documentMutationService != null) {
                DocumentMutationService.CreatedLocal created =
                        documentMutationService.createLocal(
                                docReq,
                                collectionId,
                                policy,
                                force,
                                "FILE_UPLOAD",
                                idempotencyKey,
                                filename,
                                null,
                                null);
                DocumentMutationResponse mutation = created.mutation();
                return new FileUploadResponse.FileResult(
                        filename, created.document().getId(), content.title,
                        !"SKIPPED".equals(mutation.embeddingAction()),
                        0, null, mutation.embeddingAction(),
                        mutation.embeddingJobId());
            }
            BatchCreateResponse resp = batchDocumentService.batchCreateDocuments(
                    List.of(docReq), true, collectionId, force, embeddingPolicy);
            BatchCreateResponse.DocumentResult r = resp.results().getFirst();
            return r.documentId() != null
                    ? new FileUploadResponse.FileResult(
                            filename, r.documentId(), content.title,
                            !"SKIPPED".equals(r.embeddingAction()),
                            0, null, r.embeddingAction(), r.embeddingJobId())
                    : new FileUploadResponse.FileResult(
                            filename, null, content.title, false, 0,
                            r.error() != null ? r.error() : "Creation failed");

        } catch (Exception e) { // Best-effort: file processing errors return a failure result without throwing
            log.error("Failed to process uploaded file '{}': {}", filename, e.getMessage());
            return new FileUploadResponse.FileResult(
                    filename, null, null, false, 0,
                    "Processing failed: " + e.getMessage());
        }
    }

    private String itemIdempotencyKey(String idempotencyKey, int index) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return idempotencyKey.trim() + ":" + index;
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
            } catch (Exception e) { // Best-effort: non-text files throw in getBytes(); mark as invalid
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
        } catch (Exception e) { // best-effort: error already sent via completeWithError
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
    @Timed(value = "rag.documents.version-history", description = "Get document version history", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<VersionHistoryResponse> getVersionHistory(
            @Parameter(description = "Document ID") @PathVariable Long id,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        requireDocumentAccess(id);

        if (!documentRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        var versions = documentVersionService.getVersionHistory(id, PageRequest.of(page, size));
        return ResponseEntity.ok(new VersionHistoryResponse(
                id,
                versions.getTotalElements(),
                page,
                size,
                versions.getContent().stream()
                        .map(DocumentMapper::toVersionSummary)
                        .toList()
        ));
    }

    @Operation(summary = "Get specific version", description = "Query specific version details of a document (including content snapshot).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Version details returned"),
            @ApiResponse(responseCode = "404", description = "Version not found")
    })
    @GetMapping("/{id}/versions/{versionNumber}")
    @Timed(value = "rag.documents.get-version", description = "Get specific document version", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<DocumentVersionResponse> getVersion(
            @Parameter(description = "Document ID") @PathVariable Long id,
            @Parameter(description = "Version number") @PathVariable int versionNumber) {
        requireDocumentAccess(id);

        return documentVersionService.getVersion(id, versionNumber)
                .map(v -> ResponseEntity.ok(DocumentMapper.toVersionResponse(v)))
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

    private void requireDocumentAccess(Long id) {
        documentRepository.findById(id).ifPresent(doc ->
                ApiKeyCollectionAccess.requireDocumentAccess(
                        doc, ApiKeyCollectionAccess.currentKey()));
    }

    private void requireDocumentAccess(List<Long> ids) {
        if (ApiKeyCollectionAccess.isUnrestricted(
                ApiKeyCollectionAccess.currentKey())) {
            return;
        }
        Map<Long, RagDocument> documents = documentRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(RagDocument::getId, doc -> doc));
        for (Long id : ids) {
            RagDocument document = documents.get(id);
            if (document != null) {
                ApiKeyCollectionAccess.requireDocumentAccess(
                        document, ApiKeyCollectionAccess.currentKey());
            }
        }
    }

    private Map<String, Object> embedDispatchMap(
            EmbeddingDispatchService.Result queued, Long documentId) {
        Map<String, Object> result = new HashMap<>();
        result.put("documentId", documentId);
        result.put("status", queued.embeddingStatus());
        result.put("embeddingAction", queued.action().name());
        result.put("embeddingJobId", queued.embeddingJobId());
        result.put("embeddingBatchId", queued.embeddingBatchId());
        result.put("embeddingProfileKey", queued.embeddingProfileKey());
        return result;
    }

    private ExternalDocumentService requireExternalDocumentService() {
        if (externalDocumentService == null) {
            throw new IllegalStateException("External document service is not available");
        }
        return externalDocumentService;
    }

    private DocumentMutationService requireDocumentMutationService() {
        if (documentMutationService == null) {
            throw new IllegalStateException(
                    "Document mutation service is not available");
        }
        return documentMutationService;
    }

    // ==================== Date Parsing Helper ====================

    /**
     * Parse ISO-8601 date-time string to LocalDateTime.
     * Returns null if the input is null or blank.
     * Invalid format is ignored (filter skipped) rather than throwing.
     */
    private LocalDateTime parseDateParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            log.warn("Invalid date-time format '{}': {}", value, e.getMessage());
            return null;
        }
    }
}
