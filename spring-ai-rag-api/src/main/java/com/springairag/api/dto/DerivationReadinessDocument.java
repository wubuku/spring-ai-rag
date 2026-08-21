package com.springairag.api.dto;

import java.util.List;
import java.util.UUID;

/** 不包含正文和向量的单文档派生完整性详情。 */
public record DerivationReadinessDocument(
        long documentId,
        String title,
        long documentRevision,
        String sourceNamespace,
        String externalId,
        String bucket,
        String localCondition,
        long localGeneration,
        int localExpectedChunkCount,
        int localActualChunkCount,
        String vectorCondition,
        long vectorGeneration,
        int vectorExpectedChunkCount,
        int vectorActualChunkCount,
        UUID activeJobId,
        String activeJobStatus,
        String reasonCode,
        String error,
        boolean repairable,
        List<String> recommendedActions
) {
}
