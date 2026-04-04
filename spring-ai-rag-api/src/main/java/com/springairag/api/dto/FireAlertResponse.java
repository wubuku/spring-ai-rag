package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 触发告警响应
 *
 * @param alertId 告警 ID
 * @param message 操作结果消息
 */
@Schema(description = "触发告警响应")
public record FireAlertResponse(
        Long alertId,
        String message
) {
    public static FireAlertResponse of(Long alertId) {
        return new FireAlertResponse(alertId, "告警已触发");
    }
}
