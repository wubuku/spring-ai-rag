package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 文件上传并嵌入响应
 */
@Schema(description = "文件上传并嵌入响应")
public record FileUploadResponse(
        @Schema(description = "成功处理的文档数", example = "10")
        int processed,

        @Schema(description = "成功的文档数", example = "10")
        int success,

        @Schema(description = "失败的文档数", example = "0")
        int failed,

        @Schema(description = "各文件的处理结果")
        List<FileResult> results
) {
    @Schema(description = "单个文件处理结果")
    public record FileResult(
            @Schema(description = "原文件名", example = "产品说明书.txt")
            String filename,

            @Schema(description = "文档 ID（成功时）", example = "1")
            Long documentId,

            @Schema(description = "文档标题", example = "产品说明书")
            String title,

            @Schema(description = "是否成功嵌入", example = "true")
            boolean embedded,

            @Schema(description = "分块数量（成功时）", example = "5")
            int chunks,

            @Schema(description = "错误信息（失败时）")
            String error
    ) {}
}
