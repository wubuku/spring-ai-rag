package com.springairag.api.dto;

/** 外部文档 Collection 原子迁移结果。 */
public record ExternalDocumentRelocateResponse(
        Long documentId,
        String sourceCollectionKey,
        String targetCollectionKey,
        String sourceNamespace,
        String externalId,
        String sourceRevision,
        String action,
        long documentRevision,
        int versionNumber,
        boolean contentChanged,
        String derivationAction,
        DocumentLifecycleResponse lifecycle
) {
}
