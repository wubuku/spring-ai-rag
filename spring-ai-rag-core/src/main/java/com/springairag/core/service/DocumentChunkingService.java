package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import com.springairag.documents.chunk.HierarchicalTextChunker;
import com.springairag.documents.chunk.TextChunk;
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

    private final HierarchicalTextChunker textChunker;
    private final DocumentDerivationDescriptorProvider descriptorProvider;

    public DocumentChunkingService(
            com.springairag.core.config.RagProperties properties,
            DocumentDerivationDescriptorProvider descriptorProvider) {
        this.textChunker = new HierarchicalTextChunker(
                properties.getChunk().getDefaultChunkSize(),
                properties.getChunk().getMinChunkSize(),
                properties.getChunk().getDefaultChunkOverlap());
        this.descriptorProvider = descriptorProvider;
    }

    public PreparedChunks prepare(RagDocument document) {
        Objects.requireNonNull(document, "document");
        String content = document.getContent();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(
                    "Document content is empty: documentId=" + document.getId());
        }
        DocumentDerivationDescriptorProvider.Descriptor descriptor =
                descriptorProvider.describe(document);
        List<TextChunk> chunks = RagDocument.JSON_RECORD.equals(
                document.getDocumentType())
                ? List.of(new TextChunk(content, 0, content.length()))
                : textChunker.split(content);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "Non-blank document produced no chunks: documentId=" + document.getId());
        }
        return new PreparedChunks(descriptor, chunks);
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
