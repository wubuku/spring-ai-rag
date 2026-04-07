package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * RAG chat response
 */
@Schema(description = "RAG chat response")
public class ChatResponse {

    @Schema(description = "LLM-generated answer text")
    private String answer;

    @Schema(description = "Request trace ID (end-to-end traceable)", example = "a1b2c3d4e5f6")
    private String traceId;

    @Schema(description = "Source document citations")
    private List<SourceDocument> sources;

    @Schema(description = "Response metadata (contains sessionId, etc.)")
    private Map<String, Object> metadata;

    @Schema(description = "RAG Pipeline step metrics (duration ms + result count per step)")
    private List<StepMetricRecord> stepMetrics;

    public ChatResponse() {}

    public ChatResponse(String answer) {
        this.answer = answer;
    }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public List<SourceDocument> getSources() { return sources; }
    public void setSources(List<SourceDocument> sources) { this.sources = sources; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public List<StepMetricRecord> getStepMetrics() { return stepMetrics; }
    public void setStepMetrics(List<StepMetricRecord> stepMetrics) { this.stepMetrics = stepMetrics; }

    public static ChatResponseBuilder builder() { return new ChatResponseBuilder(); }

    public static class ChatResponseBuilder {
        private final ChatResponse response = new ChatResponse();

        public ChatResponseBuilder answer(String answer) { response.setAnswer(answer); return this; }
        public ChatResponseBuilder traceId(String traceId) { response.setTraceId(traceId); return this; }
        public ChatResponseBuilder sources(List<SourceDocument> sources) { response.setSources(sources); return this; }
        public ChatResponseBuilder metadata(Map<String, Object> metadata) { response.setMetadata(metadata); return this; }
        public ChatResponseBuilder stepMetrics(List<StepMetricRecord> stepMetrics) { response.setStepMetrics(stepMetrics); return this; }
        public ChatResponse build() { return response; }
    }

    /**
     * Single RAG Pipeline step execution metrics
     */
    @Schema(description = "Single RAG Pipeline step execution metrics")
    public static class StepMetricRecord {

        @Schema(description = "Step name", example = "HybridSearch")
        private String stepName;

        @Schema(description = "Execution duration in milliseconds", example = "23")
        private long durationMs;

        @Schema(description = "Number of output results", example = "12")
        private int resultCount;

        public StepMetricRecord() {}

        public StepMetricRecord(String stepName, long durationMs, int resultCount) {
            this.stepName = stepName;
            this.durationMs = durationMs;
            this.resultCount = resultCount;
        }

        public String getStepName() { return stepName; }
        public void setStepName(String stepName) { this.stepName = stepName; }
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
        public int getResultCount() { return resultCount; }
        public void setResultCount(int resultCount) { this.resultCount = resultCount; }
    }

    /**
     * Source document snippet
     */
    @Schema(description = "Source document citation snippet")
    public static class SourceDocument {

        @Schema(description = "Source document ID", example = "doc-456")
        private String documentId;

        @Schema(description = "Matched text snippet", example = "Return policy: Within 7 days of receiving the product...")
        private String chunkText;

        @Schema(description = "Relevance score (0-1)", example = "0.92")
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
