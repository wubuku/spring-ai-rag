package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.springairag.api.enums.ChatMode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Chat history record returned by GET /chat/history/{sessionId}.
 */
@Schema(description = "A single chat message pair in the session history.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatHistoryResponse(
        @Schema(description = "Unique identifier of the history record.", example = "1")
        Long id,

        @Schema(description = "Session identifier.", example = "abc-123")
        String sessionId,

        @Schema(description = "User's message.", example = "What is RAG?")
        String userMessage,

        @Schema(description = "AI assistant's response.", example = "RAG is retrieval-augmented generation...")
        String aiResponse,

        @Schema(description = "IDs of documents retrieved and used for this response.", example = "[1, 2, 3]")
        List<Long> relatedDocumentIds,

        @Schema(description = "Additional metadata about this exchange.")
        Map<String, Object> metadata,

        @Schema(description = "Citation sources captured when this turn completed.")
        List<ChatSource> sources,

        @Schema(description = "Turn status.", example = "COMPLETE")
        String status,

        @Schema(description = "Chat execution mode.", example = "KNOWLEDGE")
        ChatMode mode,

        @Schema(description = "Model requested by the caller.")
        String requestedModel,

        @Schema(description = "Model that produced the answer.")
        String resolvedModel,

        @Schema(description = "Timestamp when this message pair was recorded.", example = "2026-04-12T10:00:00")
        LocalDateTime createdAt
) {
    public ChatHistoryResponse(
            Long id,
            String sessionId,
            String userMessage,
            String aiResponse,
            List<Long> relatedDocumentIds,
            Map<String, Object> metadata,
            LocalDateTime createdAt) {
        this(id, sessionId, userMessage, aiResponse, relatedDocumentIds, metadata,
                null, "COMPLETE", ChatMode.KNOWLEDGE, null, null, createdAt);
    }

    @Override
    public String toString() {
        return "ChatHistoryResponse{" +
                "id=" + id +
                ", sessionId='" + sessionId + '\'' +
                ", userMessage='" + userMessage + '\'' +
                ", aiResponseLength=" + (aiResponse != null ? aiResponse.length() : 0) +
                ", relatedDocumentIds=" + relatedDocumentIds +
                ", sources=" + (sources != null ? sources.size() : 0) +
                ", status='" + status + '\'' +
                ", mode=" + mode +
                ", metadata=" + metadata +
                ", createdAt=" + createdAt +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatHistoryResponse that = (ChatHistoryResponse) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(sessionId, that.sessionId) &&
                Objects.equals(userMessage, that.userMessage) &&
                Objects.equals(aiResponse, that.aiResponse) &&
                Objects.equals(relatedDocumentIds, that.relatedDocumentIds) &&
                Objects.equals(metadata, that.metadata) &&
                Objects.equals(sources, that.sources) &&
                Objects.equals(status, that.status) &&
                mode == that.mode &&
                Objects.equals(requestedModel, that.requestedModel) &&
                Objects.equals(resolvedModel, that.resolvedModel) &&
                Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, sessionId, userMessage, aiResponse, relatedDocumentIds,
                metadata, sources, status, mode, requestedModel, resolvedModel, createdAt);
    }
}
