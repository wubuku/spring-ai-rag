package com.springairag.core.exception;

import com.springairag.api.enums.ErrorCode;

/**
 * Raised when a structured-record identity cannot be written consistently.
 */
public class StructuredRecordConflictException extends RagException {

    public StructuredRecordConflictException(String message) {
        super(ErrorCode.STRUCTURED_RECORD_CONFLICT, message);
    }

    public StructuredRecordConflictException(String message, Throwable cause) {
        super(ErrorCode.STRUCTURED_RECORD_CONFLICT, message, cause);
    }
}
