package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 知识库列表响应
 */
@Schema(description = "知识库列表响应")
public record CollectionListResponse(
        @Schema(description = "知识库列表")
        List<CollectionResponse> collections,

        @Schema(description = "总数", example = "25")
        long total,

        @Schema(description = "页码", example = "0")
        int page,

        @Schema(description = "每页大小", example = "10")
        int pageSize
) {
}
