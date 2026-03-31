package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * RAG 问答响应
 */
@Schema(description = "RAG 问答响应")
public class ChatResponse {

    @Schema(description = "LLM 生成的回答文本")
    private String answer;

    @Schema(description = "引用来源文档列表")
    private List<SourceDocument> sources;

    @Schema(description = "响应元数据（包含 sessionId 等）")
    private Map<String, Object> metadata;

    public ChatResponse() {}

    public ChatResponse(String answer) {
        this.answer = answer;
    }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public List<SourceDocument> getSources() { return sources; }
    public void setSources(List<SourceDocument> sources) { this.sources = sources; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public static ChatResponseBuilder builder() { return new ChatResponseBuilder(); }

    public static class ChatResponseBuilder {
        private final ChatResponse response = new ChatResponse();

        public ChatResponseBuilder answer(String answer) { response.setAnswer(answer); return this; }
        public ChatResponseBuilder sources(List<SourceDocument> sources) { response.setSources(sources); return this; }
        public ChatResponseBuilder metadata(Map<String, Object> metadata) { response.setMetadata(metadata); return this; }
        public ChatResponse build() { return response; }
    }

    /**
     * 来源文档片段
     */
    @Schema(description = "引用来源文档片段")
    public static class SourceDocument {

        @Schema(description = "来源文档 ID", example = "doc-456")
        private String documentId;

        @Schema(description = "匹配的文本片段", example = "退货政策：自收到商品之日起7天内...")
        private String chunkText;

        @Schema(description = "相关性得分 (0-1)", example = "0.92")
        private double score;

        public SourceDocument() {}

        public String getDocumentId() { return documentId; }
        public void setDocumentId(String documentId) { this.documentId = documentId; }

        public String getChunkText() { return chunkText; }
        public void setChunkText(String chunkText) { this.chunkText = chunkText; }

        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
    }
}
