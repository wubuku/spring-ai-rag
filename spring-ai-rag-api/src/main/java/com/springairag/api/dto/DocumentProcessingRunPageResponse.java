package com.springairag.api.dto;

import java.util.List;

/** 文档处理运行的稳定分页信封。 */
public record DocumentProcessingRunPageResponse(
        List<DocumentProcessingRunResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
