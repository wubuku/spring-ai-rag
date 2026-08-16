package com.springairag.core.exception;

import com.springairag.api.enums.ErrorCode;

/**
 * Raised when an external document revision cannot be applied safely.
 */
public class DocumentRevisionConflictException extends RagException {

    public DocumentRevisionConflictException(String message) {
        super(ErrorCode.DOCUMENT_REVISION_CONFLICT, message);
    }

    public DocumentRevisionConflictException(String message, Throwable cause) {
        super(ErrorCode.DOCUMENT_REVISION_CONFLICT, message, cause);
    }
}
