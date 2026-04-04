package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 批量创建文档响应（统一响应格式）
 */
@Schema(description = "批量创建文档响应")
public record BatchCreateResponse(
        @Schema(description = "成功创建的文档数", example = "10")
        int created,

        @Schema(description = "跳过的文档数（内容未变更/已存在）", example = "2")
        int skipped,

        @Schema(description = "失败的文档数", example = "0")
        int failed,

        @Schema(description = "各文档的创建结果详情")
        List<DocumentResult> results
) {
    @Schema(description = "单个文档结果")
    public record DocumentResult(
            @Schema(description = "文档 ID", example = "1")
            Long documentId,

            @Schema(description = "文档标题", example = "产品说明书")
            String title,

            @Schema(description = "是否新建（false=已存在）", example = "true")
            boolean newlyCreated,

            @Schema(description = "错误信息（失败时）")
            String error
    ) {}
}
