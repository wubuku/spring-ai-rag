package com.springairag.core.service;

import com.springairag.core.config.RagPdfProperties;
import com.springairag.core.entity.FsFile;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.service.pdf.PdfConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * PDF Import Service
 *
 * <p>Handles PDF file import into the {@code fs_files} table using marker CLI
 * for high-quality text extraction with layout preservation and image extraction.
 *
 * <p>Import pipeline:
 * <ol>
 *   <li>Generate a UUID as the virtual directory name</li>
 *   <li>Copy uploaded PDF to temp location</li>
 *   <li>Run marker CLI to convert PDF to Markdown + images in temp directory</li>
 *   <li>Import entire directory tree (md + images) into fs_files table</li>
 *   <li>Copy original PDF to {uuid}/original.pdf</li>
 *   <li>Return metadata about the imported files</li>
 * </ol>
 *
 * <p>File storage layout (path = virtual directory + filename):
 * <ul>
 *   <li>{@code {uuid}/default.md} — entry Markdown file</li>
 *   <li>{@code {uuid}/original.pdf} — original PDF binary</li>
 *   <li>{@code {uuid}/{image_0}.png} — extracted images (if any)</li>
 *   <li>{@code {uuid}/{other_files} — any other output from marker</li>
 * </ul>
 *
 * <p>Preview URL: {@code GET /preview/{uuid}/default.html}
 * <p>Image URL: {@code GET /preview/{uuid}/{imageName}}
 */
@Service
public class PdfImportService {

    private static final Logger log = LoggerFactory.getLogger(PdfImportService.class);

    private final FsFileRepository fsFileRepository;
    private final RagPdfProperties pdfProperties;
    private final List<PdfConverter> converters;

    public PdfImportService(FsFileRepository fsFileRepository,
                            RagPdfProperties pdfProperties,
                            List<PdfConverter> converters) {
        this.fsFileRepository = fsFileRepository;
        this.pdfProperties = pdfProperties;
        // Sort by priority: marker CLI first, then PDFBox
        this.converters = converters.stream()
                .sorted(Comparator.comparing(PdfConverter::getName))
                .toList();
    }

    // ==================== Public API ====================

    /**
     * Import a PDF file: convert with marker CLI and store all output in fs_files.
     *
     * @param pdfFile     the uploaded PDF file (multipart)
     * @param collection  Optional collection/subdirectory path prefix (ignored;
     *                    the UUID serves as the virtual directory)
     * @return import result containing the virtual directory UUID and file count
     */
    @Transactional
    public PdfImportResult importPdf(MultipartFile pdfFile, String collection) throws IOException {
        if (!pdfProperties.isEnabled()) {
            throw new IllegalStateException("PDF import is disabled (rag.pdf.enabled=false)");
        }

        String originalFilename = pdfFile.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("PDF filename must not be blank");
        }
        if (!originalFilename.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF files are supported");
        }

        // Generate UUID as virtual directory name
        String uuid = UUID.randomUUID().toString();
        log.info("Importing PDF: originalFilename={}, uuid={}", originalFilename, uuid);

        // Create temp working directory
        Path tempWorkDir;
        try {
            tempWorkDir = Files.createTempDirectory("pdf-import-");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp working directory: " + e.getMessage(), e);
        }

        try {
            // Copy uploaded PDF to temp location
            Path tempPdfPath = tempWorkDir.resolve(originalFilename);
            Files.write(tempPdfPath, pdfFile.getBytes());

            // Find first available converter
            PdfConverter converter = findAvailableConverter();
            if (converter == null) {
                throw new RuntimeException("No PDF converter is available. " +
                        "Please ensure marker CLI is installed or PDFBox is on the classpath.");
            }

            log.info("Using converter: {}", converter.getName());

            // Convert PDF to Markdown + images
            boolean success = converter.convert(tempPdfPath, tempWorkDir);
            if (!success) {
                throw new RuntimeException("PDF conversion failed using " + converter.getName());
            }

            // marker output directory structure: {tempWorkDir}/{pdfName}/
            String pdfBaseName = originalFilename;
            if (pdfBaseName.toLowerCase().endsWith(".pdf")) {
                pdfBaseName = pdfBaseName.substring(0, pdfBaseName.length() - 4);
            }
            Path markerOutputDir = tempWorkDir.resolve(pdfBaseName);

            // Import entire directory tree into database
            List<FsFile> records = new ArrayList<>();
            int[] count = {0};

            // Store original PDF
            byte[] pdfBytes = Files.readAllBytes(tempPdfPath);
            FsFile pdfFileRecord = new FsFile(
                    uuid + "/original.pdf",
                    false,
                    pdfBytes,
                    null,
                    "application/pdf",
                    (long) pdfBytes.length
            );
            records.add(pdfFileRecord);
            count[0]++;

            // Iterate through marker output directory, import all files
            if (Files.exists(markerOutputDir)) {
                try {
                    Files.list(markerOutputDir).forEach(file -> {
                        try {
                            if (Files.isRegularFile(file)) {
                                // Strip leading/trailing whitespace from filename to handle edge cases
                                // where the converter may create files with extra spaces
                                String filename = file.getFileName().toString().trim();
                                byte[] content = Files.readAllBytes(file);

                                // Markdown files become the entry (default.md)
                                boolean isMarkdown = filename.toLowerCase().endsWith(".md");
                                String recordPath = isMarkdown
                                        ? uuid + "/default.md"  // Rename to default.md
                                        : uuid + "/" + filename;

                                String contentTxt = isMarkdown
                                        ? new String(content, StandardCharsets.UTF_8)
                                        : null;

                                String mimeType = isMarkdown
                                        ? "text/markdown"
                                        : Files.probeContentType(file);

                                FsFile fsFile = new FsFile(
                                        recordPath,
                                        isMarkdown,
                                        content,
                                        contentTxt,
                                        mimeType != null ? mimeType : "application/octet-stream",
                                        (long) content.length
                                );
                                records.add(fsFile);
                                count[0]++;

                                log.info("Imported file: {} -> {}", file, recordPath);
                            }
                        } catch (IOException e) {
                            log.error("Failed to import file {}: {}", file, e.getMessage());
                        }
                    });
                } catch (IOException e) {
                    log.error("Failed to list marker output directory: {}", e.getMessage());
                }
            }

            fsFileRepository.saveAll(records);

            // The entry Markdown path for preview
            String entryMarkdownPath = uuid + "/default.md";

            log.info("PDF import completed: uuid={}, entryMarkdown={}, files={}",
                    uuid, entryMarkdownPath, count[0]);

            return new PdfImportResult(uuid, entryMarkdownPath, count[0]);

        } finally {
            // Cleanup temp directory
            try {
                Files.walk(tempWorkDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                log.warn("Failed to delete temp file: {}", p);
                            }
                        });
            } catch (IOException e) {
                log.warn("Failed to cleanup temp directory: {}", tempWorkDir);
            }
        }
    }

    /**
     * Find the first available PDF converter.
     */
    private PdfConverter findAvailableConverter() {
        for (PdfConverter converter : converters) {
            if (converter.isAvailable()) {
                log.info("Found available converter: {}", converter.getName());
                return converter;
            }
        }
        log.warn("No PDF converter is available");
        return null;
    }

    /**
     * Get a file from fs_files by its path.
     */
    public Optional<FsFile> getFile(String path) {
        return fsFileRepository.findById(path);
    }

    /**
     * List direct children (files) under a virtual path prefix.
     */
    public List<FsFile> listChildren(String virtualPath) {
        String normalizedRaw = (virtualPath == null || virtualPath.isBlank()) ? "/" : virtualPath.replace('\\', '/');

        // Root "/" or empty — DB paths lack leading "/", so findAll() and let
        // buildTreeEntries synthesize directory entries from file paths
        if (normalizedRaw.equals("/") || normalizedRaw.isEmpty()) {
            return fsFileRepository.findAll();
        }

        // Strip trailing "/" then any whitespace; also strip leading "/" from the
        // normalized path to build a clean DB query prefix (handles legacy paths)
        final String normalized = normalizedRaw.endsWith("/")
                ? normalizedRaw.substring(0, normalizedRaw.length() - 1).trim()
                : normalizedRaw.trim();

        // Defensive: query prefix strips leading "/" to match DB paths (e.g.
        // DB has " e2242d5d..." with a leading space, so we strip "/" to match)
        final String queryPrefix = normalized.startsWith("/")
                ? normalized.substring(1).trim()
                : normalized;

        return fsFileRepository.findByPathStartingWithOrderByPathAsc(queryPrefix)
                .stream()
                .filter(f -> {
                    // Handle leading whitespace in DB paths (legacy data)
                    String cleanPath = f.getPath().trim();
                    String remainder = cleanPath.substring(normalized.length());
                    // A direct child has no "/" in remainder (e.g. "default.md")
                    // or remainder starts with "/" and has no more "/" (e.g. "/default.md")
                    // The latter case is a direct file in the directory (e.g. "uuid/default.md")
                    if (remainder.indexOf('/') == -1) {
                        return true;
                    }
                    // If remainder starts with "/" (direct child), check if it's truly direct
                    // e.g. "/subdir/file.md" has more "/" so it's not direct
                    // but "/default.md" has no more "/" so it IS direct
                    if (remainder.startsWith("/")) {
                        return remainder.indexOf('/', 1) == -1;
                    }
                    return false;
                })
                .toList();
    }

    /**
     * Load a file as a Spring Resource for serving via controller.
     */
    public Optional<Resource> loadFileAsResource(String path) {
        return fsFileRepository.findById(path)
                .map(file -> {
                    try {
                        String filename = path.substring(path.lastIndexOf('/') + 1);
                        if (filename.isEmpty()) {
                            filename = "file";
                        }
                        Path tempFile = Files.createTempFile("fsfile-", "-" + filename);
                        tempFile.toFile().deleteOnExit();
                        Files.write(tempFile, file.getContentBin());
                        return new UrlResource(tempFile.toUri());
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to create temp file for: " + path, e);
                    }
                });
    }

    // ==================== Result Record ====================

    public record PdfImportResult(
            /** Virtual directory UUID */
            String uuid,
            /** Path to the entry Markdown file, e.g. "{uuid}/default.md" */
            String entryMarkdown,
            /** Total number of files stored */
            int filesStored
    ) {}
}