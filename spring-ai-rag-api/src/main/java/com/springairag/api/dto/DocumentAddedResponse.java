package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 添加文档到知识库响应
 */
@Schema(description = "添加文档到知识库响应")
public record DocumentAddedResponse(
        @Schema(description = "操作结果消息")
        String message,

        @Schema(description = "知识库 ID", example = "1")
        Long collectionId,

        @Schema(description = "文档 ID", example = "1")
        Long documentId
) {
    public static DocumentAddedResponse of(Long collectionId, Long documentId) {
        return new DocumentAddedResponse("Document added to collection", collectionId, documentId);
    }
}
