package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 解决告警请求
 */
@Schema(description = "解决告警请求")
public record ResolveAlertRequest(
        @Schema(description = "解决方案描述", example = "已重启服务")
        String resolution
) {
}
