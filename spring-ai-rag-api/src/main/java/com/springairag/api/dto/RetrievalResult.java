package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 检索结果
 */
@Schema(description = "单条检索结果")
public class RetrievalResult {

    @Schema(description = "来源文档 ID", example = "doc-456")
    private String documentId;

    @Schema(description = "匹配的文本片段")
    private String chunkText;

    @Schema(description = "融合后的综合得分", example = "0.85")
    private double score;

    @Schema(description = "向量检索得分", example = "0.90")
    private double vectorScore;

    @Schema(description = "全文检索得分", example = "0.80")
    private double fulltextScore;

    @Schema(description = "文本块在文档中的索引", example = "2")
    private int chunkIndex;

    @Schema(description = "附加元数据")
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
