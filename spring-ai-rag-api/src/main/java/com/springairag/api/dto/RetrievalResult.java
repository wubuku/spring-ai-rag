package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.Objects;

/**
 * Retrieval result
 */
@Schema(description = "Single retrieval result")
public class RetrievalResult {

    @Schema(description = "Source document ID", example = "doc-456")
    private String documentId;

    @Schema(description = "Matched text snippet")
    private String chunkText;

    @Schema(description = "Fused ranking score for ordering results within the same query/config; not a calibrated probability or percentage",
            example = "0.85")
    private double score;

    @Schema(description = "Raw vector cosine-similarity score; zero means no vector contribution in a fused result",
            example = "0.90")
    private double vectorScore;

    @Schema(description = "Raw provider-specific full-text score; zero means no full-text contribution in a fused result",
            example = "0.80")
    private double fulltextScore;

    @Schema(description = "Text chunk index within document", example = "2")
    private int chunkIndex;

    @Schema(description = "Source document title", example = "Spring AI Reference")
    private String title;

    @Schema(description = "Source identifier recorded on the logical RAG document",
            example = "pdf-import:550e8400-e29b-41d4-a716-446655440000/default.md")
    private String source;

    @Schema(description = "Original source filename when known",
            example = "spring-ai-reference.pdf")
    private String originalFilename;

    @Schema(description = "File-management directory for a traceable PDF result",
            example = "550e8400-e29b-41d4-a716-446655440000/")
    private String fileDirectoryPath;

    @Schema(description = "Converted file that was indexed for a traceable PDF result",
            example = "550e8400-e29b-41d4-a716-446655440000/default.md")
    private String indexedFilePath;

    @Schema(description = "Original PDF artifact for a traceable PDF result",
            example = "550e8400-e29b-41d4-a716-446655440000/original.pdf")
    private String originalFilePath;

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

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getFileDirectoryPath() { return fileDirectoryPath; }
    public void setFileDirectoryPath(String fileDirectoryPath) {
        this.fileDirectoryPath = fileDirectoryPath;
    }

    public String getIndexedFilePath() { return indexedFilePath; }
    public void setIndexedFilePath(String indexedFilePath) {
        this.indexedFilePath = indexedFilePath;
    }

    public String getOriginalFilePath() { return originalFilePath; }
    public void setOriginalFilePath(String originalFilePath) {
        this.originalFilePath = originalFilePath;
    }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    /**
     * Two retrieval results are equal when they point to the same document chunk.
     * Scores ({@code score}, {@code vectorScore}, {@code fulltextScore}) are excluded
     * from equality because they are query-specific and vary per search.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RetrievalResult that = (RetrievalResult) o;
        return chunkIndex == that.chunkIndex
                && Objects.equals(documentId, that.documentId)
                && Objects.equals(chunkText, that.chunkText)
                && Objects.equals(title, that.title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, chunkIndex, chunkText, title);
    }

    @Override
    public String toString() {
        return "RetrievalResult{" +
                "documentId='" + documentId + '\'' +
                ", chunkIndex=" + chunkIndex +
                ", score=" + score +
                ", title='" + title + '\'' +
                '}';
    }
}
