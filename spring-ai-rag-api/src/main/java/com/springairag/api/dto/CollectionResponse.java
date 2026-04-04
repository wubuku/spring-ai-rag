package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * 知识库详情响应（用于 getById, export 等）
 */
@Schema(description = "知识库详情响应")
public record CollectionResponse(
        @Schema(description = "知识库 ID", example = "1")
        Long id,

        @Schema(description = "知识库名称", example = "我的知识库")
        String name,

        @Schema(description = "描述")
        String description,

        @Schema(description = "嵌入模型", example = "BAAI/bge-m3")
        String embeddingModel,

        @Schema(description = "向量维度", example = "1024")
        int dimensions,

        @Schema(description = "是否启用")
        boolean enabled,

        @Schema(description = "元数据")
        Map<String, Object> metadata,

        @Schema(description = "创建时间")
        ZonedDateTime createdAt,

        @Schema(description = "更新时间")
        ZonedDateTime updatedAt,

        @Schema(description = "文档数量")
        long documentCount
) {
}
