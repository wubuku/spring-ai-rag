package com.springairag.core.controller;

import com.springairag.api.dto.CollectionRequest;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
@RequestMapping("/api/v1/rag/collections")
@Tag(name = "RAG Collections", description = "文档集合（知识库）管理")
public class RagCollectionController {

    private static final Logger log = LoggerFactory.getLogger(RagCollectionController.class);

    private final RagCollectionRepository collectionRepository;
    private final RagDocumentRepository documentRepository;

    public RagCollectionController(RagCollectionRepository collectionRepository,
                                    RagDocumentRepository documentRepository) {
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
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

        return ResponseEntity.ok(toMap(collection, 0));
    }

    /**
     * 获取集合详情
     */
    @Operation(summary = "获取集合详情", description = "查询集合信息及其文档数量。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回集合详情"),
            @ApiResponse(responseCode = "404", description = "集合不存在")
    })
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Long id) {
        log.info("Getting collection: id={}", id);

        return collectionRepository.findById(id)
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
                "limit", limit
        ));
    }

    /**
     * 更新集合
     */
    @Operation(summary = "更新集合", description = "更新集合名称、描述等信息。")
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @Valid @RequestBody CollectionRequest request) {
        log.info("Updating collection: id={}", id);

        return collectionRepository.findById(id)
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
                    return ResponseEntity.ok(toMap(saved, docCount));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 删除集合
     */
    @Operation(summary = "删除集合", description = "删除集合。关联的文档 collection_id 将被置空（不删除文档）。")
    @ApiResponse(responseCode = "200", description = "删除成功")
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        log.info("Deleting collection: id={}", id);

        return collectionRepository.findById(id)
                .map(collection -> {
                    // 将关联文档的 collection_id 置空
                    List<RagDocument> docs = documentRepository.findAllByCollectionId(id);
                    for (RagDocument doc : docs) {
                        doc.setCollectionId(null);
                    }
                    if (!docs.isEmpty()) {
                        documentRepository.saveAll(docs);
                        log.info("Unlinked {} documents from collection {}", docs.size(), id);
                    }

                    collectionRepository.deleteById(id);

                    log.info("Collection deleted: id={}", id);
                    return ResponseEntity.ok(Map.of(
                            "message", "集合已删除",
                            "id", String.valueOf(id),
                            "documentsUnlinked", String.valueOf(docs.size())
                    ));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 列出集合中的文档
     */
    @Operation(summary = "列出集合中的文档", description = "查询指定集合下的文档列表（分页）。")
    @GetMapping("/{id}/documents")
    public ResponseEntity<Map<String, Object>> listDocuments(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {

        if (!collectionRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        int page = offset / limit;
        var pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        var pageResult = documentRepository.findByCollectionId(id, pageable);

        List<Map<String, Object>> docs = pageResult.getContent().stream()
                .map(doc -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", doc.getId());
                    m.put("title", doc.getTitle());
                    m.put("source", doc.getSource());
                    m.put("document_type", doc.getDocumentType());
                    m.put("processing_status", doc.getProcessingStatus());
                    m.put("created_at", doc.getCreatedAt());
                    m.put("size", doc.getSize());
                    return m;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "collectionId", id,
                "documents", docs,
                "total", pageResult.getTotalElements(),
                "offset", offset,
                "limit", limit
        ));
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
            Map<String, Object> error = new HashMap<>();
            error.put("error", "documentId 不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        if (!collectionRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        return documentRepository.findById(documentId)
                .map(doc -> {
                    doc.setCollectionId(id);
                    documentRepository.save(doc);

                    log.info("Document {} added to collection {}", documentId, id);
                    Map<String, Object> result = new HashMap<>();
                    result.put("message", "文档已加入集合");
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

        return collectionRepository.findById(id)
                .map(collection -> {
                    List<RagDocument> docs = documentRepository.findAllByCollectionId(id);

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

                    log.info("Collection exported: id={}, documents={}", id, docs.size());
                    return ResponseEntity.ok(exportData);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 导入集合（从 JSON 创建新集合）
     */
    @Operation(summary = "导入集合", description = "从导出的 JSON 数据创建新集合及其文档。")
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importCollection(@RequestBody Map<String, Object> importData) {
        String name = (String) importData.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.<String, Object>of("error", "name 不能为空"));
        }

        log.info("Importing collection: name={}", name);

        RagCollection collection = buildCollectionFromImport(importData);
        collection = collectionRepository.save(collection);

        int importedDocs = importDocuments(collection.getId(), importData);

        log.info("Collection imported: id={}, name={}, documents={}",
                collection.getId(), name, importedDocs);

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
        map.put("documentCount", documentCount);
        return map;
    }
}
