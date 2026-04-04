package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 清空会话历史响应
 */
@Schema(description = "清空会话历史响应")
public record ClearHistoryResponse(
        @Schema(description = "操作结果消息", example = "会话历史已清空")
        String message,

        @Schema(description = "会话 ID", example = "session-123")
        String sessionId,

        @Schema(description = "删除的记录数", example = "5")
        int deletedCount
) {
    public static ClearHistoryResponse of(String sessionId, int deletedCount) {
        return new ClearHistoryResponse("会话历史已清空", sessionId, deletedCount);
    }
}
