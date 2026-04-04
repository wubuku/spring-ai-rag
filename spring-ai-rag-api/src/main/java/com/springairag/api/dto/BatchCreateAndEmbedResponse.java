package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * 批量创建并嵌入文档响应
 */
@Schema(description = "批量创建并嵌入文档响应")
public record BatchCreateAndEmbedResponse(
        @Schema(description = "成功创建的文档数", example = "10")
        int created,

        @Schema(description = "成功嵌入向量的文档数", example = "10")
        int embedded,

        @Schema(description = "跳过的文档数（内容未变更）", example = "2")
        int skipped,

        @Schema(description = "失败的文档数", example = "0")
        int failed,

        @Schema(description = "各文档的创建+嵌入结果详情")
        List<DocumentResult> results
) {
    @Schema(description = "单个文档结果")
    public record DocumentResult(
            @Schema(description = "文档 ID", example = "1")
            Long documentId,

            @Schema(description = "文档标题", example = "产品说明书")
            String title,

            @Schema(description = "是否成功嵌入", example = "true")
            boolean embedded,

            @Schema(description = "分块数量", example = "5")
            int chunks,

            @Schema(description = "错误信息（失败时）")
            String error
    ) {}
}
