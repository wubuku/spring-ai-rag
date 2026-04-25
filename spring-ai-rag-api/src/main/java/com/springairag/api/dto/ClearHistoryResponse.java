package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * Clear session history response
 */
@Schema(description = "Clear session history response")
public record ClearHistoryResponse(
        @Schema(description = "Operation result message", example = "Session history cleared")
        String message,

        @Schema(description = "Session ID", example = "session-123")
        String sessionId,

        @Schema(description = "Number of deleted records", example = "5")
        int deletedCount
) {
    public static ClearHistoryResponse of(String sessionId, int deletedCount) {
        return new ClearHistoryResponse("Session history cleared", sessionId, deletedCount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof ClearHistoryResponse that
                && this.deletedCount == that.deletedCount
                && Objects.equals(this.message, that.message)
                && Objects.equals(this.sessionId, that.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, sessionId, deletedCount);
    }

    @Override
    public String toString() {
        return "ClearHistoryResponse{message=" + message + ", sessionId=" + sessionId
                + ", deletedCount=" + deletedCount + "}";
    }
}
