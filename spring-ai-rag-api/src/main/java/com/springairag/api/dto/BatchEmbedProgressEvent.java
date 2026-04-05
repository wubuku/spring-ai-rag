package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 批量嵌入进度事件（用于 SSE 实时推送）
 *
 * @param currentDocIndex 当前文档索引（0-based）
 * @param totalDocs       总文档数
 * @param currentDocId    当前处理的文档 ID
 * @param phase           当前阶段（PREPARING/CHUNKING/EMBEDDING/STORING/COMPLETED/FAILED）
 * @param current         当前文档内进度（chunk 编号）
 * @param total           当前文档内总进度（chunk 总数）
 * @param message         描述信息
 * @param successCount    成功数
 * @param failedCount     失败数
 * @param cachedCount     缓存命中数
 */
@Schema(description = "批量嵌入进度事件（SSE 流推送）")
public record BatchEmbedProgressEvent(
        @Schema(description = "当前文档索引（0-based）", example = "3")
        int currentDocIndex,

        @Schema(description = "总文档数", example = "20")
        int totalDocs,

        @Schema(description = "当前处理的文档 ID", example = "42")
        Long currentDocId,

        @Schema(description = "当前阶段", example = "EMBEDDING")
        String phase,

        @Schema(description = "当前文档内 chunk 进度", example = "5")
        int current,

        @Schema(description = "当前文档内 chunk 总数", example = "10")
        int total,

        @Schema(description = "描述信息", example = "文档 4/20：生成第 5/10 个块的嵌入向量")
        String message,

        @Schema(description = "成功数", example = "2")
        int successCount,

        @Schema(description = "失败数", example = "0")
        int failedCount,

        @Schema(description = "缓存命中数", example = "1")
        int cachedCount
) {
    public int overallPercent() {
        if (totalDocs == 0) return 0;
        return (currentDocIndex * 100) / totalDocs;
    }
}
