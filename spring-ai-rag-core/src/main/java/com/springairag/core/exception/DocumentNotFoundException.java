package com.springairag.core.exception;

import com.springairag.api.enums.ErrorCode;

/**
 * Document not found exception — thrown when a requested document ID does not exist.
 * GlobalExceptionHandler returns 404.
 */
public class DocumentNotFoundException extends RagException {

    public DocumentNotFoundException(Long documentId) {
        super(ErrorCode.DOCUMENT_NOT_FOUND, "Document not found: id=" + documentId);
    }

    public DocumentNotFoundException(String message) {
        super(ErrorCode.DOCUMENT_NOT_FOUND, message);
    }
}
