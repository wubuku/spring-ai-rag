package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 创建知识库响应
 */
@Schema(description = "创建知识库响应")
public record CollectionCreatedResponse(
        @Schema(description = "操作结果消息")
        String message,

        @Schema(description = "知识库 ID", example = "1")
        Long collectionId,

        @Schema(description = "知识库名称", example = "我的知识库")
        String name
) {
    public static CollectionCreatedResponse of(Long collectionId, String name) {
        return new CollectionCreatedResponse("Collection created", collectionId, name);
    }
}
