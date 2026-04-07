package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
}
