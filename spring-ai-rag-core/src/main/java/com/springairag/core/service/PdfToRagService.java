package com.springairag.core.service;

import com.springairag.api.dto.EmbedProgressEvent;
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
import java.util.function.Consumer;

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
 * {@link #importPdfToRagWithEmbedding(String, String, Long, boolean, Consumer)} for the
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

    // ---- Public API ----

    /**
     * Import a PDF conversion result (Markdown from fs_files) into the RAG knowledge base.
     *
     * @param entryMarkdownPath path to the entry Markdown file in fs_files,
     *                          e.g. "{uuid}/default.md"
     * @param originalFilename  original PDF filename (used as title and source)
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

        String title = deriveTitle(originalFilename);
        DocumentBuildResult result = buildDocumentFromMarkdown(entryMarkdownPath, title, originalFilename, collectionId);

        EmbedResult embedResult = null;
        if (embed) {
            embedResult = doEmbed(result.doc(), result.newlyCreated(), forceReembed);
        }

        return toPdfToRagResult(result, embedResult);
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
                                                       Consumer<EmbedProgressEvent> progressCallback) {
        log.info("Importing PDF to RAG with SSE embedding: entryMarkdownPath={}, originalFilename={}, "
                + "collectionId={}, forceReembed={}",
                entryMarkdownPath, originalFilename, collectionId, forceReembed);

        String title = deriveTitle(originalFilename);
        DocumentBuildResult result = buildDocumentFromMarkdown(entryMarkdownPath, title, originalFilename, collectionId);

        Map<String, Object> embedResult = documentEmbedService.embedDocumentWithProgress(
                result.doc().getId(), forceReembed, progressCallback);

        String status = (String) embedResult.get("status");
        String message = (String) embedResult.getOrDefault("message", "");
        Integer chunks = embedResult.get("chunksCreated") instanceof Number n ? n.intValue() : null;

        return new PdfToRagResult(
                result.doc().getId(), result.doc().getTitle(), result.newlyCreated(),
                status, message, chunks);
    }

    /**
     * Trigger embedding for an already-imported PDF (Markdown already exists in fs_files).
     *
     * <p>Looks up the entry Markdown file ({uuid}/default.md) in fs_files,
     * creates or finds the corresponding RagDocument, and triggers embedding.
     *
     * @param uuid         virtual directory UUID of the imported PDF
     * @param collectionId optional collection ID to associate with the document
     * @param forceReembed whether to force re-embedding (skip cache)
     * @return embedding result (documentId, status, chunksCreated)
     */
    public PdfToRagResult triggerEmbedding(String uuid, Long collectionId, boolean forceReembed) {
        String entryPath = uuid + "/default.md";
        log.info("Triggering embedding for imported PDF: uuid={}, collectionId={}, forceReembed={}",
                uuid, collectionId, forceReembed);

        String title = deriveTitleFromMetadata(entryPath, uuid);
        DocumentBuildResult result = buildDocumentFromMarkdown(entryPath, title, null, collectionId);

        EmbedResult embedResult = doEmbed(result.doc(), result.newlyCreated(), forceReembed);

        return toPdfToRagResult(result, embedResult);
    }

    /**
     * Trigger embedding for an already-imported PDF with SSE progress stream.
     *
     * @param uuid             virtual directory UUID
     * @param collectionId     optional collection ID
     * @param forceReembed     whether to force re-embedding
     * @param progressCallback SSE progress callback
     * @return embedding result
     */
    public PdfToRagResult triggerEmbeddingWithProgress(String uuid, Long collectionId,
                                                        boolean forceReembed,
                                                        Consumer<EmbedProgressEvent> progressCallback) {
        String entryPath = uuid + "/default.md";
        log.info("Triggering embedding with SSE for imported PDF: uuid={}", uuid);

        String title = deriveTitleFromMetadata(entryPath, uuid);
        DocumentBuildResult result = buildDocumentFromMarkdown(entryPath, title, null, collectionId);

        Map<String, Object> embedResult = documentEmbedService.embedDocumentWithProgress(
                result.doc().getId(), forceReembed, progressCallback);

        String status = (String) embedResult.get("status");
        String message = (String) embedResult.getOrDefault("message", "");
        Integer chunks = embedResult.get("chunksCreated") instanceof Number n ? n.intValue() : null;

        return new PdfToRagResult(
                result.doc().getId(), result.doc().getTitle(), result.newlyCreated(),
                status, message, chunks);
    }

    // ---- Document building ----

    /**
     * Fetch the Markdown file from fs_files, deduplicate by content hash,
     * and create or reuse a RagDocument entry.
     *
     * @param markdownPath      path in fs_files (e.g. "uuid/default.md")
     * @param title             document title
     * @param originalFilename  original PDF filename (null for triggerEmbedding path)
     * @param collectionId      optional collection ID
     */
    private DocumentBuildResult buildDocumentFromMarkdown(String markdownPath,
                                                          String title,
                                                          String originalFilename,
                                                          Long collectionId) {
        FsFile fsFile = fsFileRepository.findById(markdownPath)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Entry Markdown file not found in fs_files: " + markdownPath));

        String content = fsFile.getContentTxt();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(
                    "Entry Markdown file has no text content: " + markdownPath);
        }

        String contentHash = computeSha256(content);
        String uuid = extractUuid(markdownPath);

        var existing = documentRepository.findByContentHash(contentHash);
        if (!existing.isEmpty()) {
            RagDocument doc = existing.get(0);
            log.info("Duplicate content detected, using existing document id={}", doc.getId());
            boolean updated = false;
            if (collectionId != null && !collectionId.equals(doc.getCollectionId())) {
                doc.setCollectionId(collectionId);
                doc = documentRepository.save(doc);
                updated = true;
            }
            return new DocumentBuildResult(doc, false, updated, uuid);
        }

        RagDocument doc = new RagDocument();
        doc.setTitle(title);
        doc.setContent(content);
        doc.setSource("pdf-import:" + markdownPath);
        doc.setDocumentType("markdown");
        doc.setOriginalFilename(originalFilename != null ? originalFilename : fsFile.getPath());
        doc.setContentHash(contentHash);
        doc.setCollectionId(collectionId);
        doc.setSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        doc.setMetadata(Map.of(
                "importedFrom", "pdf",
                "fsFilesPath", markdownPath,
                "uuid", uuid));
        doc = documentRepository.save(doc);
        log.info("RAG document created: id={}", doc.getId());
        return new DocumentBuildResult(doc, true, false, uuid);
    }

    // ---- Embedding ----

    private EmbedResult doEmbed(RagDocument doc, boolean newlyCreated, boolean forceReembed) {
        try {
            if (!newlyCreated && !forceReembed) {
                boolean isCached = doc.getEmbeddedContentHash() != null
                        && doc.getContentHash().equals(doc.getEmbeddedContentHash());
                if ("COMPLETED".equals(doc.getProcessingStatus()) && isCached) {
                    return new EmbedResult("CACHED",
                            "Document already has embeddings, skipping (use forceReembed=true to re-embed)",
                            null);
                }
            }
            Map<String, Object> result = documentEmbedService.embedDocument(doc.getId(), forceReembed);
            String status = (String) result.get("status");
            String message = (String) result.getOrDefault("message", "");
            Integer chunks = result.get("chunksCreated") instanceof Number n ? n.intValue() : null;
            return new EmbedResult(status, message, chunks);
        } catch (Exception e) { // Resilience: embedding failure should not abort the overall import process
            log.error("Embedding failed for document {}: {}", doc.getId(), e.getMessage());
            return new EmbedResult("FAILED", e.getMessage(), null);
        }
    }

    // ---- Helpers ----

    private String deriveTitle(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "Untitled";
        }
        String name = originalFilename;
        if (name.toLowerCase().endsWith(".pdf")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        return name;
    }

    /**
     * Derive title for an already-imported PDF using the virtual directory UUID as fallback.
     * Since files are stored with UUID-based paths, the title defaults to "PDF Import {uuid}"
     * when no meaningful metadata is available.
     */
    private String deriveTitleFromMetadata(String markdownPath, String uuid) {
        // Files are stored with UUID-based paths; originalFilename metadata not persisted separately.
        return "PDF Import " + uuid;
    }

    private String extractUuid(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int slashIdx = path.indexOf('/');
        return slashIdx > 0 ? path.substring(0, slashIdx) : path;
    }

    private static String computeSha256(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private PdfToRagResult toPdfToRagResult(DocumentBuildResult build, EmbedResult embed) {
        return new PdfToRagResult(
                build.doc().getId(),
                build.doc().getTitle(),
                build.newlyCreated(),
                embed != null ? embed.status() : null,
                embed != null ? embed.message() : null,
                embed != null ? embed.chunksCreated() : null);
    }

    // ---- Result types ----

    public record PdfToRagResult(
            Long documentId,
            String title,
            boolean newlyCreated,
            String embedStatus,
            String embedMessage,
            Integer chunksCreated
    ) {}

    private record EmbedResult(String status, String message, Integer chunksCreated) {}

    /**
     * Result of building (or finding) a RagDocument from a Markdown file.
     *
     * @param doc           the RagDocument entity (saved if newly created)
     * @param newlyCreated  true if this is a brand-new document
     * @param collectionUpdated true if an existing document had its collectionId updated
     * @param uuid          virtual directory UUID extracted from the Markdown path
     */
    private record DocumentBuildResult(
            RagDocument doc,
            boolean newlyCreated,
            boolean collectionUpdated,
            String uuid
    ) {}
}
