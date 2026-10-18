package com.springairag.api.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.springairag.api.dto.ChatSource;

/**
 * Privacy-safe source projection for the OpenAI-compatible extension.
 *
 * <p>It intentionally omits chunk text and arbitrary metadata. Clients that
 * need the full native source contract must use the Native RAG API.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiRagSource(
        String citationId,
        String sourceHandle,
        String documentId,
        Integer chunkIndex,
        String title,
        Double score,
        Double vectorScore,
        Double fulltextScore,
        String originalFilename,
        String documentType,
        String collectionKey,
        String sourceType,
        com.springairag.api.dto.EvidenceSnapshot evidenceSnapshot) {

    public OpenAiRagSource(String citationId, String sourceHandle, String documentId,
            Integer chunkIndex, String title, Double score, Double vectorScore,
            Double fulltextScore, String originalFilename, String documentType,
            String collectionKey, String sourceType) {
        this(citationId, sourceHandle, documentId, chunkIndex, title, score,
                vectorScore, fulltextScore, originalFilename, documentType,
                collectionKey, sourceType, null);
    }

    public static OpenAiRagSource from(ChatSource source) {
        if (source == null) {
            return null;
        }
        return new OpenAiRagSource(
                source.getCitationId(),
                source.getSourceHandle(),
                source.getDocumentId(),
                source.getChunkIndex(),
                source.getTitle(),
                source.getScore(),
                source.getVectorScore(),
                source.getFulltextScore(),
                source.getOriginalFilename(),
                source.getDocumentType(),
                source.getCollectionKey(),
                source.getSourceType(),
                source.getEvidenceSnapshot());
    }
}
