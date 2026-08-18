package com.springairag.api.dto;

import java.util.List;

/**
 * embedding job 列表的稳定分页信封。
 */
public record EmbeddingJobPageResponse(
        List<EmbeddingJobResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
