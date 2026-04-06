package com.springairag.core.controller;

import com.springairag.api.dto.CollectionCloneResponse;
import com.springairag.api.dto.CollectionCreatedResponse;
import com.springairag.api.dto.CollectionDeleteResponse;
import com.springairag.api.dto.CollectionImportResponse;
import com.springairag.api.dto.CollectionRequest;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import org.springframework.data.domain.Page;
import com.springairag.core.service.AuditLogService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档集合（知识库）管理控制器
 *
 * <p>提供集合的 CRUD 操作，支持多知识库/多租户隔离。
 * 集合用于组织文档，每个文档可归属一个集合。
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/collections")
@Tag(name = "RAG Collections", description = "文档集合（知识库）管理")
public class RagCollectionController {

    private static final Logger log = LoggerFactory.getLogger(RagCollectionController.class);

    private final RagCollectionRepository collectionRepository;
    private final RagDocumentRepository documentRepository;
    private AuditLogService auditLogService;  // optional: null when RagAuditLogRepository unavailable

    public RagCollectionController(RagCollectionRepository collectionRepository,
                                    RagDocumentRepository documentRepository,
                                    @Autowired(required = false) AuditLogService auditLogService) {
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * 创建集合
     */
    @Operation(summary = "创建集合", description = "创建新的文档集合（知识库）。")
    @ApiResponse(responseCode = "200", description = "创建成功，返回集合信息")
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

        return ResponseEntity.ok(toMap(collection, 0));
    }

    /**
     * 获取集合详情
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
                    return ResponseEntity.ok(toMap(c, docCount));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 列出集合（分页）
     */
    @Operation(summary = "列出集合", description = "分页查询集合列表，按创建时间倒序。")
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
                    return toMap(c, docCount);
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "collections", items,
                "total", pageResult.getTotalElements(),
                "offset", offset,
                "limit", limit));
    }

    /**
     * 更新集合
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
                    return ResponseEntity.ok(toMap(saved, docCount));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 删除集合（软删除）
     */
    @Operation(summary = "Delete collection (soft delete)", description = "Soft-deletes the collection. Associated documents are unlinked (not deleted). Can be restored via POST /{id}/restore.")
    @ApiResponse(responseCode = "200", description = "Collection soft-deleted")
    @Transactional
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        log.info("Soft-deleting collection: id={}", id);

        return collectionRepository.findByIdAndDeletedFalse(id)
                .map(collection -> {
                    // 批量清空关联文档的 collection_id（避免逐个加载）
                    long count = documentRepository.countByCollectionId(id);
                    if (count > 0) {
                        documentRepository.clearCollectionIdByCollectionId(id);
                        log.info("Unlinked {} documents from collection {}", count, id);
                    }

                    collectionRepository.softDelete(id, java.time.LocalDateTime.now());

                    log.info("Collection soft-deleted: id={}", id);
                    auditDelete(AuditLogService.ENTITY_COLLECTION,
                            String.valueOf(id),
                            "Collection soft-deleted, documentsUnlinked: " + count);
                    return ResponseEntity.ok(Map.of(
                            "message", "Collection deleted",
                            "id", String.valueOf(id),
                            "documentsUnlinked", String.valueOf(count)
                    ));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 恢复已删除的集合
     */
    @Operation(summary = "Restore deleted collection", description = "Restores a soft-deleted collection. Associated documents will NOT be re-linked automatically.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Collection restored"),
            @ApiResponse(responseCode = "404", description = "Collection not found or not deleted")
    })
    @Transactional
    @PostMapping("/{id}/restore")
    public ResponseEntity<Map<String, Object>> restore(@PathVariable Long id) {
        log.info("Restoring collection: id={}", id);

        int updated = collectionRepository.restore(id);
        if (updated == 0) {
            log.warn("Collection not found or not deleted for restore: id={}", id);
            return ResponseEntity.notFound().build();
        }

        log.info("Collection restored: id={}", id);
        auditUpdate(AuditLogService.ENTITY_COLLECTION,
                String.valueOf(id),
                "Collection restored");

        return collectionRepository.findById(id)
                .map(c -> {
                    long docCount = documentRepository.countByCollectionId(id);
                    return ResponseEntity.ok(toMap(c, docCount));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 克隆集合（深拷贝）
     */
    @Operation(summary = "Clone collection", description = "Creates a deep copy of an existing collection. All documents are copied with PENDING processing status (embeddings are not copied and must be re-embedded).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Collection cloned successfully"),
            @ApiResponse(responseCode = "404", description = "Source collection not found or deleted")
    })
    @PostMapping("/{id}/clone")
    public ResponseEntity<CollectionCloneResponse> cloneCollection(@PathVariable Long id) {
        log.info("Cloning collection: id={}", id);

        return collectionRepository.findByIdAndDeletedFalse(id)
                .map(source -> {
                    // Build new collection as a copy
                    RagCollection cloned = new RagCollection();
                    cloned.setName(source.getName() + " (Copy)");
                    cloned.setDescription(source.getDescription());
                    cloned.setEmbeddingModel(source.getEmbeddingModel());
                    cloned.setDimensions(source.getDimensions());
                    cloned.setEnabled(source.getEnabled());
                    cloned.setMetadata(source.getMetadata());
                    final RagCollection saved = collectionRepository.save(cloned);

                    // Copy all documents (content + metadata only; embeddings require re-embedding)
                    List<RagDocument> sourceDocs = documentRepository.findAllByCollectionId(id);
                    List<RagDocument> clonedDocs = sourceDocs.stream()
                            .map(doc -> cloneDocument(doc, saved.getId()))
                            .toList();
                    if (!clonedDocs.isEmpty()) {
                        documentRepository.saveAll(clonedDocs);
                    }

                    log.info("Collection cloned: sourceId={}, newId={}, documents={}",
                            id, saved.getId(), clonedDocs.size());
                    auditCreate(AuditLogService.ENTITY_COLLECTION,
                            String.valueOf(saved.getId()),
                            "Collection cloned from " + source.getName() + " (ID: " + id + "), documents: " + clonedDocs.size(),
                            Map.of("sourceCollectionId", id,
                                    "sourceCollectionName", source.getName(),
                                    "documentsCloned", clonedDocs.size()));

                    return ResponseEntity.ok(CollectionCloneResponse.of(
                            saved.getId(),
                            saved.getName(),
                            id,
                            source.getName(),
                            clonedDocs.size()));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private RagDocument cloneDocument(RagDocument source, Long newCollectionId) {
        RagDocument doc = new RagDocument();
        doc.setTitle(source.getTitle());
        doc.setSource(source.getSource());
        doc.setContent(source.getContent());
        doc.setDocumentType(source.getDocumentType());
        doc.setMetadata(source.getMetadata());
        doc.setSize(source.getSize());
        doc.setCollectionId(newCollectionId);
        doc.setEnabled(source.getEnabled());
        doc.setProcessingStatus("PENDING");  // Must re-embed; embeddings not copied
        return doc;
    }

    /**
     * 列出集合中的文档
     */
    @Operation(summary = "列出集合中的文档", description = "查询指定集合下的文档列表（分页）。")
    @GetMapping("/{id}/documents")
    public ResponseEntity<Map<String, Object>> listDocuments(
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

        List<Map<String, Object>> docs = pageResult.getContent().stream()
                .map(this::toDocumentSummary)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "collectionId", id,
                "documents", docs,
                "total", pageResult.getTotalElements(),
                "offset", offset,
                "limit", limit));
    }

    private Map<String, Object> toDocumentSummary(RagDocument doc) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", doc.getId());
        m.put("title", doc.getTitle());
        m.put("source", doc.getSource());
        m.put("document_type", doc.getDocumentType());
        m.put("processing_status", doc.getProcessingStatus());
        m.put("created_at", doc.getCreatedAt());
        m.put("size", doc.getSize());
        return m;
    }

    /**
     * 将文档加入集合
     */
    @Operation(summary = "将文档加入集合", description = "将指定文档关联到集合中。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "添加成功，返回添加详情"),
            @ApiResponse(responseCode = "404", description = "集合不存在")
    })
    @PostMapping("/{id}/documents")
    public ResponseEntity<Map<String, Object>> addDocument(
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
                    Map<String, Object> result = new HashMap<>();
                    result.put("message", "Document added to collection");
                    result.put("collectionId", id);
                    result.put("documentId", documentId);
                    return ResponseEntity.ok(result);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 导出集合（含文档元数据）
     */
    @Operation(summary = "导出集合", description = "导出集合信息及其文档元数据为 JSON，用于备份和迁移。")
    @GetMapping("/{id}/export")
    public ResponseEntity<Map<String, Object>> exportCollection(@PathVariable Long id) {
        log.info("Exporting collection: id={}", id);

        return collectionRepository.findByIdAndDeletedFalse(id)
                .map(collection -> {
                    List<RagDocument> docs = documentRepository.findAllByCollectionId(id);
                    Map<String, Object> exportData = buildExportData(collection, docs);
                    log.info("Collection exported: id={}, documents={}", id, docs.size());
                    return ResponseEntity.ok(exportData);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> buildExportData(RagCollection collection, List<RagDocument> docs) {
        List<Map<String, Object>> docList = docs.stream()
                .map(doc -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("title", doc.getTitle());
                    m.put("source", doc.getSource());
                    m.put("content", doc.getContent());
                    m.put("documentType", doc.getDocumentType());
                    m.put("metadata", doc.getMetadata());
                    m.put("size", doc.getSize());
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> exportData = new HashMap<>();
        exportData.put("name", collection.getName());
        exportData.put("description", collection.getDescription());
        exportData.put("embeddingModel", collection.getEmbeddingModel());
        exportData.put("dimensions", collection.getDimensions());
        exportData.put("enabled", collection.getEnabled());
        exportData.put("metadata", collection.getMetadata());
        exportData.put("documents", docList);
        exportData.put("exportedAt", java.time.Instant.now().toString());
        exportData.put("documentCount", docs.size());
        return exportData;
    }

    /**
     * 导入集合（从 JSON 创建新集合）
     */
    @Operation(summary = "导入集合", description = "从导出的 JSON 数据创建新集合及其文档。")
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

        Map<String, Object> result = toMap(collection, importedDocs);
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

    private Map<String, Object> toMap(RagCollection c, long documentCount) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", c.getId());
        map.put("name", c.getName());
        map.put("description", c.getDescription());
        map.put("embeddingModel", c.getEmbeddingModel());
        map.put("dimensions", c.getDimensions());
        map.put("enabled", c.getEnabled());
        map.put("metadata", c.getMetadata());
        map.put("createdAt", c.getCreatedAt());
        map.put("updatedAt", c.getUpdatedAt());
        map.put("deleted", c.getDeleted());
        map.put("deletedAt", c.getDeletedAt());
        map.put("documentCount", documentCount);
        return map;
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
