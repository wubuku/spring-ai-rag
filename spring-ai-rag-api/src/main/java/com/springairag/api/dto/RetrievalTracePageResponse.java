package com.springairag.api.dto;

import java.util.List;

/**
 * 检索诊断分页信封。
 */
public record RetrievalTracePageResponse(
        List<RetrievalTraceSummaryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
