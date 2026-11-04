package com.springairag.core.exception;

import com.springairag.api.enums.ErrorCode;

/**
 * 文档内容无法抽取或格式不可信。
 *
 * <p>用于明确区分“客户端文件问题”和“服务端故障”：损坏、加密、无文字层、
 * 编码不支持、伪造扩展名都返回 422（{@code DOCUMENT_EXTRACTION_FAILED}），
 * 而不是 500 或静默成功。
 */
public class DocumentExtractionException extends RagException {

    /** 稳定机器可读原因，便于测试与前端分支。 */
    public enum Reason {
        EMPTY_FILE,
        FILE_TYPE_MISMATCH,
        UNSUPPORTED_FORMAT,
        TEXT_ENCODING_UNSUPPORTED,
        INVALID_DOCX,
        INVALID_PDF,
        ENCRYPTED_DOCUMENT,
        NO_EXTRACTABLE_TEXT,
        TOO_MANY_PAGES,
        OUTPUT_TOO_LARGE,
        CONVERSION_FAILED
    }

    private final Reason reason;

    public DocumentExtractionException(Reason reason, String message) {
        super(ErrorCode.DOCUMENT_EXTRACTION_FAILED, message);
        this.reason = reason;
    }

    public DocumentExtractionException(Reason reason, String message, Throwable cause) {
        super(ErrorCode.DOCUMENT_EXTRACTION_FAILED, message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
