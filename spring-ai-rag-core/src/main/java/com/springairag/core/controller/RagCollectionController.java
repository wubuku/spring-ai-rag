package com.springairag.core.controller;

import com.springairag.api.dto.CollectionCloneResponse;
import com.springairag.api.dto.CollectionCreatedResponse;
import com.springairag.api.dto.CollectionDeleteResponse;
import com.springairag.api.dto.CollectionExportResponse;
import com.springairag.api.dto.CollectionDocumentListResponse;
import com.springairag.api.dto.CollectionImportResponse;
import com.springairag.api.dto.CollectionRequest;
import com.springairag.api.dto.CollectionRestoreResponse;
import com.springairag.api.dto.DocumentAddedResponse;
import com.springairag.api.dto.DocumentSummary;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.util.CollectionMapper;
import org.springframework.data.domain.Page;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.RagCollectionService;
import com.springairag.core.versioning.ApiVersion;
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
    private AuditLogService auditLogService;  // optional: null when RagAuditLogRepository unavailable

    public RagCollectionController(RagCollectionRepository collectionRepository,
                                    RagDocumentRepository documentRepository,
                                    RagCollectionService collectionService,
                                    @Autowired(required = false) AuditLogService auditLogService) {
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.collectionService = collectionService;
        this.auditLogService = auditLogService;
    }

    /**
     * Create a collection.
     */
    @Operation(summary = "Create collection", description = "Create a new document collection (knowledge base).")
    @ApiResponse(responseCode = "200", description = "Collection created, returns collection info")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CollectionRequest request) {
        log.info("Creating collection: name={}", request.getName());

        RagCollection collection = new RagCollection();
        collection.setName(request.getName());
        collection.setDescription(request.getDescription());
        collection.setEmbeddingModel(request.getEmbeddingModel());
        collection.setDimensions(request.getDimensions() != null ? request.getDimensions() : 1024);
        collection.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        collection.setMetadata(request.getMetadata());

        collection = collectionRepository.save(collection);

        log.info("Collection created: id={}, name={}", collection.getId(), collection.getName());
        auditCreate(AuditLogService.ENTITY_COLLECTION,
                String.valueOf(collection.getId()),
                "Collection created: " + collection.getName());

        return ResponseEntity.ok(CollectionMapper.toMap(collection, 0));
    }

    /**
     * Get collection details.
     */
    @Operation(summary = "Get collection details", description = "Query collection info and document count.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Collection found"),
            @ApiResponse(responseCode = "404", description = "Collection not found or deleted")
    })
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Long id) {
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
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Boolean enabled) {

        int page = offset / limit;
        var pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"));

        var pageResult = collectionRepository.searchCollections(name, enabled, pageable);

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
    @Operation(summary = "Update collection", description = "Update collection name, description, etc.")
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @Valid @RequestBody CollectionRequest request) {
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
                    auditUpdate(AuditLogService.ENTITY_COLLECTION,
                            String.valueOf(id),
                            "Collection updated: " + existing.getName());
                    return ResponseEntity.ok(CollectionMapper.toMap(saved, docCount));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Delete collection (soft delete).
     */
    @Operation(summary = "Delete collection (soft delete)", description = "Soft-deletes the collection. Associated documents are unlinked (not deleted). Can be restored via POST /{id}/restore.")
    @ApiResponse(responseCode = "200", description = "Collection soft-deleted")
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        log.info("Soft-deleting collection: id={}", id);

        return collectionService.deleteCollection(id)
                .map(result -> ResponseEntity.ok(Map.of(
                        "message", "Collection deleted",
                        "id", String.valueOf(result.id()),
                        "documentsUnlinked", String.valueOf(result.documentsUnlinked())
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Restore a deleted collection.
     */
    @Operation(summary = "Restore deleted collection", description = "Restores a soft-deleted collection. Associated documents will NOT be re-linked automatically.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Collection restored"),
            @ApiResponse(responseCode = "404", description = "Collection not found or not deleted")
    })
    @PostMapping("/{id}/restore")
    public ResponseEntity<CollectionRestoreResponse> restore(@PathVariable Long id) {
        log.info("Restoring collection: id={}", id);

        return collectionService.restoreCollection(id)
                .map(result -> {
                    long docCount = documentRepository.countByCollectionId(result.collection().getId());
                    return ResponseEntity.ok(CollectionRestoreResponse.of(
                            result.collection().getId(),
                            result.collection().getName(),
                            docCount));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Clone collection (deep copy).
     */
    @Operation(summary = "Clone collection", description = "Creates a deep copy of an existing collection. All documents are copied with PENDING processing status (embeddings are not copied and must be re-embedded).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Collection cloned successfully"),
            @ApiResponse(responseCode = "404", description = "Source collection not found or deleted")
    })
    @PostMapping("/{id}/clone")
    public ResponseEntity<CollectionCloneResponse> cloneCollection(@PathVariable Long id) {
        log.info("Cloning collection: id={}", id);

        return collectionService.cloneCollection(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * List documents in a collection.
     */
    @Operation(summary = "List documents in collection", description = "Query document list under the specified collection (paginated).")
    @GetMapping("/{id}/documents")
    public ResponseEntity<CollectionDocumentListResponse> listDocuments(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String processingStatus) {

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

        List<DocumentSummary> docs = pageResult.getContent().stream()
                .map(this::toDocumentSummary)
                .toList();

        return ResponseEntity.ok(new CollectionDocumentListResponse(
                id, docs, pageResult.getTotalElements(), offset, limit));
    }

    private DocumentSummary toDocumentSummary(RagDocument doc) {
        return new DocumentSummary(
                doc.getId(),
                doc.getTitle(),
                doc.getSource(),
                doc.getDocumentType(),
                doc.getProcessingStatus(),
                doc.getCreatedAt(),
                doc.getSize());
    }

    /**
     * Add document to collection.
     */
    @Operation(summary = "Add document to collection", description = "Associate the specified document with a collection.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document added successfully, returns details"),
            @ApiResponse(responseCode = "404", description = "Collection not found")
    })
    @PostMapping("/{id}/documents")
    public ResponseEntity<DocumentAddedResponse> addDocument(
            @PathVariable Long id,
            @RequestBody Map<String, Long> request) {

        Long documentId = request.get("documentId");
        if (documentId == null) {
            throw new IllegalArgumentException("documentId is required");
        }

        if (!collectionRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        return documentRepository.findById(documentId)
                .map(doc -> {
                    doc.setCollectionId(id);
                    documentRepository.save(doc);

                    log.info("Document {} added to collection {}", documentId, id);
                    auditUpdate(AuditLogService.ENTITY_DOCUMENT,
                            String.valueOf(documentId),
                            "Document added to collection " + id,
                            Map.of("collectionId", id));
                    return ResponseEntity.ok(DocumentAddedResponse.of(id, documentId));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Export collection (with document metadata).
     */
    @Operation(summary = "Export collection", description = "Export collection info and document metadata as JSON for backup and migration.")
    @GetMapping("/{id}/export")
    public ResponseEntity<CollectionExportResponse> exportCollection(@PathVariable Long id) {
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

    private CollectionExportResponse buildExportResponse(RagCollection collection, List<RagDocument> docs) {
        List<CollectionExportResponse.ExportedDocumentSummary> docList = docs.stream()
                .map(doc -> new CollectionExportResponse.ExportedDocumentSummary(
                        doc.getTitle(),
                        doc.getSource(),
                        doc.getContent(),
                        doc.getDocumentType(),
                        doc.getMetadata(),
                        doc.getSize()))
                .toList();

        return new CollectionExportResponse(
                collection.getName(),
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
    public ResponseEntity<Map<String, Object>> importCollection(@RequestBody Map<String, Object> importData) {
        String name = (String) importData.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }

        log.info("Importing collection: name={}", name);

        RagCollection collection = buildCollectionFromImport(importData);
        collection = collectionRepository.save(collection);

        int importedDocs = importDocuments(collection.getId(), importData);

        log.info("Collection imported: id={}, name={}, documents={}",
                collection.getId(), name, importedDocs);
        auditCreate(AuditLogService.ENTITY_COLLECTION,
                String.valueOf(collection.getId()),
                "Collection imported: " + name + ", documents: " + importedDocs,
                Map.of("importedDocuments", importedDocs));

        Map<String, Object> result = CollectionMapper.toMap(collection, importedDocs);
        result.put("importedDocuments", importedDocs);
        return ResponseEntity.ok(result);
    }

    private RagCollection buildCollectionFromImport(Map<String, Object> importData) {
        RagCollection collection = new RagCollection();
        collection.setName((String) importData.get("name"));
        collection.setDescription((String) importData.get("description"));
        collection.setEmbeddingModel((String) importData.get("embeddingModel"));
        Object dims = importData.get("dimensions");
        collection.setDimensions(dims != null ? ((Number) dims).intValue() : 1024);
        Object enabled = importData.get("enabled");
        collection.setEnabled(enabled != null ? (Boolean) enabled : true);
        collection.setMetadata(castToMap(importData.get("metadata")));
        return collection;
    }

    @SuppressWarnings("unchecked")
    private int importDocuments(Long collectionId, Map<String, Object> importData) {
        int count = 0;
        List<Map<String, Object>> docList = (List<Map<String, Object>>) importData.get("documents");
        if (docList != null) {
            for (Map<String, Object> docData : docList) {
                documentRepository.save(buildDocumentFromImport(docData, collectionId));
                count++;
            }
        }
        return count;
    }

    private RagDocument buildDocumentFromImport(Map<String, Object> docData, Long collectionId) {
        RagDocument doc = new RagDocument();
        doc.setTitle((String) docData.get("title"));
        doc.setSource((String) docData.get("source"));
        doc.setContent((String) docData.get("content"));
        doc.setDocumentType((String) docData.get("documentType"));
        doc.setMetadata(castToMap(docData.get("metadata")));
        Object size = docData.get("size");
        doc.setSize(size != null ? ((Number) size).longValue() : 0L);
        doc.setCollectionId(collectionId);
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

    // Null-safe audit logging helpers (AuditLogService is optional)
    private void auditCreate(String entityType, String entityId, String message) {
        if (auditLogService != null) auditLogService.logCreate(entityType, entityId, message);
    }
    private void auditCreate(String entityType, String entityId, String message, Map<String, Object> details) {
        if (auditLogService != null) auditLogService.logCreate(entityType, entityId, message, details);
    }
    private void auditUpdate(String entityType, String entityId, String message) {
        if (auditLogService != null) auditLogService.logUpdate(entityType, entityId, message);
    }
    private void auditUpdate(String entityType, String entityId, String message, Map<String, Object> details) {
        if (auditLogService != null) auditLogService.logUpdate(entityType, entityId, message, details);
    }
    private void auditDelete(String entityType, String entityId, String message) {
        if (auditLogService != null) auditLogService.logDelete(entityType, entityId, message);
    }
    private void auditDelete(String entityType, String entityId, String message, Map<String, Object> details) {
        if (auditLogService != null) auditLogService.logDelete(entityType, entityId, message, details);
    }
}
