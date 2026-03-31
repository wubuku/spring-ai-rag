package com.springairag.api.dto;

import java.util.Map;

/**
 * 检索结果
 */
public class RetrievalResult {

    private String documentId;
    private String chunkText;
    private double score;
    private double vectorScore;
    private double fulltextScore;
    private int chunkIndex;
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

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
