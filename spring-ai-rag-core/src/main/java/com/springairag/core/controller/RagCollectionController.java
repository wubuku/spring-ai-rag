package com.springairag.core.controller;

import com.springairag.api.dto.CollectionCloneResponse;
import com.springairag.api.dto.CollectionCloneRequest;
import com.springairag.api.dto.CollectionCreatedResponse;
import com.springairag.api.dto.CollectionDeleteResponse;
import com.springairag.api.dto.CollectionExportResponse;
import com.springairag.api.dto.CollectionDocumentListResponse;
import com.springairag.api.dto.CollectionImportResponse;
import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.api.dto.CollectionRequest;
import com.springairag.api.dto.CollectionUpdateRequest;
import com.springairag.api.dto.CollectionRestoreResponse;
import com.springairag.api.dto.DocumentAddedResponse;
import com.springairag.api.dto.DocumentSummary;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.util.CollectionMapper;
import org.springframework.data.domain.Page;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.RagCollectionService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.JsonRecordService;
import com.springairag.core.util.DigestUtils;
import com.springairag.core.versioning.ApiVersion;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Document collection (knowledge base) management controller.
 *
 * <p>Provides collection CRUD operations with multi-knowledge-base/multi-tenant isolation support.
 * Collections organize documents; each document can belong to one collection.
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/collections")
@Tag(name = "RAG Collections", description = "Document collection (knowledge base) management")
public class RagCollectionController {

    private static final Logger log = LoggerFactory.getLogger(RagCollectionController.class);

    private final RagCollectionRepository collectionRepository;
    private final RagDocumentRepository documentRepository;
    private final RagCollectionService collectionService;
    private final CollectionIdentityResolver identityResolver;
    private JsonRecordService jsonRecordService;
    private AuditLogService auditLogService;  // optional: null when RagAuditLogRepository unavailable

    @Autowired(required = false)
    public void setJsonRecordService(JsonRecordService jsonRecordService) {
        this.jsonRecordService = jsonRecordService;
    }

    @Autowired
    public RagCollectionController(RagCollectionRepository collectionRepository,
                                    RagDocumentRepository documentRepository,
                                    RagCollectionService collectionService,
                                    CollectionIdentityResolver identityResolver,
                                    @Autowired(required = false) AuditLogService auditLogService) {
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.collectionService = collectionService;
        this.identityResolver = identityResolver;
        this.auditLogService = auditLogService;
    }

    public RagCollectionController(RagCollectionRepository collectionRepository,
                                   RagDocumentRepository documentRepository,
                                   RagCollectionService collectionService,
                                   AuditLogService auditLogService) {
        this(collectionRepository, documentRepository, collectionService,
                new CollectionIdentityResolver(collectionRepository), auditLogService);
    }

    /**
     * Create a collection.
     */
    @Operation(summary = "Create collection", description = "Create a new document collection (knowledge base).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Collection created, returns collection info"),
            @ApiResponse(responseCode = "400", description = "Invalid or missing collectionKey"),
            @ApiResponse(responseCode = "409", description = "collectionKey already exists")
    })
    @PostMapping
    @Timed(value = "rag.collection.create", description = "Create collection", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CollectionRequest request) {
        ApiKeyCollectionAccess.requireCollectionCreationAllowed(
                ApiKeyCollectionAccess.currentKey());
        log.info("Creating collection: key={}, name={}",
                request.getCollectionKey(), request.getName());

        RagCollection collection = collectionService.createCollection(request);

        log.info("Collection created: id={}, name={}", collection.getId(), collection.getName());
        audit(AuditLogService.AuditAction.CREATE, AuditLogService.ENTITY_COLLECTION,
                String.valueOf(collection.getId()),
                "Collection created: " + collection.getName());

        return ResponseEntity.ok(CollectionMapper.toMap(collection, 0));
    }

    @Operation(summary = "Get collection by stable key",
            description = "Query an active collection by its caller-supplied stable key.")
    @GetMapping("/by-key")
    public ResponseEntity<Map<String, Object>> getByKey(@RequestParam String collectionKey) {
        RagCollection collection = identityResolver.requireActive(null, collectionKey);
        ApiKeyCollectionAccess.requireCollectionId(
                collection.getId(), ApiKeyCollectionAccess.currentKey());
        long docCount = documentRepository.countByCollectionId(collection.getId());
        return ResponseEntity.ok(CollectionMapper.toMap(collection, docCount));
    }

    @PutMapping("/by-key")
    public ResponseEntity<Map<String, Object>> updateByKey(
            @RequestParam String collectionKey,
            @Valid @RequestBody CollectionUpdateRequest request) {
        RagCollection collection = identityResolver.requireActive(null, collectionKey);
        ApiKeyCollectionAccess.requireCollectionId(
                collection.getId(), ApiKeyCollectionAccess.currentKey());
        return update(collection.getId(), request);
    }

    /**
     * Get collection details.
     */
    @Operation(summary = "Get collection details",
            description = "Deprecated numeric route. Query collection info and document count.",
            deprecated = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Collection found"),
            @ApiResponse(responseCode = "404", description = "Collection not found or deleted")
    })
    @GetMapping("/{id}")
    @Timed(value = "rag.collection.get", description = "Get collection details", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Long id) {
        ApiKeyCollectionAccess.requireCollectionId(
                id, ApiKeyCollectionAccess.currentKey());
        log.info("Getting collection: id={}", id);

        return collectionRepository.findByIdAndDeletedFalse(id)
                .map(c -> {
                    long docCount = documentRepository.countByCollectionId(id);
                    return ResponseEntity.ok(CollectionMapper.toMap(c, docCount));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * List collections (paginated).
     */
    @Operation(summary = "List collections", description = "Paginated collection list, sorted by creation time descending.")
    @GetMapping
    @Timed(value = "rag.collection.list", description = "List collections", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Boolean enabled) {

        int page = offset / limit;
        var pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"));

        var restrictedIds = ApiKeyCollectionAccess.restrictedCollectionIds(
                ApiKeyCollectionAccess.currentKey());
        var pageResult = restrictedIds
                .map(ids -> collectionRepository.searchCollectionsByIds(
                        List.copyOf(ids), name, enabled, pageable))
                .orElseGet(() -> collectionRepository.searchCollections(
                        name, enabled, pageable));

        List<Map<String, Object>> items = pageResult.getContent().stream()
                .map(c -> {
                    long docCount = documentRepository.countByCollectionId(c.getId());
                    return CollectionMapper.toMap(c, docCount);
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "collections", items,
                "total", pageResult.getTotalElements(),
                "offset", offset,
                "limit", limit));
    }

    /**
     * Update collection.
     */
    @Operation(summary = "Update collection",
            description = "Deprecated numeric route. Update mutable collection fields.",
            deprecated = true)
    @PutMapping("/{id}")
    @Timed(value = "rag.collection.update", description = "Update collection", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @Valid @RequestBody CollectionUpdateRequest request) {
        request.rejectImmutableCollectionKey();
        ApiKeyCollectionAccess.requireCollectionId(
                id, ApiKeyCollectionAccess.currentKey());
        log.info("Updating collection: id={}", id);

        return collectionRepository.findByIdAndDeletedFalse(id)
                .map(existing -> {
                    existing.setName(request.getName());
                    existing.setDescription(request.getDescription());
                    existing.setEmbeddingModel(request.getEmbeddingModel());
                    if (request.getDimensions() != null) {
                        existing.setDimensions(request.getDimensions());
                    }
                    if (request.getEnabled() != null) {
                        existing.setEnabled(request.getEnabled());
                    }
                    existing.setMetadata(request.getMetadata());

                    RagCollection saved = collectionRepository.save(existing);
                    long docCount = documentRepository.countByCollectionId(id);

                    log.info("Collection updated: id={}", id);
                    audit(AuditLogService.AuditAction.UPDATE, AuditLogService.ENTITY_COLLECTION,
                            String.valueOf(id),
                            "Collection updated: " + existing.getName());
                    return ResponseEntity.ok(CollectionMapper.toMap(saved, docCount));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Compatibility overload for isolated Java callers using the old shared DTO.
     * The HTTP contract uses CollectionUpdateRequest, which has no key field.
     */
    public ResponseEntity<Map<String, Object>> update(
            Long id, CollectionRequest request) {
        if (request.getCollectionKey() != null) {
            throw new IllegalArgumentException(
                    "collectionKey is immutable and must not be supplied when updating");
        }
        CollectionUpdateRequest update = new CollectionUpdateRequest();
        update.setName(request.getName());
        update.setDescription(request.getDescription());
        update.setEmbeddingModel(request.getEmbeddingModel());
        update.setDimensions(request.getDimensions());
        update.setEnabled(request.getEnabled());
        update.setMetadata(request.getMetadata());
        return update(id, update);
    }

    /**
     * Delete collection (soft delete).
     */
    @Operation(summary = "Delete collection (soft delete)",
            description = "Deprecated numeric route. Soft-deletes the collection. Associated documents are unlinked (not deleted).",
            deprecated = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Collection soft-deleted"),
            @ApiResponse(responseCode = "404", description = "Collection not found")
    })
    @DeleteMapping("/{id}")
    @Timed(value = "rag.collection.delete", description = "Delete collection (soft delete)", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        ApiKeyCollectionAccess.requireCollectionId(
                id, ApiKeyCollectionAccess.currentKey());
        log.info("Soft-deleting collection: id={}", id);

        return collectionService.deleteCollection(id)
                .map(result -> ResponseEntity.ok(Map.of(
                        "message", "Collection deleted",
                        "id", String.valueOf(result.id()),
                        "documentsUnlinked", String.valueOf(result.documentsUnlinked())
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/by-key")
    public ResponseEntity<Map<String, String>> deleteByKey(
            @RequestParam String collectionKey) {
        RagCollection collection = identityResolver.requireActive(null, collectionKey);
        ApiKeyCollectionAccess.requireCollectionId(
                collection.getId(), ApiKeyCollectionAccess.currentKey());
        return delete(collection.getId());
    }

    /**
     * Restore a deleted collection.
     */
    @Operation(summary = "Restore deleted collection",
            description = "Deprecated numeric route. Restores a soft-deleted collection without re-linking documents.",
            deprecated = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Collection restored"),
            @ApiResponse(responseCode = "404", description = "Collection not found or not deleted")
    })
    @PostMapping("/{id}/restore")
    @Timed(value = "rag.collection.restore", description = "Restore deleted collection", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<CollectionRestoreResponse> restore(@PathVariable Long id) {
        ApiKeyCollectionAccess.requireCollectionId(
                id, ApiKeyCollectionAccess.currentKey());
        log.info("Restoring collection: id={}", id);

        return collectionService.restoreCollection(id)
                .map(result -> {
                    long docCount = documentRepository.countByCollectionId(result.collection().getId());
                    return ResponseEntity.ok(CollectionRestoreResponse.of(
                            result.collection().getId(),
                            result.collection().getCollectionKey(),
                            result.collection().getName(),
                            docCount));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/by-key/restore")
    public ResponseEntity<CollectionRestoreResponse> restoreByKey(
            @RequestParam String collectionKey) {
        RagCollection collection = identityResolver.findIncludingDeleted(null, collectionKey)
                .orElse(null);
        if (collection == null) {
            return ResponseEntity.notFound().build();
        }
        ApiKeyCollectionAccess.requireCollectionId(
                collection.getId(), ApiKeyCollectionAccess.currentKey());
        return restore(collection.getId());
    }

    /**
     * Clone collection (deep copy).
     */
    @Operation(summary = "Clone collection",
            description = "Deprecated numeric source route. Creates a deep copy with an explicit target collectionKey; embeddings are not copied.",
            deprecated = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Collection cloned successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid or missing target collectionKey"),
            @ApiResponse(responseCode = "403", description = "Caller cannot clone collections"),
            @ApiResponse(responseCode = "409", description = "Target collectionKey already exists"),
            @ApiResponse(responseCode = "404", description = "Source collection not found or deleted")
    })
    @PostMapping("/{id}/clone")
    @Timed(value = "rag.collection.clone", description = "Clone collection", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<CollectionCloneResponse> cloneCollectionById(
            @PathVariable Long id,
            @RequestParam String collectionKey) {
        ApiKeyCollectionAccess.requireCollectionCreationAllowed(
                ApiKeyCollectionAccess.currentKey());
        ApiKeyCollectionAccess.requireCollectionId(id, ApiKeyCollectionAccess.currentKey());
        log.info("Cloning collection: id={}, key={}", id, collectionKey);

        return collectionService.cloneCollection(id, collectionKey)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Clone collection by stable key",
            description = "Creates a deep copy using stable source and target Collection keys.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Collection cloned successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid or missing Collection key"),
            @ApiResponse(responseCode = "403", description = "Caller cannot access or clone the source Collection"),
            @ApiResponse(responseCode = "404", description = "Source Collection not found or deleted"),
            @ApiResponse(responseCode = "409", description = "Target collectionKey already exists")
    })
    @PostMapping("/clone")
    @Timed(value = "rag.collection.cloneByKey",
            description = "Clone collection by stable key",
            percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<CollectionCloneResponse> cloneCollectionByKey(
            @Valid @RequestBody CollectionCloneRequest request) {
        ApiKeyCollectionAccess.requireCollectionCreationAllowed(
                ApiKeyCollectionAccess.currentKey());
        RagCollection source = identityResolver.requireActive(
                null, request.getSourceCollectionKey());
        ApiKeyCollectionAccess.requireCollectionId(
                source.getId(), ApiKeyCollectionAccess.currentKey());
        log.info("Cloning collection by key: sourceKey={}, targetKey={}",
                request.getSourceCollectionKey(), request.getCollectionKey());

        return collectionService.cloneCollection(
                        source.getId(), request.getCollectionKey())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Java compatibility overload retained for existing isolated callers.
     * The HTTP endpoint requires an explicit target key.
     */
    public ResponseEntity<CollectionCloneResponse> cloneCollection(Long id) {
        throw new IllegalArgumentException(
                "collectionKey is required when cloning a collection");
    }

    /**
     * List documents in a collection.
     */
    @Operation(summary = "List documents in collection",
            description = "Deprecated numeric route. Query documents in the collection.",
            deprecated = true)
    @GetMapping("/{id}/documents")
    @Timed(value = "rag.collection.listDocuments", description = "List documents in collection", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<CollectionDocumentListResponse> listDocuments(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String processingStatus) {
        ApiKeyCollectionAccess.requireCollectionId(
                id, ApiKeyCollectionAccess.currentKey());

        if (!collectionRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        int page = offset / limit;
        var pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<RagDocument> pageResult;

        boolean hasFilters = (keyword != null && !keyword.isBlank())
                || (documentType != null && !documentType.isBlank())
                || (processingStatus != null && !processingStatus.isBlank());

        if (hasFilters) {
            pageResult = documentRepository.searchDocumentsByCollectionId(
                    id,
                    keyword != null ? keyword.trim() : null,
                    documentType,
                    processingStatus,
                    pageable);
        } else {
            pageResult = documentRepository.findByCollectionId(id, pageable);
        }

        String collectionKey = identityResolver.mapKeys(List.of(id)).get(id);
        List<DocumentSummary> docs = pageResult.getContent().stream()
                .map(doc -> toDocumentSummary(doc, id, collectionKey))
                .toList();

        return ResponseEntity.ok(new CollectionDocumentListResponse(
                id, identityResolver.mapKeys(List.of(id)).get(id),
                docs, pageResult.getTotalElements(), offset, limit));
    }

    @GetMapping("/by-key/documents")
    public ResponseEntity<CollectionDocumentListResponse> listDocumentsByKey(
            @RequestParam String collectionKey,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String processingStatus) {
        RagCollection collection = identityResolver.requireActive(null, collectionKey);
        ApiKeyCollectionAccess.requireCollectionId(
                collection.getId(), ApiKeyCollectionAccess.currentKey());
        return listDocuments(collection.getId(), offset, limit, keyword,
                documentType, processingStatus);
    }

    private DocumentSummary toDocumentSummary(
            RagDocument doc, Long collectionId, String collectionKey) {
        return new DocumentSummary(
                doc.getId(),
                doc.getTitle(),
                doc.getSource(),
                doc.getDocumentType(),
                doc.getProcessingStatus(),
                doc.getCreatedAt(),
                doc.getSize(),
                doc.getContentHash(),
                doc.getEnabled(),
                doc.getUpdatedAt(),
                collectionId,
                null, // collectionName
                0L,   // chunkCount - not available without embeddingRepository
                null, // contentPreview
                null, // content
                doc.getMetadata(),
                collectionKey);
    }

    /**
     * Add document to collection.
     */
    @Operation(summary = "Add document to collection",
            description = "Deprecated numeric route. Associate a document with the collection.",
            deprecated = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document added successfully, returns details"),
            @ApiResponse(responseCode = "404", description = "Collection not found")
    })
    @PostMapping("/{id}/documents")
    @Timed(value = "rag.collection.addDocument", description = "Add document to collection", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<DocumentAddedResponse> addDocument(
            @PathVariable Long id,
            @RequestBody Map<String, Long> request) {
        ApiKeyCollectionAccess.requireCollectionId(
                id, ApiKeyCollectionAccess.currentKey());

        Long documentId = request.get("documentId");
        if (documentId == null) {
            throw new IllegalArgumentException("documentId is required");
        }

        if (!collectionRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        return documentRepository.findById(documentId)
                .map(doc -> {
                    if (doc.getCollectionId() != null) {
                        ApiKeyCollectionAccess.requireCollectionId(
                                doc.getCollectionId(),
                                ApiKeyCollectionAccess.currentKey());
                    }
                    doc.setCollectionId(id);
                    documentRepository.save(doc);

                    log.info("Document {} added to collection {}", documentId, id);
                    audit(AuditLogService.AuditAction.UPDATE, AuditLogService.ENTITY_DOCUMENT,
                            String.valueOf(documentId),
                            "Document added to collection " + id,
                            Map.of("collectionId", id));
                    return ResponseEntity.ok(DocumentAddedResponse.of(
                            id, identityResolver.mapKeys(List.of(id)).get(id), documentId));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/by-key/documents")
    public ResponseEntity<DocumentAddedResponse> addDocumentByKey(
            @RequestParam String collectionKey,
            @RequestBody Map<String, Long> request) {
        RagCollection collection = identityResolver.requireActive(null, collectionKey);
        ApiKeyCollectionAccess.requireCollectionId(
                collection.getId(), ApiKeyCollectionAccess.currentKey());
        return addDocument(collection.getId(), request);
    }

    /**
     * Export collection (with document metadata).
     */
    @Operation(summary = "Export collection",
            description = "Deprecated numeric route. Export collection info and documents as JSON.",
            deprecated = true)
    @GetMapping("/{id}/export")
    @Timed(value = "rag.collection.export", description = "Export collection", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<CollectionExportResponse> exportCollection(@PathVariable Long id) {
        ApiKeyCollectionAccess.requireCollectionId(
                id, ApiKeyCollectionAccess.currentKey());
        log.info("Exporting collection: id={}", id);

        return collectionRepository.findByIdAndDeletedFalse(id)
                .map(collection -> {
                    List<RagDocument> docs = documentRepository.findAllByCollectionId(id);
                    CollectionExportResponse response = buildExportResponse(collection, docs);
                    log.info("Collection exported: id={}, documents={}", id, docs.size());
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-key/export")
    public ResponseEntity<CollectionExportResponse> exportCollectionByKey(
            @RequestParam String collectionKey) {
        RagCollection collection = identityResolver.requireActive(null, collectionKey);
        ApiKeyCollectionAccess.requireCollectionId(
                collection.getId(), ApiKeyCollectionAccess.currentKey());
        return exportCollection(collection.getId());
    }

    private CollectionExportResponse buildExportResponse(RagCollection collection, List<RagDocument> docs) {
        List<CollectionExportResponse.ExportedDocumentSummary> docList = docs.stream()
                .map(doc -> new CollectionExportResponse.ExportedDocumentSummary(
                        doc.getTitle(),
                        doc.getSource(),
                        doc.getContent(),
                        doc.getDocumentType(),
                        doc.getMetadata(),
                        doc.getSize(),
                        doc.getExternalId(),
                        doc.getJsonbPayload(),
                        doc.getOriginalFilename(),
                        doc.getEnabled()))
                .toList();

        return new CollectionExportResponse(
                collection.getName(),
                collection.getCollectionKey(),
                collection.getDescription(),
                collection.getEmbeddingModel(),
                collection.getDimensions(),
                collection.getEnabled(),
                collection.getMetadata(),
                docList,
                Instant.now(),
                docs.size());
    }


    /**
     * Import collection (create new collection from exported JSON data).
     */
    @Operation(summary = "Import collection", description = "Create a new collection and its documents from exported JSON data.")
    @PostMapping("/import")
    @Timed(value = "rag.collection.import", description = "Import collection", percentiles = {0.5, 0.95, 0.99})
    @Transactional
    public ResponseEntity<Map<String, Object>> importCollection(
            @Valid @RequestBody CollectionImportRequest importRequest) {
        ApiKeyCollectionAccess.requireCollectionCreationAllowed(
                ApiKeyCollectionAccess.currentKey());
        validateImportRequest(importRequest);
        String name = importRequest.getName();

        log.info("Importing collection: name={}", name);

        RagCollection collection = buildCollectionFromImport(importRequest);
        collection = collectionRepository.saveAndFlush(collection);

        int importedDocs = importDocuments(collection.getId(), importRequest.getDocuments());

        log.info("Collection imported: id={}, name={}, documents={}",
                collection.getId(), name, importedDocs);
        audit(AuditLogService.AuditAction.CREATE, AuditLogService.ENTITY_COLLECTION,
                String.valueOf(collection.getId()),
                "Collection imported: " + name + ", documents: " + importedDocs,
                Map.of("importedDocuments", importedDocs));

        Map<String, Object> result = CollectionMapper.toMap(collection, importedDocs);
        result.put("importedDocuments", importedDocs);
        return ResponseEntity.ok(result);
    }

    private void validateImportRequest(CollectionImportRequest importRequest) {
        if (importRequest == null) {
            throw new IllegalArgumentException("import request must not be null");
        }
        if (importRequest.getName() == null || importRequest.getName().isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (importRequest.getCollectionKey() == null
                || importRequest.getCollectionKey().isBlank()) {
            throw new IllegalArgumentException("collectionKey must not be blank");
        }
    }

    private RagCollection buildCollectionFromImport(CollectionImportRequest importData) {
        RagCollection collection = new RagCollection();
        collection.setCollectionKey(importData.getCollectionKey());
        collection.setName(importData.getName());
        collection.setDescription(importData.getDescription());
        collection.setEmbeddingModel(importData.getEmbeddingModel());
        collection.setDimensions(importData.getDimensions() != null
                ? importData.getDimensions() : 1024);
        collection.setEnabled(importData.getEnabled() != null
                ? importData.getEnabled() : true);
        collection.setMetadata(importData.getMetadata());
        return collection;
    }

    private int importDocuments(
            Long collectionId, List<CollectionImportRequest.ImportedDocument> docList) {
        int count = 0;
        if (docList != null) {
            java.util.Set<String> externalIds = new java.util.HashSet<>();
            for (CollectionImportRequest.ImportedDocument docData : docList) {
                if (RagDocument.JSON_RECORD.equals(docData.getDocumentType())) {
                    if (docData.getExternalId() == null
                            || docData.getExternalId().isBlank()
                            || docData.getJsonbPayload() == null
                            || docData.getJsonbPayload().isNull()) {
                        throw new IllegalArgumentException(
                                "json-record import requires externalId and jsonbPayload");
                    }
                    if (!externalIds.add(docData.getExternalId().trim())) {
                        throw new IllegalArgumentException(
                                "Duplicate JSON record externalId in import: "
                                        + docData.getExternalId());
                    }
                    if (jsonRecordService == null) {
                        throw new IllegalStateException(
                                "JSON record import service is not available");
                    }
                    jsonRecordService.importRecord(collectionId, docData);
                    count++;
                    continue;
                }

                RagDocument document = buildDocumentFromImport(docData, collectionId);
                document = documentRepository.saveAndFlush(document);
                count++;
            }
        }
        return count;
    }

    private RagDocument buildDocumentFromImport(
            CollectionImportRequest.ImportedDocument docData, Long collectionId) {
        RagDocument doc = new RagDocument();
        doc.setTitle(docData.getTitle());
        doc.setSource(docData.getSource());
        doc.setContent(docData.getContent());
        doc.setDocumentType(docData.getDocumentType());
        doc.setMetadata(docData.getMetadata());
        doc.setSize(docData.getSize() != null
                ? docData.getSize()
                : docData.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length * 1L);
        doc.setContentHash(DigestUtils.sha256(docData.getContent()));
        doc.setOriginalFilename(docData.getOriginalFilename());
        doc.setExternalId(docData.getExternalId());
        doc.setJsonbPayload(docData.getJsonbPayload() == null
                ? null : docData.getJsonbPayload().deepCopy());
        doc.setCollectionId(collectionId);
        doc.setEnabled(docData.getEnabled() == null ? true : docData.getEnabled());
        doc.setProcessingStatus("PENDING");
        return doc;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToMap(Object obj) {
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        return null;
    }

    // Null-safe generic audit helper (AuditLogService is optional)
    private void audit(AuditLogService.AuditAction action, String entityType, String entityId, String message) {
        audit(action, entityType, entityId, message, null);
    }
    private void audit(AuditLogService.AuditAction action, String entityType, String entityId, String message, Map<String, Object> details) {
        if (auditLogService == null) return;
        switch (action) {
            case CREATE -> auditLogService.logCreate(entityType, entityId, message, details);
            case UPDATE -> auditLogService.logUpdate(entityType, entityId, message, details);
            case DELETE -> auditLogService.logDelete(entityType, entityId, message, details);
        }
    }
}
