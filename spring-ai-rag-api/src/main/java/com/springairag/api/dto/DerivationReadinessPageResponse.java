package com.springairag.api.dto;

import java.util.List;

/** 有界派生完整性详情页。 */
public record DerivationReadinessPageResponse(
        String collectionKey,
        String bucket,
        int page,
        int size,
        long totalElements,
        List<DerivationReadinessDocument> documents
) {
}
