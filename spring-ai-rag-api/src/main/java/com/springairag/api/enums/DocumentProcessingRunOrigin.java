package com.springairag.api.enums;

import java.util.Locale;

/** 文档处理运行的有限来源枚举。 */
public enum DocumentProcessingRunOrigin {
    API,
    LOCAL_CREATE,
    FILE_UPLOAD,
    DOCUMENT_EMBED,
    REEMBED_MISSING,
    BATCH_EMBED,
    DERIVATION_REPAIR,
    DOCUMENT_MUTATION,
    PDF_TO_RAG,
    EXTERNAL_SYNC,
    OTHER;

    /** 将旧 Job 的自由文本来源归一为稳定的低基数来源。 */
    public static DocumentProcessingRunOrigin fromRaw(String value) {
        String raw = value == null
                ? "OTHER"
                : value.trim().toUpperCase(Locale.ROOT);
        if (raw.endsWith("_DUPLICATE_FORCE")) {
            raw = raw.substring(0, raw.length() - "_DUPLICATE_FORCE".length());
        }
        return switch (raw) {
            case "API" -> API;
            case "LOCAL_CREATE", "BATCH_CREATE", "COLLECTION_CLONE",
                    "COLLECTION_IMPORT" -> LOCAL_CREATE;
            case "FILE_UPLOAD" -> FILE_UPLOAD;
            case "DOCUMENT_EMBED" -> DOCUMENT_EMBED;
            case "REEMBED_MISSING" -> REEMBED_MISSING;
            case "BATCH_EMBED" -> BATCH_EMBED;
            case "DERIVATION_REPAIR" -> DERIVATION_REPAIR;
            case "LOCAL_PATCH", "LOCAL_RESTORE", "LOCAL_VERSION_RESTORE" ->
                    DOCUMENT_MUTATION;
            case "PDF_TO_RAG" -> PDF_TO_RAG;
            case "EXTERNAL_UPSERT", "JSON_RECORD_UPSERT", "SYNC_RUN_ITEM" ->
                    EXTERNAL_SYNC;
            default -> OTHER;
        };
    }
}
