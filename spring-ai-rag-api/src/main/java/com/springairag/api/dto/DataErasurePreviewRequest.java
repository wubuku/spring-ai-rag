package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 数据擦除预览范围；范围只允许 principal/session/trace 的受限组合。 */
@Schema(description = "Preview a bounded, owner-scoped data erasure operation")
public record DataErasurePreviewRequest(
        @Size(max = 128)
        @Pattern(regexp = "[\\x20-\\x7E]*")
        @Schema(description = "Optional canonical target principal; normal callers may only name themselves")
        String targetPrincipalId,
        @Size(max = 36)
        @Pattern(regexp = "[A-Za-z0-9._~-]{1,36}")
        @Schema(description = "Optional chat session narrowing")
        String sessionId,
        @Size(max = 128)
        @Pattern(regexp = "[\\x20-\\x7E]{1,128}")
        @Schema(description = "Optional request trace narrowing")
        String traceId) {
}
