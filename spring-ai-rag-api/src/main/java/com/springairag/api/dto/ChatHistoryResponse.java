package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

        @Schema(description = "Timestamp when this message pair was recorded.", example = "2026-04-12T10:00:00")
        LocalDateTime createdAt
) {
    @Override
    public String toString() {
        return "ChatHistoryResponse{" +
                "id=" + id +
                ", sessionId='" + sessionId + '\'' +
                ", userMessage='" + userMessage + '\'' +
                ", aiResponseLength=" + (aiResponse != null ? aiResponse.length() : 0) +
                ", relatedDocumentIds=" + relatedDocumentIds +
                ", metadata=" + metadata +
                ", createdAt=" + createdAt +
                '}';
    }
}
