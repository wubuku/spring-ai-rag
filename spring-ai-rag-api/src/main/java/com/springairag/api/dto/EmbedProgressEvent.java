package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

/**
 * 嵌入进度事件（用于 SSE 实时推送）
 *
 * @param phase 当前阶段（PREPARING/CHUNKING/EMBEDDING/STORING/COMPLETED/FAILED）
 * @param current 当前处理数量
 * @param total 总数量
 * @param message 描述信息
 * @param documentId 文档 ID
 */
@Schema(description = "嵌入进度事件（SSE 流推送）")
public record EmbedProgressEvent(
        @Schema(description = "当前阶段", example = "EMBEDDING")
        String phase,

        @Schema(description = "当前处理数量", example = "5")
        int current,

        @Schema(description = "总数量", example = "20")
        int total,

        @Schema(description = "描述信息", example = "正在生成第 5/20 个块的嵌入向量")
        String message,

        @Schema(description = "关联文档 ID", example = "42")
        Long documentId
) implements Serializable {

    public static EmbedProgressEvent preparing(Long docId) {
        return new EmbedProgressEvent("PREPARING", 0, 0, "正在准备文档...", docId);
    }

    public static EmbedProgressEvent chunking(Long docId, int total) {
        return new EmbedProgressEvent("CHUNKING", 0, total, "正在分块，共 " + total + " 个块", docId);
    }

    public static EmbedProgressEvent embedding(Long docId, int current, int total) {
        return new EmbedProgressEvent("EMBEDDING", current, total,
                "正在生成第 " + current + "/" + total + " 个块的嵌入向量", docId);
    }

    public static EmbedProgressEvent storing(Long docId, int current, int total) {
        return new EmbedProgressEvent("STORING", current, total,
                "正在存储第 " + current + "/" + total + " 个嵌入向量", docId);
    }

    public static EmbedProgressEvent completed(Long docId, int total) {
        return new EmbedProgressEvent("COMPLETED", total, total,
                "嵌入生成完成，共 " + total + " 个块", docId);
    }

    public static EmbedProgressEvent failed(Long docId, String reason) {
        return new EmbedProgressEvent("FAILED", 0, 0, "嵌入失败: " + reason, docId);
    }
}
