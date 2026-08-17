package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.springairag.api.enums.ChatMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RAG chat response
 */
@Schema(description = "RAG chat response")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatResponse {

    @Schema(description = "LLM-generated answer text")
    private String answer;

    @Schema(description = "Request trace ID (end-to-end traceable)", example = "a1b2c3d4e5f6")
    private String traceId;

    @Schema(description = "Source document citations")
    private List<ChatSource> sources;

    private String sessionId;
    private ChatMode mode;
    private String requestedModel;
    private String resolvedModel;
    private Map<String, Object> usage;
    private String finishReason;

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

    public List<ChatSource> getSources() { return sources; }
    public void setSources(List<? extends ChatSource> sources) {
        this.sources = sources != null ? List.copyOf(sources) : null;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public ChatMode getMode() { return mode; }
    public void setMode(ChatMode mode) { this.mode = mode; }

    public String getRequestedModel() { return requestedModel; }
    public void setRequestedModel(String requestedModel) { this.requestedModel = requestedModel; }

    public String getResolvedModel() { return resolvedModel; }
    public void setResolvedModel(String resolvedModel) { this.resolvedModel = resolvedModel; }

    public Map<String, Object> getUsage() { return usage; }
    public void setUsage(Map<String, Object> usage) { this.usage = usage; }

    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String finishReason) { this.finishReason = finishReason; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public List<StepMetricRecord> getStepMetrics() { return stepMetrics; }
    public void setStepMetrics(List<StepMetricRecord> stepMetrics) { this.stepMetrics = stepMetrics; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatResponse that = (ChatResponse) o;
        return Objects.equals(answer, that.answer)
                && Objects.equals(traceId, that.traceId)
                && Objects.equals(sources, that.sources)
                && Objects.equals(sessionId, that.sessionId)
                && mode == that.mode
                && Objects.equals(requestedModel, that.requestedModel)
                && Objects.equals(resolvedModel, that.resolvedModel)
                && Objects.equals(usage, that.usage)
                && Objects.equals(finishReason, that.finishReason)
                && Objects.equals(metadata, that.metadata)
                && Objects.equals(stepMetrics, that.stepMetrics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(answer, traceId, sources, sessionId, mode, requestedModel,
                resolvedModel, usage, finishReason, metadata, stepMetrics);
    }

    @Override
    public String toString() {
        return "ChatResponse{answer='" + answer + "', traceId='" + traceId
                + "', sources=" + (sources != null ? sources.size() : 0) + " source(s)"
                + ", metadata=" + (metadata != null ? metadata.size() + " key(s)" : "null")
                + ", stepMetrics=" + (stepMetrics != null ? stepMetrics.size() + " step(s)" : "null") + "}";
    }

    public static ChatResponseBuilder builder() { return new ChatResponseBuilder(); }

    public static class ChatResponseBuilder {
        private final ChatResponse response = new ChatResponse();

        public ChatResponseBuilder answer(String answer) { response.setAnswer(answer); return this; }
        public ChatResponseBuilder traceId(String traceId) { response.setTraceId(traceId); return this; }
        public ChatResponseBuilder sources(List<? extends ChatSource> sources) { response.setSources(sources); return this; }
        public ChatResponseBuilder sessionId(String sessionId) { response.setSessionId(sessionId); return this; }
        public ChatResponseBuilder mode(ChatMode mode) { response.setMode(mode); return this; }
        public ChatResponseBuilder requestedModel(String value) { response.setRequestedModel(value); return this; }
        public ChatResponseBuilder resolvedModel(String value) { response.setResolvedModel(value); return this; }
        public ChatResponseBuilder usage(Map<String, Object> value) { response.setUsage(value); return this; }
        public ChatResponseBuilder finishReason(String value) { response.setFinishReason(value); return this; }
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StepMetricRecord that = (StepMetricRecord) o;
            return durationMs == that.durationMs
                    && resultCount == that.resultCount
                    && Objects.equals(stepName, that.stepName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stepName, durationMs, resultCount);
        }

        @Override
        public String toString() {
            return "StepMetricRecord{stepName='" + stepName + "', durationMs=" + durationMs
                    + ", resultCount=" + resultCount + "}";
        }
    }

    /**
     * Source document snippet
     */
    @Schema(description = "Source document citation snippet")
    @Deprecated
    public static class SourceDocument extends ChatSource {
    }
}
