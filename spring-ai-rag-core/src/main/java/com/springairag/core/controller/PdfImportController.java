package com.springairag.core.controller;


import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.FileTreeEntryResponse;
import com.springairag.api.dto.FileTreeResponse;
import com.springairag.api.dto.PdfImportResponse;
import com.springairag.api.dto.PdfToRagResponse;
import com.springairag.core.entity.FsFile;
import com.springairag.core.service.MarkdownRendererService;
import com.springairag.core.service.PdfImportService;
import com.springairag.core.service.PdfToRagService;
import com.springairag.core.util.SseEmitters;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * PDF Import and File Preview Controller
 *
 * <p>Provides PDF import with automatic conversion to Markdown + images,
 * and a preview endpoint that renders the entry Markdown file as HTML
 * with image links pointing to the raw file download endpoint.
 *
 * <p>API design:
 * <ul>
 *   <li>POST /files/pdf       — import a PDF (convert + store directory tree)</li>
 *   <li>GET  /files/preview   — preview entry Markdown as HTML (query: path=...)</li>
 *   <li>GET  /files/raw       — download a raw file (query: path=...)</li>
 *   <li>GET  /files/tree      — list directory entries (query: path=...)</li>
 * </ul>
 *
 * <p>This controller is only instantiated when the JPA infrastructure
 * ({@code FsFileRepository}) is available. This ensures it is not activated
 * in test contexts that disable DataSource/JPA (e.g., {@code OpenApiContractTest}).
 */
@ApiVersion("v1")
@RestController
@RequestMapping("/rag/files")
@Tag(name = "File System Import", description = "PDF import, directory tree management, and file preview")
public class PdfImportController {

    private static final Logger log = LoggerFactory.getLogger(PdfImportController.class);

    private final PdfImportService pdfImportService;
    private final MarkdownRendererService markdownRendererService;
    private final PdfToRagService pdfToRagService;

    public PdfImportController(PdfImportService pdfImportService,
                               MarkdownRendererService markdownRendererService,
                               PdfToRagService pdfToRagService) {
        this.pdfImportService = pdfImportService;
        this.markdownRendererService = markdownRendererService;
        this.pdfToRagService = pdfToRagService;
    }

    // ==================== PDF Import ====================

    @Operation(summary = "Import PDF file",
               description = "Upload a PDF file. The PDF is converted to Markdown + images using the configured CLI tool "
                           + "(default: marker_single). The entire output directory tree is stored in fs_files. "
                           + "Returns the virtual root path of the imported PDF and the entry Markdown file for preview.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF imported successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request (no file, or conversion failed)"),
            @ApiResponse(responseCode = "500", description = "Internal error during import")
    })
    @PostMapping(value = "/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> importPdf(
            @Parameter(description = "PDF file to import")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Optional collection/subdirectory path prefix, e.g., 'papers/2024'")
            @RequestParam(value = "collection", required = false) String collection) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("No file uploaded"));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("Only PDF files are supported"));
        }

        try {
            PdfImportService.PdfImportResult result = pdfImportService.importPdf(file, collection);

            log.info("PDF imported: uuid={}, entryMarkdown={}, files={}",
                    result.uuid(), result.entryMarkdown(), result.filesStored());

            return ResponseEntity.ok(new PdfImportResponse(
                    result.uuid(),
                    result.entryMarkdown(),
                    result.filesStored()
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
        } catch (Exception e) {
            log.error("PDF import failed for '{}': {}", filename, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ErrorResponse.of("PDF import failed: " + e.getMessage()));
        }
    }

    // ==================== PDF Import → RAG Knowledge Base ====================

    /**
     * Import a PDF and add it to the RAG knowledge base (with optional embedding).
     *
     * <p>This is a convenience endpoint that combines {@code POST /files/pdf}
     * (PDF → Markdown conversion + fs_files storage) with optional
     * {@code POST /documents/{id}/embed} (embedding + RAG knowledge base registration).
     *
     * <p>When embed=true (default), returns an SSE stream of embedding progress
     * followed by a "done" event with the final result.
     * When embed=false, returns immediately with the document ID (no embedding).
     *
     * <p>API design:
     * <ul>
     *   <li>POST /files/pdf-to-rag?embed=true  — import + embed with SSE progress stream</li>
     *   <li>POST /files/pdf-to-rag?embed=false — import only, returns immediately</li>
     * </ul>
     *
     * @param file         the PDF file to import
     * @param collectionId optional collection ID to associate with
     * @param embed        whether to trigger embedding (default: true)
     * @return SSE stream (embed=true) or JSON response (embed=false)
     */
    @Operation(summary = "Import PDF to RAG knowledge base",
               description = "Convenience endpoint: imports a PDF (converts to Markdown), stores in fs_files, "
                           + "creates a RagDocument entry, and optionally triggers embedding. "
                           + "When embed=true (default), returns SSE progress stream followed by final result. "
                           + "When embed=false, returns immediately with document ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF imported and added to RAG (embed=false)"),
            @ApiResponse(responseCode = "200", description = "SSE stream: PDF imported and embedding in progress (embed=true)"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "500", description = "Internal error during import or embedding")
    })
    @PostMapping(value = "/pdf-to-rag", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object importPdfToRag(
            @Parameter(description = "PDF file to import")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Optional collection ID to associate with the RAG document")
            @RequestParam(value = "collectionId", required = false) Long collectionId,
            @Parameter(description = "Whether to trigger embedding (default: true). "
                    + "When true, returns SSE progress stream; when false, returns immediately.")
            @RequestParam(value = "embed", defaultValue = "true") boolean embed) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("No file uploaded"));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("Only PDF files are supported"));
        }

        try {
            // Step 1: Import PDF to fs_files (get entry Markdown path)
            PdfImportService.PdfImportResult importResult = pdfImportService.importPdf(file, null);

            log.info("PDF-to-RAG step 1 complete: uuid={}, entryMarkdown={}, files={}",
                    importResult.uuid(), importResult.entryMarkdown(), importResult.filesStored());

            if (!embed) {
                // Step 2a: Create RagDocument WITHOUT embedding (fast, returns immediately)
                PdfToRagService.PdfToRagResult ragResult = pdfToRagService.importPdfToRag(
                        importResult.entryMarkdown(),
                        filename,
                        collectionId,
                        false,  // embed=false
                        false   // forceReembed (irrelevant when embed=false)
                );

                return ResponseEntity.ok(new PdfToRagResponse(
                        ragResult.documentId(),
                        ragResult.title(),
                        ragResult.newlyCreated(),
                        null,   // no embedding
                        null,   // no embedding
                        null,   // no embedding
                        importResult.uuid(),
                        importResult.entryMarkdown()
                ));
            }

            // Step 2b: Create RagDocument WITH embedding (returns SSE stream)
            return streamPdfToRagWithEmbedding(importResult, filename, collectionId);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
        } catch (Exception e) {
            log.error("PDF-to-RAG import failed for '{}': {}", filename, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ErrorResponse.of("PDF-to-RAG import failed: " + e.getMessage()));
        }
    }

    /**
     * Streams embedding progress via SSE, then sends the final result as "done" event.
     */
    private Object streamPdfToRagWithEmbedding(PdfImportService.PdfImportResult importResult,
                                               String filename,
                                               Long collectionId) {
        SseEmitter emitter = SseEmitters.create();

        // Run embedding in background thread so the HTTP response can stream
        Runnable task = () -> {
            try {
                java.util.concurrent.atomic.AtomicReference<PdfToRagService.PdfToRagResult> ragResultRef =
                        new java.util.concurrent.atomic.AtomicReference<>();
                PdfToRagService.PdfToRagResult ragResult = pdfToRagService.importPdfToRagWithEmbedding(
                        importResult.entryMarkdown(),
                        filename,
                        collectionId,
                        false,  // forceReembed=false
                        (progressEvent) -> {
                            // Forward embedding progress as SSE events
                            PdfToRagService.PdfToRagResult r = ragResultRef.get();
                            SseEmitters.sendProgress(emitter, "progress", progressEvent,
                                    "pdf-to-rag embed docId=" + (r != null ? r.documentId() : "pending"));
                        }
                );
                ragResultRef.set(ragResult);

                PdfToRagResponse doneData = new PdfToRagResponse(
                        ragResult.documentId(),
                        ragResult.title(),
                        ragResult.newlyCreated(),
                        ragResult.embedStatus(),
                        ragResult.embedMessage(),
                        ragResult.chunksCreated(),
                        importResult.uuid(),
                        importResult.entryMarkdown()
                );

                SseEmitters.sendDone(emitter, doneData);

            } catch (Exception e) {
                log.error("PDF-to-RAG SSE embedding failed: {}", e.getMessage());
                SseEmitters.sendError(emitter, e.getMessage(), Map.of(
                        "uuid", importResult.uuid(),
                        "entryMarkdown", importResult.entryMarkdown()
                ));
            }
        };

        // Execute in virtual thread (Spring MVC uses async executor for SseEmitter)
        Thread.ofVirtual().start(task);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    // ==================== Trigger Embedding for Already-Imported PDF ====================

    /**
     * Trigger embedding for an already-imported PDF.
     *
     * <p>This endpoint is for PDFs that were imported via {@code POST /files/pdf}
     * (Markdown already exists in {@code fs_files}), and the user now wants to
     * add it to the RAG knowledge base and/or trigger embedding.
     *
     * <p>Behavior:
     * <ul>
     *   <li>If the Markdown content already has a RagDocument (by content hash), reuses it</li>
     *   <li>Otherwise creates a new RagDocument from the Markdown content</li>
     *   <li>Then triggers embedding (or streams progress when embed=SSE)</li>
     * </ul>
     *
     * <p>API design:
     * <ul>
     *   <li>POST /files/{uuid}/embed?embed=sync  — sync embedding, returns JSON result</li>
     *   <li>POST /files/{uuid}/embed?embed=sse     — SSE stream, progress events + final result</li>
     * </ul>
     *
     * @param uuid         virtual directory UUID of the imported PDF
     * @param collectionId optional collection ID to associate
     * @param embed       "sync" (default) for immediate JSON, "sse" for SSE progress stream
     * @param forceReembed whether to force re-embedding (default: false)
     * @return sync: JSON result | sse: SSE progress stream + final result
     */
    @Operation(summary = "Trigger embedding for an already-imported PDF",
               description = "Finds the Markdown conversion result (from a previous PDF import) in fs_files, "
                           + "creates or reuses the corresponding RagDocument, and triggers embedding. "
                           + "Set embed=sync for immediate JSON response, embed=sse (default) for SSE progress stream.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Embedding triggered successfully (sync mode)"),
            @ApiResponse(responseCode = "200", description = "SSE stream: embedding progress and final result (sse mode)"),
            @ApiResponse(responseCode = "400", description = "UUID not found or Markdown has no content"),
            @ApiResponse(responseCode = "500", description = "Internal error during embedding")
    })
    @PostMapping("/{uuid}/embed")
    public Object triggerEmbedding(
            @Parameter(description = "Virtual directory UUID of the imported PDF")
            @PathVariable("uuid") String uuid,
            @Parameter(description = "Optional collection ID to associate with the RAG document")
            @RequestParam(value = "collectionId", required = false) Long collectionId,
            @Parameter(description = "Embedding mode: 'sse' (default, SSE progress stream) or 'sync' (immediate JSON)")
            @RequestParam(value = "embed", defaultValue = "sse") String embed,
            @Parameter(description = "Force re-embedding even if content unchanged (default: false)")
            @RequestParam(value = "forceReembed", defaultValue = "false") boolean forceReembed) {

        if (uuid == null || uuid.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("UUID must not be blank"));
        }

        if ("sync".equalsIgnoreCase(embed)) {
            // Sync mode: return immediately with result
            try {
                PdfToRagService.PdfToRagResult result = pdfToRagService.triggerEmbedding(
                        uuid, collectionId, forceReembed);

                return ResponseEntity.ok(new PdfToRagResponse(
                        result.documentId(),
                        result.title(),
                        result.newlyCreated(),
                        result.embedStatus(),
                        result.embedMessage(),
                        result.chunksCreated(),
                        uuid,
                        uuid + "/default.md"
                ));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
            } catch (Exception e) {
                log.error("Trigger embedding failed for uuid={}: {}", uuid, e.getMessage());
                return ResponseEntity.internalServerError()
                        .body(ErrorResponse.of("Embedding trigger failed: " + e.getMessage()));
            }
        }

        // SSE mode: stream progress, then final result
        return streamEmbeddingWithProgress(uuid, collectionId, forceReembed);
    }

    /**
     * Streams embedding progress via SSE, then sends the final result as "done" event.
     */
    private Object streamEmbeddingWithProgress(String uuid, Long collectionId, boolean forceReembed) {
        SseEmitter emitter = SseEmitters.create();

        Runnable task = () -> {
            try {
                PdfToRagService.PdfToRagResult result = pdfToRagService.triggerEmbeddingWithProgress(
                        uuid, collectionId, forceReembed,
                        (progressEvent) -> {
                            SseEmitters.sendProgress(emitter, "progress", progressEvent,
                                    "trigger-embed uuid=" + uuid);
                        }
                );

                PdfToRagResponse doneData = new PdfToRagResponse(
                        result.documentId(),
                        result.title(),
                        result.newlyCreated(),
                        result.embedStatus(),
                        result.embedMessage(),
                        result.chunksCreated(),
                        uuid,
                        uuid + "/default.md"
                );
                SseEmitters.sendDone(emitter, doneData);

            } catch (IllegalArgumentException e) {
                SseEmitters.sendError(emitter, e.getMessage(), Map.of("uuid", uuid));
            } catch (Exception e) {
                log.error("Trigger embedding SSE failed for uuid={}: {}", uuid, e.getMessage());
                SseEmitters.sendError(emitter, e.getMessage(), Map.of("uuid", uuid));
            }
        };

        Thread.ofVirtual().start(task);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    // ==================== Preview (Markdown → HTML) ====================

    /**
     * Preview entry Markdown as a standalone HTML page (full page with CSS styling).
     * Uses path parameter so relative image paths in Markdown resolve correctly.
     *
     * URL pattern: GET /files/preview/{uuid}/default.html
     *
     * The Markdown content is rendered to HTML with a <base> tag set to the virtual
     * directory path, ensuring relative image paths resolve correctly without path rewriting.
     */
    @Operation(summary = "Preview entry Markdown as a standalone HTML page",
               description = "Locates the entry Markdown file for the given virtual PDF directory (UUID), "
                           + "renders it as HTML with a <base> tag for correct relative image resolution. "
                           + "Image links remain as relative paths and are resolved via the <base> tag.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "HTML preview rendered"),
            @ApiResponse(responseCode = "404", description = "Entry Markdown file not found")
    })
    @GetMapping(value = "/preview/{uuid}/default.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> previewHtmlPage(
            @Parameter(description = "Virtual directory UUID of the imported PDF")
            @PathVariable("uuid") String uuid) {

        String markdownPath = uuid + "/default.md";
        log.debug("Preview requested: uuid={}, markdownPath={}", uuid, markdownPath);

        Optional<FsFile> markdownFile = pdfImportService.getFile(markdownPath);
        if (markdownFile.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String html = markdownRendererService.renderToHtml(markdownFile.get());

        // Wrap in a minimal HTML page with <base> tag for correct relative image resolution
        String page = wrapInHtmlPageWithBase(uuid, html);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("X-Content-Type-Options", "nosniff")
                .body(page);
    }

    /**
     * Preview entry Markdown for legacy path format (e.g., /files/preview?path=xxx).
     * @deprecated Use GET /files/preview/{uuid}/default.html instead
     */
    @Deprecated
    @Operation(summary = "[Deprecated] Preview using query parameter",
               description = "Legacy endpoint using query parameter. Use /files/preview/{uuid}/default.html instead.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "HTML preview rendered"),
            @ApiResponse(responseCode = "404", description = "Entry Markdown file not found")
    })
    @GetMapping(value = "/preview", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> previewHtmlPageLegacy(
            @Parameter(description = "Virtual path of the imported PDF (URL-encoded)")
            @RequestParam("path") String path) {

        String decodedPath = urlDecode(path);
        String markdownPath = deriveMarkdownPath(decodedPath);
        log.debug("Legacy preview requested: pdfPath={}, markdownPath={}", decodedPath, markdownPath);

        Optional<FsFile> markdownFile = pdfImportService.getFile(markdownPath);
        if (markdownFile.isEmpty()) {
            markdownFile = pdfImportService.getFile(replaceLast(decodedPath, ".pdf", ".md", 1));
        }
        if (markdownFile.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Extract UUID from the path
        String uuid = extractUuid(decodedPath);
        String html = markdownRendererService.renderToHtml(markdownFile.get());
        String page = wrapInHtmlPageWithBase(uuid, html);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("X-Content-Type-Options", "nosniff")
                .body(page);
    }

    /**
     * Return only the rendered HTML fragment (no wrapper page).
     * Used by the WebUI Files page for direct innerHTML rendering (no iframe).
     * Returns fragment with <base> tag for relative image resolution.
     */
    @Operation(summary = "Render Markdown to HTML fragment with <base> tag",
               description = "Renders the Markdown entry file to HTML with a <base> tag. "
                           + "Designed for WebUI fetch + innerHTML rendering.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "HTML fragment with base tag"),
            @ApiResponse(responseCode = "404", description = "Entry Markdown file not found")
    })
    @GetMapping(value = "/preview/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> previewHtmlFragment(
            @Parameter(description = "Virtual path of the imported PDF (URL-encoded)")
            @RequestParam("path") String path) {

        String decodedPath = urlDecode(path);
        String markdownPath = deriveMarkdownPath(decodedPath);
        log.debug("Preview HTML fragment requested: pdfPath={}, markdownPath={}", decodedPath, markdownPath);

        Optional<FsFile> markdownFile = pdfImportService.getFile(markdownPath);
        if (markdownFile.isEmpty()) {
            markdownFile = pdfImportService.getFile(replaceLast(decodedPath, ".pdf", ".md", 1));
        }
        if (markdownFile.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String uuid = extractUuid(decodedPath);
        String html = markdownRendererService.renderToHtml(markdownFile.get());
        // Wrap with minimal structure + base tag for correct image resolution
        String wrapped = "<div>\n" + html + "\n</div>";
        String page = wrapInHtmlPageWithBase(uuid, wrapped);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("X-Content-Type-Options", "nosniff")
                .body(page);
    }

    // ==================== Raw File Download ====================

    /**
     * Download a raw file using path parameters.
     * URL pattern: GET /files/raw/{uuid}/{filename}
     *
     * This is the preferred endpoint for accessing files like images in PDF previews.
     * The base tag in preview HTML pages points to /files/raw/{uuid}/ so relative
     * image paths resolve correctly.
     */
    @Operation(summary = "Download a raw file (path parameter)",
               description = "Serve a raw file from fs_files using path parameters. "
                           + "URL pattern: /files/raw/{uuid}/{filename}. "
                           + "Content-Type is inferred from the file extension / MIME type.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "File content (binary or text)"),
            @ApiResponse(responseCode = "404", description = "File not found")
    })
    @GetMapping("/raw/{uuid}/{filename:.+}")
    public ResponseEntity<Resource> getRawFilePath(
            @Parameter(description = "Virtual directory UUID")
            @PathVariable("uuid") String uuid,
            @Parameter(description = "Filename within the UUID directory")
            @PathVariable("filename") String filename) {

        String path = uuid + "/" + filename;
        log.debug("Raw file requested (path param): uuid={}, filename={}, path={}", uuid, filename, path);

        Optional<FsFile> file = pdfImportService.getFile(path);
        if (file.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FsFile fsFile = file.get();
        Optional<Resource> resource = pdfImportService.loadFileAsResource(path);
        if (resource.isEmpty()) {
            return ResponseEntity.internalServerError().build();
        }

        String contentType = inferContentType(fsFile.getMimeType(), filename);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + filename + "\"")
                .body(resource.get());
    }

    /**
     * Download a raw file using query parameter (legacy).
     * @deprecated Use GET /files/raw/{uuid}/{filename} instead
     */
    @Deprecated
    @Operation(summary = "[Deprecated] Download a raw file (query parameter)",
               description = "Legacy endpoint using query parameter. Use /files/raw/{uuid}/{filename} instead.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "File content (binary or text)"),
            @ApiResponse(responseCode = "404", description = "File not found")
    })
    @GetMapping("/raw")
    public ResponseEntity<Resource> getRawFile(
            @Parameter(description = "Virtual path of the file (URL-encoded)", example = "papers%2F%E8%AE%BA%E6%96%87.md")
            @RequestParam("path") String path) {

        String decodedPath = urlDecode(path);
        log.debug("Raw file requested (query param): path={}", decodedPath);

        Optional<FsFile> file = pdfImportService.getFile(decodedPath);
        if (file.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FsFile fsFile = file.get();
        Optional<Resource> resource = pdfImportService.loadFileAsResource(decodedPath);
        if (resource.isEmpty()) {
            return ResponseEntity.internalServerError().build();
        }

        String filename = decodedPath.substring(decodedPath.lastIndexOf('/') + 1);
        String contentType = inferContentType(fsFile.getMimeType(), filename);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + filename + "\"")
                .body(resource.get());
    }

    // ==================== Directory Tree Listing ====================

    @Operation(summary = "List directory entries",
               description = "List direct children (files and subdirectories) under a virtual path prefix. "
                           + "Returns both files and synthetic directory entries (paths ending in '/'). "
                           + "If path is empty, lists the root-level entries.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Directory listing returned")
    })
    @GetMapping("/tree")
    public ResponseEntity<FileTreeResponse> listTree(
            @Parameter(description = "Virtual path prefix to list (URL-encoded, trailing slash optional). "
                                    + "Omit or use empty string to list root.")
            @RequestParam(value = "path", required = false) String path) {

        String normalized = (path == null || path.isBlank()) ? "" : urlDecode(path);
        normalized = normalized.replace('\\', '/');
        if (!normalized.isEmpty() && !normalized.endsWith("/")) {
            normalized += "/";
        }

        List<FsFile> children = pdfImportService.listChildren(normalized.isEmpty() ? "/" : normalized);

        // Distinguish files from directories: a "directory" entry ends with "/" and has no stored content
        // But since we store flat files, we synthesize directory entries from paths
        List<FileTreeEntryResponse> entries = buildTreeEntries(children, normalized);
        String displayPath = normalized.isEmpty() ? "/" : normalized;

        return ResponseEntity.ok(new FileTreeResponse(displayPath, entries, entries.size()));
    }

    // ==================== Internal Helpers ====================

    private String deriveMarkdownPath(String pdfPath) {
        // pdfPath can be:
        // 1. UUID (virtual dir) -> UUID/default.md
        // 2. UUID/original.pdf -> UUID/default.md
        // 3. papers/论文.pdf -> papers/论文.md (legacy, keep for compatibility)
        if (pdfPath.endsWith(".pdf")) {
            // If it's UUID/original.pdf, convert to UUID/default.md
            if (pdfPath.endsWith("/original.pdf") || pdfPath.endsWith("/original")) {
                int idx = pdfPath.lastIndexOf("/original.pdf");
                if (idx > 0) {
                    return pdfPath.substring(0, idx) + "/default.md";
                }
            }
            // Otherwise just replace .pdf with .md
            return replaceLast(pdfPath, ".pdf", ".md", 1);
        }
        // If path is just UUID (no extension), append /default.md
        if (!pdfPath.contains("/")) {
            return pdfPath + "/default.md";
        }
        return pdfPath + ".md";
    }

    private String urlDecode(String path) {
        try {
            return URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return path;
        }
    }

    /** Shared CSS for Markdown preview pages. */
    private static final String PREVIEW_CSS =
            "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n" +
            "       max-width: 900px; margin: 2rem auto; padding: 0 1rem; color: #333; }\n" +
            "h1,h2,h3 { color: #1a1a1a; margin-top: 1.5em; }\n" +
            "h1 { font-size: 1.8em; border-bottom: 2px solid #eee; padding-bottom: 0.3em; }\n" +
            "h2 { font-size: 1.4em; }\n" +
            "h3 { font-size: 1.1em; }\n" +
            "pre { background: #f6f8fa; border-radius: 6px; padding: 1em; overflow-x: auto; }\n" +
            "code { background: #f0f0f0; border-radius: 3px; padding: 0.1em 0.3em; font-size: 0.9em; }\n" +
            "pre code { background: none; padding: 0; }\n" +
            "blockquote { border-left: 4px solid #dfe2e5; margin: 1em 0; padding: 0.5em 1em; color: #6a737d; }\n" +
            "img { max-width: 100%; height: auto; border-radius: 4px; }\n" +
            "table { border-collapse: collapse; width: 100%; margin: 1em 0; }\n" +
            "th, td { border: 1px solid #dfe2e5; padding: 0.5em 0.8em; text-align: left; }\n" +
            "th { background: #f6f8fa; }\n" +
            "hr { border: none; border-top: 1px solid #dfe2e5; margin: 2em 0; }\n" +
            "ul, ol { padding-left: 1.5em; }\n" +
            "li { margin: 0.3em 0; }\n" +
            "a { color: #0366d6; }";

    private String buildHtmlShell(String lang, String title, String baseTag, String css, String bodyHtml) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"").append(lang).append("\">\n");
        sb.append("<head>\n");
        sb.append("  <meta charset=\"UTF-8\">\n");
        sb.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        if (baseTag != null) sb.append("  ").append(baseTag).append("\n");
        sb.append("  <title>").append(escapeHtml(title)).append("</title>\n");
        sb.append("  <style>\n").append(css).append("\n  </style>\n");
        sb.append("</head>\n");
        sb.append("<body>\n");
        sb.append(bodyHtml).append("\n");
        sb.append("</body>\n");
        sb.append("</html>");
        return sb.toString();
    }

    private String wrapInHtmlPage(String title, String bodyHtml) {
        return buildHtmlShell("zh", title, null, PREVIEW_CSS, bodyHtml);
    }

    /**
     * Wrap HTML content in a full page with &lt;base&gt; tag for correct relative image resolution.
     * Relative image paths in the content will be resolved against /files/raw/{uuid}/.
     */
    private String wrapInHtmlPageWithBase(String uuid, String bodyHtml) {
        String baseTag = "<base href=\"/files/raw/" + uuid + "/\">";
        return buildHtmlShell("zh", "PDF Preview - " + uuid, baseTag, PREVIEW_CSS, bodyHtml);
    }

    /**
     * Extract UUID from a file path like "uuid/original.pdf" or just "uuid".
     */
    private String extractUuid(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        // Remove trailing slashes
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        // If path contains "/", take the first segment
        int slashIdx = path.indexOf('/');
        if (slashIdx > 0) {
            return path.substring(0, slashIdx);
        }
        // Otherwise return the whole path (might be the UUID itself)
        return path;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    /**
     * Replace the last occurrence of a substring.
     *
     * @param s      the string to operate on
     * @param target the substring to find
     * @param replacement the replacement string
     * @param limit  maximum number of replacements (use 1 to replace only the last)
     * @return the resulting string
     */
    private static String replaceLast(String s, String target, String replacement, int limit) {
        if (s == null || target == null || limit <= 0) return s;
        int idx = s.lastIndexOf(target);
        if (idx == -1) return s;
        return s.substring(0, idx) + replacement + s.substring(idx + target.length());
    }

    private String inferContentType(String mimeType, String filename) {
        if (mimeType != null && !mimeType.equals("application/octet-stream")) {
            return mimeType;
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        return "application/octet-stream";
    }

    private List<FileTreeEntryResponse> buildTreeEntries(List<FsFile> files, String parentPath) {
        // Collect synthetic directory entries from file paths
        Set<String> dirs = new TreeSet<>();
        List<FileTreeEntryResponse> entries = new ArrayList<>();

        for (FsFile f : files) {
            // DB paths may have leading whitespace (legacy import bug); trim for safe parsing
            String cleanPath = f.getPath().trim();
            // Compute relative path from parentPath.
            // parentPath may end with "/" (e.g. "uuid/") or not (e.g. "uuid").
            // DB paths don't start with "/", so we need to adjust the offset accordingly.
            // When parentPath ends with "/", parentPath.length() is the start of filename.
            // When parentPath doesn't end with "/", parentPath.length() is also the start.
            String relative;
            if (parentPath.isEmpty()) {
                relative = cleanPath;
            } else {
                int offset = parentPath.endsWith("/")
                        ? parentPath.length()      // skip parentPath including trailing "/"
                        : parentPath.length();     // same - skip parentPath
                relative = cleanPath.substring(offset);
            }
            int slashIdx = relative.indexOf('/');

            if (slashIdx == -1) {
                // Direct file
                entries.add(new FileTreeEntryResponse(
                        relative,
                        cleanPath,
                        "file",
                        f.getMimeType() != null ? f.getMimeType() : "application/octet-stream",
                        f.getFileSize() != null ? f.getFileSize() : 0
                ));
            } else {
                // First segment is a subdirectory
                String dirName = relative.substring(0, slashIdx);
                dirs.add(dirName);
            }
        }

        // Add synthetic directory entries (directories have no MIME type)
        for (String dir : dirs) {
            entries.add(new FileTreeEntryResponse(
                    dir,
                    parentPath + dir + "/",
                    "directory",
                    null,
                    0
            ));
        }

        // Sort: directories first, then files
        entries.sort((a, b) -> {
            if (!a.type().equals(b.type())) {
                return a.type().equals("directory") ? -1 : 1;
            }
            return a.name().compareTo(b.name());
        });

        return entries;
    }
}
