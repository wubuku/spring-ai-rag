package com.springairag.core.service;

import com.springairag.api.dto.DocumentRequest;
import com.springairag.core.entity.FsFile;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.repository.RagDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Service for importing converted Markdown (from PDF) into the RAG knowledge base.
 *
 * <p>This bridges the file system layer ({@code fs_files}) and the RAG knowledge base
 * ({@code rag_documents}). After a PDF is imported via {@link PdfImportService},
 * callers can use this service to create a {@link RagDocument} entry from the
 * converted Markdown content and optionally trigger embedding.
 *
 * <p>Design principle: this service does NOT depend on {@link DocumentEmbedService}
 * to avoid circular dependency risk. Instead, it prepares the {@link RagDocument}
 * entry and callers are responsible for triggering embedding separately, or use
 * {@link #importPdfToRagWithEmbedding(String, String, Long, boolean)} for the
 * combined operation.
 */
@Service
public class PdfToRagService {

    private static final Logger log = LoggerFactory.getLogger(PdfToRagService.class);

    private final FsFileRepository fsFileRepository;
    private final RagDocumentRepository documentRepository;
    private final DocumentEmbedService documentEmbedService;

    public PdfToRagService(FsFileRepository fsFileRepository,
                           RagDocumentRepository documentRepository,
                           DocumentEmbedService documentEmbedService) {
        this.fsFileRepository = fsFileRepository;
        this.documentRepository = documentRepository;
        this.documentEmbedService = documentEmbedService;
    }

    /**
     * Import a PDF conversion result (Markdown from fs_files) into the RAG knowledge base.
     *
     * <p>Fetches the entry Markdown file from {@code fs_files} by its path,
     * creates a {@link RagDocument} entry, and optionally triggers embedding.
     *
     * @param entryMarkdownPath  path to the entry Markdown file in fs_files,
     *                           e.g. "{uuid}/default.md"
     * @param originalFilename   original PDF filename (used as title and source)
     * @param collectionId      optional collection ID to associate with
     * @param embed             whether to trigger embedding after document creation
     * @param forceReembed      whether to force re-embedding (ignored when embed=false)
     * @return import result containing the created document ID and embedding status
     */
    @Transactional
    public PdfToRagResult importPdfToRag(String entryMarkdownPath,
                                          String originalFilename,
                                          Long collectionId,
                                          boolean embed,
                                          boolean forceReembed) {
        log.info("Importing PDF conversion to RAG: entryMarkdownPath={}, originalFilename={}, "
                + "collectionId={}, embed={}, forceReembed={}",
                entryMarkdownPath, originalFilename, collectionId, embed, forceReembed);

        // 1. Fetch Markdown content from fs_files
        Optional<FsFile> markdownFile = fsFileRepository.findById(entryMarkdownPath);
        if (markdownFile.isEmpty()) {
            throw new IllegalArgumentException(
                    "Entry Markdown file not found in fs_files: " + entryMarkdownPath);
        }

        FsFile fsFile = markdownFile.get();
        String content = fsFile.getContentTxt();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(
                    "Entry Markdown file has no text content: " + entryMarkdownPath);
        }

        // 2. Derive title from original filename
        String title = deriveTitle(originalFilename);

        // 3. Compute content hash for deduplication
        String contentHash = computeSha256(content);

        // 4. Check for duplicate content
        var existing = documentRepository.findByContentHash(contentHash);
        RagDocument doc;
        boolean newlyCreated;

        if (!existing.isEmpty()) {
            doc = existing.get(0);
            newlyCreated = false;
            log.info("Duplicate content detected, using existing document id={}", doc.getId());
        } else {
            doc = new RagDocument();
            doc.setTitle(title);
            doc.setContent(content);
            doc.setSource("pdf-import:" + entryMarkdownPath);
            doc.setDocumentType("markdown");
            doc.setOriginalFilename(originalFilename);
            doc.setContentHash(contentHash);
            doc.setCollectionId(collectionId);
            doc.setSize((long) content.getBytes(StandardCharsets.UTF_8).length);
            doc.setMetadata(Map.of(
                    "importedFrom", "pdf",
                    "fsFilesPath", entryMarkdownPath,
                    "uuid", extractUuid(entryMarkdownPath)
            ));
            doc = documentRepository.save(doc);
            newlyCreated = true;
            log.info("RAG document created: id={}", doc.getId());
        }

        // 5. Trigger embedding if requested
        EmbedResult embedResult = null;
        if (embed) {
            embedResult = triggerEmbedding(doc, newlyCreated, forceReembed);
        }

        return new PdfToRagResult(
                doc.getId(),
                doc.getTitle(),
                newlyCreated,
                embedResult != null ? embedResult.status() : null,
                embedResult != null ? embedResult.message() : null,
                embedResult != null ? embedResult.chunksCreated() : null
        );
    }

    /**
     * Import a PDF conversion result with synchronous embedding via SSE progress stream.
     *
     * @param entryMarkdownPath path to the entry Markdown file in fs_files
     * @param originalFilename  original PDF filename
     * @param collectionId      optional collection ID
     * @param forceReembed      whether to force re-embedding
     * @param progressCallback  SSE progress callback (can be null)
     * @return import result
     */
    @Transactional
    public PdfToRagResult importPdfToRagWithEmbedding(String entryMarkdownPath,
                                                       String originalFilename,
                                                       Long collectionId,
                                                       boolean forceReembed,
                                                       java.util.function.Consumer<com.springairag.api.dto.EmbedProgressEvent> progressCallback) {
        log.info("Importing PDF to RAG with SSE embedding: entryMarkdownPath={}, originalFilename={}, "
                + "collectionId={}, forceReembed={}",
                entryMarkdownPath, originalFilename, collectionId, forceReembed);

        // Create RagDocument first
        Optional<FsFile> markdownFile = fsFileRepository.findById(entryMarkdownPath);
        if (markdownFile.isEmpty()) {
            throw new IllegalArgumentException(
                    "Entry Markdown file not found in fs_files: " + entryMarkdownPath);
        }

        FsFile fsFile = markdownFile.get();
        String content = fsFile.getContentTxt();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(
                    "Entry Markdown file has no text content: " + entryMarkdownPath);
        }

        String title = deriveTitle(originalFilename);
        String contentHash = computeSha256(content);

        var existing = documentRepository.findByContentHash(contentHash);
        RagDocument doc;
        boolean newlyCreated;

        if (!existing.isEmpty()) {
            doc = existing.get(0);
            newlyCreated = false;
            log.info("Duplicate content detected, using existing document id={}", doc.getId());
        } else {
            doc = new RagDocument();
            doc.setTitle(title);
            doc.setContent(content);
            doc.setSource("pdf-import:" + entryMarkdownPath);
            doc.setDocumentType("markdown");
            doc.setOriginalFilename(originalFilename);
            doc.setContentHash(contentHash);
            doc.setCollectionId(collectionId);
            doc.setSize((long) content.getBytes(StandardCharsets.UTF_8).length);
            doc.setMetadata(Map.of(
                    "importedFrom", "pdf",
                    "fsFilesPath", entryMarkdownPath,
                    "uuid", extractUuid(entryMarkdownPath)
            ));
            doc = documentRepository.save(doc);
            newlyCreated = true;
            log.info("RAG document created: id={}", doc.getId());
        }

        // Trigger embedding with progress
        Map<String, Object> embedResult = documentEmbedService.embedDocumentWithProgress(
                doc.getId(), forceReembed, progressCallback);

        String status = (String) embedResult.get("status");
        String message = (String) embedResult.getOrDefault("message", "");
        Integer chunks = null;
        if (embedResult.get("chunksCreated") instanceof Number n) {
            chunks = n.intValue();
        }

        return new PdfToRagResult(
                doc.getId(),
                doc.getTitle(),
                newlyCreated,
                status,
                message,
                chunks
        );
    }

    private EmbedResult triggerEmbedding(RagDocument doc, boolean newlyCreated, boolean forceReembed) {
        try {
            // Skip embedding for unchanged cached documents unless force
            if (!newlyCreated && !forceReembed) {
                long existingCount = doc.getEmbeddedContentHash() != null
                        && doc.getContentHash().equals(doc.getEmbeddedContentHash())
                        ? 1 : 0; // Simplified check
                if ("COMPLETED".equals(doc.getProcessingStatus()) && existingCount > 0) {
                    return new EmbedResult("CACHED",
                            "Document already has embeddings, skipping (use forceReembed=true to re-embed)",
                            null);
                }
            }

            Map<String, Object> result = documentEmbedService.embedDocument(doc.getId(), forceReembed);
            String status = (String) result.get("status");
            String message = (String) result.getOrDefault("message", "");
            Integer chunks = null;
            if (result.get("chunksCreated") instanceof Number n) {
                chunks = n.intValue();
            }
            return new EmbedResult(status, message, chunks);
        } catch (Exception e) {
            log.error("Embedding failed for document {}: {}", doc.getId(), e.getMessage());
            return new EmbedResult("FAILED", e.getMessage(), null);
        }
    }

    private String deriveTitle(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "Untitled";
        }
        // Remove .pdf extension
        String name = originalFilename;
        if (name.toLowerCase().endsWith(".pdf")) {
            name = name.substring(0, name.length() - 4);
        }
        // Truncate if too long
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        return name;
    }

    private String extractUuid(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        // Path format: {uuid}/default.md
        int slashIdx = path.indexOf('/');
        if (slashIdx > 0) {
            return path.substring(0, slashIdx);
        }
        return path;
    }

    private static String computeSha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ==================== Result Records ====================

    public record PdfToRagResult(
            /** Created (or existing) RAG document ID */
            Long documentId,
            /** Document title */
            String title,
            /** Whether this was a newly created document (vs. existing duplicate) */
            boolean newlyCreated,
            /** Embedding status: COMPLETED, CACHED, FAILED, null if embed=false */
            String embedStatus,
            /** Embedding result message */
            String embedMessage,
            /** Number of chunks created (null if embed=false or on failure) */
            Integer chunksCreated
    ) {}

    private record EmbedResult(String status, String message, Integer chunksCreated) {}
}
