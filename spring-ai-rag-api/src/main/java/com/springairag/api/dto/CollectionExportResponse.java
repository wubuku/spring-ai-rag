package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 导出知识库响应
 */
@Schema(description = "导出知识库响应")
public record CollectionExportResponse(
        @Schema(description = "知识库信息")
        CollectionResponse collection,

        @Schema(description = "文档数量", example = "42")
        int documentCount
) {
}
