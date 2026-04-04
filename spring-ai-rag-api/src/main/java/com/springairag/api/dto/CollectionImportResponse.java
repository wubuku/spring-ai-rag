package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 导入知识库响应
 */
@Schema(description = "导入知识库响应")
public record CollectionImportResponse(
        @Schema(description = "操作结果消息")
        String message,

        @Schema(description = "知识库 ID", example = "1")
        Long collectionId,

        @Schema(description = "成功导入的文档数", example = "10")
        int imported,

        @Schema(description = "跳过的文档数（重复）", example = "2")
        int skipped
) {
    public static CollectionImportResponse of(Long collectionId, int imported, int skipped) {
        return new CollectionImportResponse("知识库导入完成", collectionId, imported, skipped);
    }
}
