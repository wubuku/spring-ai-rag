package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 手动触发告警请求
 */
@Schema(description = "手动触发告警请求")
public record FireAlertRequest(
        @Schema(description = "告警类型", example = "manual")
        String alertType,

        @Schema(description = "告警名称", example = "手动测试告警")
        String alertName,

        @Schema(description = "告警消息", example = "这是一条手动触发的测试告警")
        String message,

        @Schema(description = "严重程度", example = "WARNING")
        String severity,

        @Schema(description = "关联指标")
        Map<String, Object> metrics
) {
}
