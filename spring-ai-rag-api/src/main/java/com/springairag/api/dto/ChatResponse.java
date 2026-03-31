package com.springairag.api.dto;

import java.util.List;
import java.util.Map;

/**
 * RAG 问答响应
 */
public class ChatResponse {

    private String answer;
    private List<SourceDocument> sources;
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
    public static class SourceDocument {
        private String documentId;
        private String chunkText;
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
