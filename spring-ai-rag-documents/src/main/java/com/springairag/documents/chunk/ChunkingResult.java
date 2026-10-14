package com.springairag.documents.chunk;

import java.util.List;

/** 分块和原文范围共享结果，供索引、Embedding 与预览使用。 */
public record ChunkingResult(
        List<TextChunk> chunks, List<ChunkMetadata> metadata, ChunkDiagnostics diagnostics) {
    public ChunkingResult {
        chunks = List.copyOf(chunks);
        metadata = List.copyOf(metadata);
        if (chunks.size() != metadata.size()) {
            throw new IllegalArgumentException("Chunk metadata count mismatch");
        }
    }

    public record ChunkMetadata(String kind, List<String> titlePath, int generatedChars) {
        public ChunkMetadata {
            titlePath = List.copyOf(titlePath);
        }
    }
}
