package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.Objects;

/**
 * 对话引用来源。
 *
 * <p>{@code score} 是当前查询与检索配置下的排序信号，不是概率或百分比。
 */
@Schema(description = "Chat citation source. Scores are ranking signals, not probabilities.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatSource {

    private String citationId;
    private String documentId;
    private Integer chunkIndex;
    private String title;
    private String chunkText;
    private Double score;
    private Double vectorScore;
    private Double fulltextScore;
    private String originalFilename;
    private String documentType;
    private String collectionKey;
    private String sourceType;
    private Map<String, Object> metadata;

    public String getCitationId() { return citationId; }
    public void setCitationId(String citationId) { this.citationId = citationId; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getChunkText() { return chunkText; }
    public void setChunkText(String chunkText) { this.chunkText = chunkText; }

    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }

    public Double getVectorScore() { return vectorScore; }
    public void setVectorScore(Double vectorScore) { this.vectorScore = vectorScore; }

    public Double getFulltextScore() { return fulltextScore; }
    public void setFulltextScore(Double fulltextScore) { this.fulltextScore = fulltextScore; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public String getCollectionKey() { return collectionKey; }
    public void setCollectionKey(String collectionKey) { this.collectionKey = collectionKey; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatSource that = (ChatSource) o;
        return Objects.equals(citationId, that.citationId)
                && Objects.equals(documentId, that.documentId)
                && Objects.equals(chunkIndex, that.chunkIndex)
                && Objects.equals(title, that.title)
                && Objects.equals(chunkText, that.chunkText)
                && Objects.equals(score, that.score)
                && Objects.equals(vectorScore, that.vectorScore)
                && Objects.equals(fulltextScore, that.fulltextScore)
                && Objects.equals(originalFilename, that.originalFilename)
                && Objects.equals(documentType, that.documentType)
                && Objects.equals(collectionKey, that.collectionKey)
                && Objects.equals(sourceType, that.sourceType)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(citationId, documentId, chunkIndex, title, chunkText,
                score, vectorScore, fulltextScore, originalFilename, documentType,
                collectionKey, sourceType, metadata);
    }

    @Override
    public String toString() {
        return "ChatSource{citationId='" + citationId + "', documentId='" + documentId
                + "', chunkIndex=" + chunkIndex + ", title='" + title
                + "', score=" + score + "}";
    }
}
