package com.springairag.core.controller;

import com.springairag.api.dto.BatchCreateAndEmbedRequest;
import com.springairag.api.dto.BatchCreateAndEmbedResponse;
import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.BatchDocumentRequest;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.FileUploadResponse;
import com.springairag.api.dto.BatchEmbedProgressEvent;
import com.springairag.api.dto.EmbedProgressEvent;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档管理控制器
 *
 * <p>提供文档的 CRUD 操作和嵌入向量管理。
 * 业务逻辑委托给 {@link DocumentEmbedService} 和 {@link BatchDocumentService}。
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/documents")
@Tag(name = "RAG Documents", description = "文档管理（CRUD + 嵌入向量生成）")
public class RagDocumentController {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentController.class);

    private final RagDocumentRepository documentRepository;
    private final RagEmbeddingRepository embeddingRepository;
    private final DocumentEmbedService documentEmbedService;
    private final BatchDocumentService batchDocumentService;
    private final DocumentVersionService documentVersionService;

    // SHA256 utility for content hashing (breaks circular dependency)
    private static String computeSha256(String content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 compute failed", e);
        }
    }

    public RagDocumentController(RagDocumentRepository documentRepository,
                                  RagEmbeddingRepository embeddingRepository,
                                  @Lazy DocumentEmbedService documentEmbedService,
                                  @Lazy BatchDocumentService batchDocumentService,
                                  DocumentVersionService documentVersionService) {
        this.documentRepository = documentRepository;
        this.embeddingRepository = embeddingRepository;
        this.documentEmbedService = documentEmbedService;
        this.batchDocumentService = batchDocumentService;
        this.documentVersionService = documentVersionService;
    }

    // ==================== CRUD ====================

    @Operation(summary = "创建文档", description = "上传文档内容，自动计算内容哈希用于去重。嵌入向量需通过 /embed 接口单独生成。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "创建成功（或检测到重复内容）"),
            @ApiResponse(responseCode = "400", description = "请求参数校验失败")
    })
    @PostMapping
    public ResponseEntity<Map<String, Object>> createDocument(@Valid @RequestBody DocumentRequest request) {
        log.info("Creating document: title={}", request.getTitle());

        String content = request.getContent();
        String contentHash = computeSha256(content);

        // 去重检查
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

        return ResponseEntity.ok(Map.of(
                "id", doc.getId(),
                "title", doc.getTitle(),
                "status", "CREATED",
                "message", "Document created, to generate embedding call POST /api/v1/rag/documents/{id}/embed",
                "contentHash", contentHash
        ));
    }

    @Operation(summary = "获取文档详情", description = "查询文档内容、元数据及嵌入向量数量。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回文档详情"),
            @ApiResponse(responseCode = "404", description = "文档不存在")
    })
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getDocument(@PathVariable Long id) {
        log.info("Getting document: id={}", id);

        return documentRepository.findById(id)
                .map(doc -> {
                    long embeddingCount = embeddingRepository.countByDocumentId(id);
                    Map<String, Object> result = documentToMap(doc);
                    result.put("embeddingCount", embeddingCount);
                    return ResponseEntity.ok(result);
                })
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Operation(summary = "删除文档", description = "删除文档及其关联的嵌入向量（级联删除）。")
    @ApiResponse(responseCode = "200", description = "删除成功")
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteDocument(@PathVariable Long id) {
        return ResponseEntity.ok(batchDocumentService.deleteDocument(id));
    }

    @Operation(summary = "列出文档", description = "分页查询文档列表，支持按标题/类型/状态过滤，按创建时间倒序。")
    @ApiResponse(responseCode = "200", description = "返回文档分页列表")
    @GetMapping
    public ResponseEntity<Map<String, Object>> listDocuments(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String processingStatus,
            @RequestParam(required = false) Boolean enabled) {

        int page = offset / limit;
        var pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        var pageResult = documentRepository.searchDocuments(
                title, documentType, processingStatus, enabled, pageable);

        List<Map<String, Object>> docs = pageResult.getContent().stream()
                .map(this::documentToMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "documents", docs,
                "total", pageResult.getTotalElements(),
                "offset", offset,
                "limit", limit
        ));
    }

    @Operation(summary = "文档统计", description = "获取各处理状态的文档数量统计。")
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

    // ==================== 嵌入向量 ====================

    @Operation(summary = "生成嵌入向量", description = "对文档进行分块并生成嵌入向量，存储到 rag_embeddings 表。已有嵌入时默认跳过，传入 force=true 强制重嵌入。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "嵌入向量生成成功"),
            @ApiResponse(responseCode = "404", description = "文档不存在")
    })
    @PostMapping("/{id}/embed")
    public ResponseEntity<Map<String, Object>> embedDocument(
            @PathVariable Long id,
            @Parameter(description = "强制重嵌入（跳过缓存）")
            @RequestParam(defaultValue = "false") boolean force) {
        try {
            Map<String, Object> result = documentEmbedService.embedDocument(id, force);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "documentId", id
            ));
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
        // 0L = no timeout; client disconnection is detected via IOException in async callback
        SseEmitter emitter = new SseEmitter(0L);
        try {
            documentEmbedService.embedDocumentWithProgress(id, force, event -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name("progress")
                            .data(event));
                } catch (Exception ex) {
                    // Best-effort: client likely disconnected mid-stream
                    log.warn("SSE send failed for document {}: {}", id, ex.getMessage());
                }
            });
            emitter.send(SseEmitter.event().name("done").data(Map.of("documentId", id)));
            emitter.complete();
        } catch (IllegalArgumentException e) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of(
                        "error", e.getMessage(),
                        "documentId", id
                )));
            } catch (Exception ex) { /* best-effort: error already sent via completeWithError */ }
            emitter.completeWithError(e);
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @Operation(summary = "通过 VectorStore 生成嵌入向量",
            description = "使用 VectorStore.add() 自动完成嵌入生成和存储，代码更简洁。存储到 rag_vector_store 表。已有嵌入时默认跳过，传入 force=true 强制重嵌入。")
    @PostMapping("/{id}/embed/vs")
    public ResponseEntity<Map<String, Object>> embedDocumentViaVectorStore(
            @PathVariable Long id,
            @Parameter(description = "强制重嵌入（跳过缓存）")
            @RequestParam(defaultValue = "false") boolean force) {
        try {
            Map<String, Object> result = documentEmbedService.embedDocumentViaVectorStore(id, force);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "documentId", id
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "documentId", id
            ));
        }
    }

    // ==================== 批量操作 ====================

    @Operation(summary = "Batch create documents",
               description = "Upload multiple documents at once (up to 100), auto-deduplication."
                           + " Set embed=true to create and embed in one step (no need to call /batch/embed)."
                           + " Single document failure does not affect other documents.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回创建结果"),
            @ApiResponse(responseCode = "400", description = "请求参数无效（ids 为空/超限）")
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
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "批量删除文档", description = "按 ID 列表批量删除文档及其嵌入向量。单条不存在不影响其他文档。")
    @DeleteMapping("/batch")
    public ResponseEntity<Map<String, Object>> batchDeleteDocuments(
            @RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids 列表不能为空");
        }
        Map<String, Object> result = batchDocumentService.batchDeleteDocuments(ids);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "批量生成嵌入向量", description = "对多个文档批量执行分块和嵌入生成。单个文档失败不影响其他文档。")
    @PostMapping("/batch/embed")
    public ResponseEntity<Map<String, Object>> batchEmbedDocuments(
            @RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids 列表不能为空");
        }
        if (ids.size() > 50) {
            throw new IllegalArgumentException("Batch embedding limited to 50 documents per request (API rate limit)");
        }
        Map<String, Object> result = documentEmbedService.batchEmbedDocuments(ids);
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

        SseEmitter emitter = new SseEmitter(0L);
        try {
            documentEmbedService.batchEmbedDocumentsWithProgress(ids, event -> {
                try {
                    emitter.send(SseEmitter.event().name("progress").data(event));
                } catch (Exception ex) {
                    log.warn("SSE send failed for batch embed: {}", ex.getMessage());
                }
            });
            emitter.send(SseEmitter.event().name("done").data(Map.of("total", ids.size(), "status", "completed")));
            emitter.complete();
        } catch (IllegalArgumentException e) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("error", e.getMessage())));
            } catch (Exception ex) { /* best-effort */ }
            emitter.completeWithError(e);
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    // ==================== 批量创建并嵌入（已废弃，使用 /batch?embed=true 代替） ====================

    /**
     * @deprecated 请使用 {@link #batchCreateDocuments(BatchDocumentRequest)} batch=true}，
     *             功能完全相同，且无需额外端点。
     *             例如：POST /batch + body { "documents": [...], "embed": true, "collectionId": 1 }
     */
    @Deprecated
    @Operation(summary = "批量创建并嵌入文档（已废弃）",
               description = "@Deprecated 请改用 POST /batch + embed=true。功能完全相同。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回创建和嵌入结果"),
            @ApiResponse(responseCode = "400", description = "请求参数无效")
    })
    @PostMapping("/batch/create-and-embed")
    public ResponseEntity<BatchCreateAndEmbedResponse> batchCreateAndEmbed(
            @Valid @RequestBody BatchCreateAndEmbedRequest request) {
        log.info("Batch create and embed (deprecated): collectionId={}, docs={}, force={}",
                request.getCollectionId(), request.getDocuments().size(), request.isForce());

        // 委托给服务层（统一使用 embed=true）
        BatchCreateResponse resp = batchDocumentService.batchCreateDocuments(
                request.getDocuments(), true, request.getCollectionId(), request.isForce());

        // 转换为旧的响应格式
        List<BatchCreateAndEmbedResponse.DocumentResult> results = resp.results().stream()
                .map(r -> new BatchCreateAndEmbedResponse.DocumentResult(
                        r.documentId(), r.title(), r.newlyCreated(), 0, r.error()))
                .toList();

        return ResponseEntity.ok(new BatchCreateAndEmbedResponse(
                resp.created(), resp.created(), resp.skipped(), resp.failed(), results));
    }

    // ==================== 文件上传并嵌入 ====================

    @Operation(summary = "上传文件并嵌入", description = "上传文本文件（txt/md 等），自动创建文档并生成嵌入向量。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "文件处理完成"),
            @ApiResponse(responseCode = "400", description = "无文件或文件格式不支持")
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
        return ResponseEntity.ok(new FileUploadResponse(files.length, success, failed, results));
    }

    private FileUploadResponse.FileResult processUploadedFile(
            MultipartFile file, Long collectionId, boolean force) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "unnamed";
        }

        try {
            // 提取文件扩展名
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
            }

            // 检查是否为支持的文本类型
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
                // 尝试作为文本读取
                try {
                    file.getBytes(); // 触发检查
                } catch (Exception e) {
                    return new FileUploadResponse.FileResult(
                            filename, null, null, false, 0,
                            "Unsupported file type: " + contentType + ", only text files supported (txt/md/json/xml/html/csv/log)");
                }
            }

            // 读取文件内容
            String content = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return new FileUploadResponse.FileResult(
                        filename, null, null, false, 0, "File content is empty");
            }

            // 构建文档标题（使用文件名）
            String title = filename;
            if (title.toLowerCase().endsWith(".txt") || title.toLowerCase().endsWith(".md")) {
                title = title.substring(0, title.lastIndexOf('.'));
            }

            // 创建文档请求
            DocumentRequest docReq = new DocumentRequest(title, content);
            if (collectionId != null) {
                docReq.setCollectionId(collectionId);
            }

            // 使用服务层统一处理（创建 + 嵌入）
            BatchCreateResponse resp = batchDocumentService.batchCreateDocuments(
                    List.of(docReq), true, collectionId, force);
            BatchCreateResponse.DocumentResult r = resp.results().getFirst();

            if (r.documentId() != null) {
                // 嵌入成功（因为 embed=true）
                return new FileUploadResponse.FileResult(filename, r.documentId(), title, true, 0, null);
            } else {
                return new FileUploadResponse.FileResult(
                        filename, null, title, false, 0,
                        r.error() != null ? r.error() : "Creation failed");
            }

        } catch (Exception e) {
            log.error("Failed to process uploaded file '{}': {}", filename, e.getMessage());
            return new FileUploadResponse.FileResult(
                    filename, null, null, false, 0,
                    "Processing failed: " + e.getMessage());
        }
    }

    // ==================== 版本历史 ====================

    @Operation(summary = "获取文档版本历史", description = "分页查询文档的内容变更版本记录，最新在前。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回版本历史"),
            @ApiResponse(responseCode = "404", description = "文档不存在")
    })
    @GetMapping("/{id}/versions")
    public ResponseEntity<Map<String, Object>> getVersionHistory(
            @Parameter(description = "文档 ID") @PathVariable Long id,
            @Parameter(description = "页码") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") int size) {

        if (!documentRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        var versions = documentVersionService.getVersionHistory(id, PageRequest.of(page, size));
        Map<String, Object> result = new HashMap<>();
        result.put("documentId", id);
        result.put("totalVersions", versions.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        result.put("versions", versions.getContent().stream().map(this::versionToMap).collect(Collectors.toList()));
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "获取指定版本", description = "查询文档的指定版本详情（含内容快照）。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回版本详情"),
            @ApiResponse(responseCode = "404", description = "版本不存在")
    })
    @GetMapping("/{id}/versions/{versionNumber}")
    public ResponseEntity<Map<String, Object>> getVersion(
            @Parameter(description = "文档 ID") @PathVariable Long id,
            @Parameter(description = "版本号") @PathVariable int versionNumber) {

        return documentVersionService.getVersion(id, versionNumber)
                .map(v -> ResponseEntity.ok(versionToMap(v)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== 辅助方法 ====================

    private Map<String, Object> documentToMap(RagDocument doc) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", doc.getId());
        map.put("title", doc.getTitle());
        map.put("source", doc.getSource());
        map.put("document_type", doc.getDocumentType());
        map.put("enabled", doc.getEnabled());
        map.put("created_at", doc.getCreatedAt());
        map.put("updated_at", doc.getUpdatedAt());
        map.put("processing_status", doc.getProcessingStatus());
        map.put("size", doc.getSize());
        if (doc.getContent() != null) {
            map.put("content", doc.getContent());
        }
        if (doc.getMetadata() != null) {
            map.put("metadata", doc.getMetadata());
        }
        return map;
    }

    private Map<String, Object> versionToMap(RagDocumentVersion v) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", v.getId());
        map.put("documentId", v.getDocumentId());
        map.put("versionNumber", v.getVersionNumber());
        map.put("contentHash", v.getContentHash());
        map.put("size", v.getSize());
        map.put("changeType", v.getChangeType());
        map.put("changeDescription", v.getChangeDescription());
        map.put("createdAt", v.getCreatedAt());
        // 仅在单版本详情中返回内容快照，列表中省略以节省带宽
        if (v.getContentSnapshot() != null) {
            map.put("contentSnapshot", v.getContentSnapshot());
        }
        return map;
    }
}
