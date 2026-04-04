package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 静默告警请求
 */
@Schema(description = "静默告警请求")
public record SilenceAlertRequest(
        @Schema(description = "告警键", example = "high-latency")
        String alertKey,

        @Schema(description = "静默时长（分钟）", example = "60")
        Integer durationMinutes
) {
}
