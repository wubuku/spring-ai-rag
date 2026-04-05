package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 删除知识库响应
 */
@Schema(description = "删除知识库响应")
public record CollectionDeleteResponse(
        @Schema(description = "操作结果消息", example = "集合已删除")
        String message,

        @Schema(description = "知识库 ID", example = "1")
        Long id,

        @Schema(description = "解除关联的文档数", example = "5")
        long documentsUnlinked
) {
    public static CollectionDeleteResponse of(Long id, long documentsUnlinked) {
        return new CollectionDeleteResponse("Collection deleted", id, documentsUnlinked);
    }
}
