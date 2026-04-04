package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 知识库文档列表响应
 */
@Schema(description = "知识库文档列表响应")
public record DocumentListResponse(
        @Schema(description = "文档列表")
        List<?> documents,

        @Schema(description = "总数", example = "25")
        long total
) {
}
