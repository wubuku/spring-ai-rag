package com.springairag.core.controller;

import java.util.HashMap;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.PdfImportResponse;
import com.springairag.core.entity.FsFile;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.service.MarkdownRendererService;
import com.springairag.core.service.PdfImportService;
import com.springairag.core.versioning.ApiVersion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
import org.springframework.web.servlet.resource.HttpResource;

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
@ConditionalOnBean(FsFileRepository.class)
public class PdfImportController {

    private static final Logger log = LoggerFactory.getLogger(PdfImportController.class);

    private final PdfImportService pdfImportService;
    private final MarkdownRendererService markdownRendererService;

    public PdfImportController(PdfImportService pdfImportService,
                               MarkdownRendererService markdownRendererService) {
        this.pdfImportService = pdfImportService;
        this.markdownRendererService = markdownRendererService;
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

            log.info("PDF imported: virtualRoot={}, entryMarkdown={}, files={}",
                    result.virtualRoot(), result.entryMarkdown(), result.filesImported());

            return ResponseEntity.ok(new PdfImportResponse(
                    result.virtualRoot(),
                    result.entryMarkdown(),
                    result.filesImported()
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
        } catch (Exception e) {
            log.error("PDF import failed for '{}': {}", filename, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ErrorResponse.of("PDF import failed: " + e.getMessage()));
        }
    }

    // ==================== Preview (Markdown → HTML) ====================

    /**
     * Return only the rendered HTML fragment (no wrapper page).
     * Used by the WebUI Files page for direct innerHTML rendering (no iframe).
     */
    @Operation(summary = "Render Markdown to HTML fragment (no wrapper page)",
               description = "Renders the Markdown entry file to HTML and returns only the HTML fragment "
                           + "(no &lt;html&gt;, &lt;head&gt;, or &lt;body&gt; tags). "
                           + "Image links are rewritten to /files/raw. "
                           + "Designed for WebUI fetch + innerHTML rendering.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "HTML fragment"),
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

        String html = markdownRendererService.renderToHtml(markdownFile.get());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("X-Content-Type-Options", "nosniff")
                .body(html);
    }

    /**
     * Preview entry Markdown as a standalone HTML page (full page with CSS styling).
     * Intended for direct browser navigation or embedding in an iframe.
     */
    @Operation(summary = "Preview entry Markdown as a standalone HTML page",
               description = "Locates the entry Markdown file for the given virtual PDF path, "
                           + "renders it as HTML, and rewrites image links to point to /files/raw. "
                           + "The Markdown entry path is derived by replacing the .pdf extension with .md.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "HTML preview rendered"),
            @ApiResponse(responseCode = "404", description = "Entry Markdown file not found")
    })
    @GetMapping(value = "/preview", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> previewHtmlPage(
            @Parameter(description = "Virtual path of the imported PDF (URL-encoded)", example = "papers%2F%E8%AE%BA%E6%96%87.pdf")
            @RequestParam("path") String path) {

        String decodedPath = urlDecode(path);

        // Derive entry Markdown path: replace .pdf with .md
        String markdownPath = deriveMarkdownPath(decodedPath);
        log.debug("Preview requested: pdfPath={}, markdownPath={}", decodedPath, markdownPath);

        Optional<FsFile> markdownFile = pdfImportService.getFile(markdownPath);
        if (markdownFile.isEmpty()) {
            // Fallback: try the pdf's parent dir + baseName.md
            markdownFile = pdfImportService.getFile(
                    replaceLast(decodedPath, ".pdf", ".md", 1));
        }
        if (markdownFile.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String html = markdownRendererService.renderToHtml(markdownFile.get());

        // Wrap in a minimal HTML page
        String page = wrapInHtmlPage(decodedPath, html);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("X-Content-Type-Options", "nosniff")
                .body(page);
    }

    // ==================== Raw File Download ====================

    @Operation(summary = "Download a raw file",
               description = "Serve a raw file from fs_files by its virtual path. "
                           + "Content-Type is inferred from the file extension / MIME type. "
                           + "Images are typically served via this endpoint (rewritten in HTML previews).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "File content (binary or text)"),
            @ApiResponse(responseCode = "404", description = "File not found")
    })
    @GetMapping("/raw")
    public ResponseEntity<Resource> getRawFile(
            @Parameter(description = "Virtual path of the file (URL-encoded)", example = "papers%2F%E8%AE%BA%E6%96%87.md")
            @RequestParam("path") String path) {

        String decodedPath = urlDecode(path);
        log.debug("Raw file requested: path={}", decodedPath);

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
    public ResponseEntity<Map<String, Object>> listTree(
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
        List<Map<String, Object>> entries = buildTreeEntries(children, normalized);

        return ResponseEntity.ok(Map.of(
                "path", normalized.isEmpty() ? "/" : normalized,
                "entries", entries,
                "total", entries.size()
        ));
    }

    // ==================== Internal Helpers ====================

    private String deriveMarkdownPath(String pdfPath) {
        // pdfPath example: "papers/论文.pdf" → "papers/论文.md"
        return replaceLast(pdfPath.toLowerCase(), ".pdf", ".md", 1);
    }

    private String urlDecode(String path) {
        try {
            return URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return path;
        }
    }

    private String wrapInHtmlPage(String title, String bodyHtml) {
        return "<!DOCTYPE html>\n" +
               "<html lang=\"zh\">\n" +
               "<head>\n" +
               "  <meta charset=\"UTF-8\">\n" +
               "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
               "  <title>" + escapeHtml(title) + "</title>\n" +
               "  <style>\n" +
               "    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n" +
               "           max-width: 900px; margin: 2rem auto; padding: 0 1rem; color: #333; }\n" +
               "    h1,h2,h3 { color: #1a1a1a; margin-top: 1.5em; }\n" +
               "    h1 { font-size: 1.8em; border-bottom: 2px solid #eee; padding-bottom: 0.3em; }\n" +
               "    h2 { font-size: 1.4em; }\n" +
               "    h3 { font-size: 1.1em; }\n" +
               "    pre { background: #f6f8fa; border-radius: 6px; padding: 1em; overflow-x: auto; }\n" +
               "    code { background: #f0f0f0; border-radius: 3px; padding: 0.1em 0.3em; font-size: 0.9em; }\n" +
               "    pre code { background: none; padding: 0; }\n" +
               "    blockquote { border-left: 4px solid #dfe2e5; margin: 1em 0; padding: 0.5em 1em; color: #6a737d; }\n" +
               "    img { max-width: 100%; height: auto; border-radius: 4px; }\n" +
               "    table { border-collapse: collapse; width: 100%; margin: 1em 0; }\n" +
               "    th, td { border: 1px solid #dfe2e5; padding: 0.5em 0.8em; text-align: left; }\n" +
               "    th { background: #f6f8fa; }\n" +
               "    hr { border: none; border-top: 1px solid #dfe2e5; margin: 2em 0; }\n" +
               "    ul, ol { padding-left: 1.5em; }\n" +
               "    li { margin: 0.3em 0; }\n" +
               "    a { color: #0366d6; }\n" +
               "  </style>\n" +
               "</head>\n" +
               "<body>\n" +
               bodyHtml + "\n" +
               "</body>\n" +
               "</html>";
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replaceAll("&", "&amp;")
                   .replaceAll("<", "&lt;")
                   .replaceAll(">", "&gt;")
                   .replaceAll("\"", "&quot;");
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

    private List<Map<String, Object>> buildTreeEntries(List<FsFile> files, String parentPath) {
        // Collect synthetic directory entries from file paths
        Set<String> dirs = new TreeSet<>();
        List<Map<String, Object>> entries = new ArrayList<>();

        for (FsFile f : files) {
            String relative = f.getPath().substring(parentPath.length());
            int slashIdx = relative.indexOf('/');

            if (slashIdx == -1) {
                // Direct file
                entries.add(Map.of(
                        "name", relative,
                        "path", f.getPath(),
                        "type", "file",
                        "mimeType", f.getMimeType() != null ? f.getMimeType() : "application/octet-stream",
                        "size", f.getFileSize() != null ? f.getFileSize() : 0
                ));
            } else {
                // First segment is a subdirectory
                String dirName = relative.substring(0, slashIdx);
                dirs.add(dirName);
            }
        }

        // Add synthetic directory entries
        for (String dir : dirs) {
            Map<String, Object> dirEntry = new HashMap<>();
            dirEntry.put("name", dir);
            dirEntry.put("path", parentPath + dir + "/");
            dirEntry.put("type", "directory");
            dirEntry.put("mimeType", null); // directories have no MIME type
            dirEntry.put("size", 0);
            entries.add(dirEntry);
        }

        // Sort: directories first, then files
        entries.sort((a, b) -> {
            String typeA = (String) a.get("type");
            String typeB = (String) b.get("type");
            if (!typeA.equals(typeB)) {
                return typeA.equals("directory") ? -1 : 1;
            }
            return ((String) a.get("name")).compareTo((String) b.get("name"));
        });

        return entries;
    }
}
