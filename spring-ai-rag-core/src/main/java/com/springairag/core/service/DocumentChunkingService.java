package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import com.springairag.documents.chunk.HierarchicalTextChunker;
import com.springairag.documents.chunk.TextChunk;
import com.springairag.documents.chunk.BoundedTextChunker;
import com.springairag.documents.chunk.ChunkingException;
import com.springairag.documents.chunk.ChunkingResult;
import com.springairag.documents.chunk.ChunkValidator;
import com.springairag.core.config.RagChunkProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 文档派生分块的唯一协调入口。
 *
 * <p>本地关键词索引和远程 embedding 必须使用同一个 document kind、
 * chunker version 和 chunk 列表。这里不涉及任何 provider 调用。</p>
 */
@Service
public class DocumentChunkingService {

    private final com.springairag.core.config.RagProperties properties;
    private final DocumentDerivationDescriptorProvider descriptorProvider;

    public DocumentChunkingService(
            com.springairag.core.config.RagProperties properties,
            DocumentDerivationDescriptorProvider descriptorProvider) {
        properties.getChunk().validate();
        this.properties = properties;
        this.descriptorProvider = descriptorProvider;
    }

    public PreparedChunks prepare(RagDocument document) {
        ChunkingResult result = prepareDetailed(document);
        return new PreparedChunks(descriptorProvider.describe(document), result.chunks());
    }

    public ChunkingResult prepareDetailed(RagDocument document) {
        Objects.requireNonNull(document, "document");
        String content = document.getContent();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(
                    "Document content is empty: documentId=" + document.getId());
        }
        RagChunkProperties settings = properties.getChunk();
        settings.validate();
        if (RagDocument.JSON_RECORD.equals(document.getDocumentType())) {
            List<TextChunk> chunks = List.of(new TextChunk(content, 0, content.length()));
            return new ChunkingResult(chunks,
                    List.of(new ChunkingResult.ChunkMetadata("JSON_RECORD", List.of(), 0)),
                    ChunkValidator.analyze(content, chunks, Integer.MAX_VALUE, 1, List.of("SINGLE_RECORD")));
        }
        String version = descriptorProvider.describe(document).chunkerVersion();
        String[] identity = version.split(":");
        int size = Integer.parseInt(identity[1]);
        int minimum = Integer.parseInt(identity[2]);
        int overlap = Integer.parseInt(identity[3]);
        if (identity[0].equals("bounded-v3")) {
            return new BoundedTextChunker(size, minimum, overlap,
                    Integer.parseInt(identity[4]), Integer.parseInt(identity[5])).split(content);
        }
        List<TextChunk> chunks = new HierarchicalTextChunker(size, minimum, overlap).split(content);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "Non-blank document produced no chunks: documentId=" + document.getId());
        }
        return new ChunkingResult(chunks, chunks.stream()
                .map(chunk -> new ChunkingResult.ChunkMetadata("LEGACY", List.of(), 0)).toList(),
                ChunkValidator.analyze(content, chunks, Math.max(1, size),
                        Integer.MAX_VALUE, List.of("LEGACY_NOT_STRICT")));
    }

    public ChunkingResult preview(RagDocument document) {
        Objects.requireNonNull(document, "document");
        if (document.getContent() != null
                && document.getContent().length() > properties.getChunk().getPreviewMaxChars()) {
            throw new ChunkingException("CHUNK_PREVIEW_INPUT_LIMIT");
        }
        return prepareDetailed(document);
    }

    public String profileFor(RagDocument document) {
        return RagDocument.JSON_RECORD.equals(document.getDocumentType()) ? "single-record"
                : descriptorProvider.describe(document).chunkerVersion().startsWith("bounded-v3:")
                ? "bounded-v3" : "legacy-v2";
    }

    /**
     * The chunker version this document's content would derive under, without
     * deriving any chunks.
     *
     * <p>Exists because {@link #prepare(RagDocument)} does the splitting too, and
     * asking it for a version string alone still walked the whole document
     * through the hierarchical chunker. Cache-freshness checks did exactly that:
     * {@code DocumentEmbedService} split the document, threw the chunks away,
     * compared the version against the row in
     * {@code rag_document_embedding_state}, and returned {@code CACHED} — so the
     * common case (document unchanged, nothing to redo) paid a full chunking pass
     * to learn it had nothing to do. It also made a blank-content document throw
     * from a code path that was only ever going to read a status.
     *
     * <p>The answer is identical to {@code prepare(doc).descriptor().chunkerVersion()}
     * — both go through {@link DocumentDerivationDescriptorProvider}, which is the
     * single source of derivation identity. Only the work skipped differs.
     *
     * <p>Returns without touching {@code document.getContent()}, so a document
     * whose content is absent or blank yields its version rather than an
     * {@link IllegalArgumentException}.
     */
    public String chunkerVersionFor(RagDocument document) {
        Objects.requireNonNull(document, "document");
        return descriptorProvider.describe(document).chunkerVersion();
    }

    public record PreparedChunks(
            DocumentDerivationDescriptorProvider.Descriptor descriptor,
            List<TextChunk> chunks) {
        public PreparedChunks {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        }
    }
}
