package com.springairag.core.service;

import com.springairag.core.config.RagPdfProperties;
import com.springairag.core.entity.FsFile;
import com.springairag.core.repository.FsFileRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * PDF Import Service
 *
 * <p>Handles PDF file import into the {@code fs_files} table using Apache PDFBox
 * for text extraction. No external CLI dependencies required.
 *
 * <p>Import pipeline:
 * <ol>
 *   <li>Generate a UUID as the virtual directory name</li>
 *   <li>Extract text from PDF using PDFBox</li>
 *   <li>Create a Markdown file from the extracted text</li>
 *   <li>Store the original PDF and Markdown as FsFile records</li>
 *   <li>Return metadata about the imported files</li>
 * </ol>
 *
 * <p>File storage layout (path = virtual directory + filename):
 * <ul>
 *   <li>{@code {uuid}/default.md} — entry Markdown file</li>
 *   <li>{@code {uuid}/original.pdf} — original PDF binary</li>
 * </ul>
 *
 * <p>Preview URL: {@code GET /files/preview?path={uuid}/original.pdf}
 * Preview logic: replaces {@code original.pdf} with {@code default.md} to locate entry Markdown.
 */
@Service
public class PdfImportService {

    private static final Logger log = LoggerFactory.getLogger(PdfImportService.class);

    private final FsFileRepository fsFileRepository;
    private final RagPdfProperties pdfProperties;

    public PdfImportService(FsFileRepository fsFileRepository, RagPdfProperties pdfProperties) {
        this.fsFileRepository = fsFileRepository;
        this.pdfProperties = pdfProperties;
    }

    // ==================== Public API ====================

    /**
     * Import a PDF file: extract text and store both PDF and Markdown entry in fs_files.
     *
     * @param pdfFile     the uploaded PDF file (multipart)
     * @param collection  Optional collection/subdirectory path prefix (ignored for now;
     *                    the UUID serves as the virtual directory)
     * @return import result containing the virtual directory UUID and file count
     */
    @Transactional
    public PdfImportResult importPdf(MultipartFile pdfFile, String collection) {
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

        // Read PDF bytes
        byte[] pdfBytes;
        try {
            pdfBytes = pdfFile.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read PDF bytes: " + e.getMessage(), e);
        }

        // Extract text using PDFBox
        String extractedText = extractTextFromPdf(pdfBytes, originalFilename);

        // Build Markdown content
        String markdown = buildMarkdown(originalFilename, extractedText);

        // Store files
        List<FsFile> records = new ArrayList<>();
        int[] count = {0};

        // Entry Markdown: {uuid}/default.md
        FsFile mdFile = new FsFile(
                uuid + "/default.md",
                true,
                markdown.getBytes(StandardCharsets.UTF_8),
                markdown,
                "text/markdown",
                (long) markdown.length()
        );
        records.add(mdFile);
        count[0]++;

        // Original PDF: {uuid}/original.pdf
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

        fsFileRepository.saveAll(records);

        // The entry Markdown path for preview
        String entryMarkdownPath = uuid + "/default.md";

        log.info("PDF import completed: uuid={}, entryMarkdown={}, files={}",
                uuid, entryMarkdownPath, count[0]);

        return new PdfImportResult(uuid, entryMarkdownPath, count[0]);
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
        String normalizedRaw = virtualPath.replace('\\', '/');
        final String normalized = normalizedRaw.endsWith("/") ? normalizedRaw : normalizedRaw + "/";
        return fsFileRepository.findByPathStartingWithOrderByPathAsc(normalized)
                .stream()
                .filter(f -> {
                    String remainder = f.getPath().substring(normalized.length());
                    return remainder.indexOf('/') == -1;
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
                        Path tempFile = Files.createTempFile("fsfile-", "-" + path.substring(path.lastIndexOf('/') + 1));
                        tempFile.toFile().deleteOnExit();
                        Files.write(tempFile, file.getContentBin());
                        return new UrlResource(tempFile.toUri());
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to create temp file for: " + path, e);
                    }
                });
    }

    // ==================== Internal ====================

    /**
     * Extract text from a PDF using Apache PDFBox.
     *
     * <p>Note: PDFBox text extraction does not preserve layout. For full layout
     * preservation (headings, tables, images), consider using a dedicated OCR + layout
     * analysis tool such as marker-pdf (marker_single CLI) when GPU is available.
     *
     * @param pdfBytes        raw PDF bytes
     * @param originalFilename for error messages
     * @return extracted plain text, never null
     */
    String extractTextFromPdf(byte[] pdfBytes, String originalFilename) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Sort by reading order for better text coherence
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            return text != null ? text : "";
        } catch (IOException e) {
            log.error("PDFBox text extraction failed for '{}': {}", originalFilename, e.getMessage());
            throw new RuntimeException("Failed to extract text from PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Build Markdown content from extracted PDF text.
     *
     * <p>Creates a simple Markdown document with the original filename as title
     * and the extracted text as body content.
     */
    String buildMarkdown(String originalFilename, String extractedText) {
        // Derive a clean title from the filename
        String title = originalFilename;
        if (title.toLowerCase().endsWith(".pdf")) {
            title = title.substring(0, title.length() - 4);
        }
        // Replace underscores/hyphens with spaces in title
        title = title.replace('[', ' ').replace(']', ' ')
                     .replace('_', ' ').replace('-', ' ')
                     .replaceAll(" +", " ").trim();

        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");
        md.append("*Extracted from PDF automatically. Layout may not be preserved.*\n\n");
        md.append("## Content\n\n");

        if (extractedText == null || extractedText.isBlank()) {
            md.append("*No text content could be extracted from this PDF.*\n");
        } else {
            // Split by double newlines to form paragraphs
            String[] paragraphs = extractedText.split("\n\\s*\n");
            for (String para : paragraphs) {
                String trimmed = para.trim();
                if (trimmed.isEmpty()) continue;
                // Avoid adding very short lines as separate paragraphs
                if (trimmed.contains("\n") || trimmed.length() > 80) {
                    // Multi-line block — preserve line breaks
                    md.append(trimmed.replaceAll("\\n", "  \n")).append("\n\n");
                } else {
                    md.append(trimmed).append("\n\n");
                }
            }
        }

        md.append("\n---\n");
        md.append("*Generated by spring-ai-rag PDF Import · ").append(new java.util.Date()).append("*\n");

        return md.toString();
    }

    // ==================== Result Record ====================

    public record PdfImportResult(
            /** Virtual directory UUID */
            String uuid,
            /** Path to the entry Markdown file, e.g. "{uuid}/default.md" */
            String entryMarkdown,
            /** Total number of files stored (PDF + Markdown) */
            int filesStored
    ) {}
}
