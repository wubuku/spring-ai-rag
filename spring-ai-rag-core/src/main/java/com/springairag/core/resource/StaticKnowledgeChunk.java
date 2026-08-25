package com.springairag.core.resource;

import java.util.List;
import java.util.Map;

/**
 * One bounded, immutable chunk of deployment-provided static knowledge.
 */
public record StaticKnowledgeChunk(
        String id,
        String rootKey,
        String sourceLabel,
        String relativePath,
        String contentDigest,
        int chunkIndex,
        String titlePath,
        String text,
        List<String> terms,
        Map<String, Object> metadata) {

    public StaticKnowledgeChunk {
        if (id == null || id.isBlank() || rootKey == null || rootKey.isBlank()
                || relativePath == null || relativePath.isBlank()
                || text == null || text.isBlank()) {
            throw new IllegalArgumentException("Invalid static knowledge chunk");
        }
        terms = terms == null ? List.of() : List.copyOf(terms);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
