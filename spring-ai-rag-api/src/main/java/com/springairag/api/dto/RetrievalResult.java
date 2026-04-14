package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Retrieval result
 */
@Schema(description = "Single retrieval result")
public class RetrievalResult {

    @Schema(description = "Source document ID", example = "doc-456")
    private String documentId;

    @Schema(description = "Matched text snippet")
    private String chunkText;

    @Schema(description = "Fused combined score", example = "0.85")
    private double score;

    @Schema(description = "Vector search score", example = "0.90")
    private double vectorScore;

    @Schema(description = "Fulltext search score", example = "0.80")
    private double fulltextScore;

    @Schema(description = "Text chunk index within document", example = "2")
    private int chunkIndex;

    @Schema(description = "Source document title", example = "Spring AI Reference")
    private String title;

    @Schema(description = "Additional metadata")
    private Map<String, Object> metadata;

    public RetrievalResult() {}

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getChunkText() { return chunkText; }
    public void setChunkText(String chunkText) { this.chunkText = chunkText; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public double getVectorScore() { return vectorScore; }
    public void setVectorScore(double vectorScore) { this.vectorScore = vectorScore; }

    public double getFulltextScore() { return fulltextScore; }
    public void setFulltextScore(double fulltextScore) { this.fulltextScore = fulltextScore; }

    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
