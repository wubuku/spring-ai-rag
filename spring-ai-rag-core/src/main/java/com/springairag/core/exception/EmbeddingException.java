package com.springairag.core.exception;

import com.springairag.api.enums.ErrorCode;

/**
 * Embedding exception — thrown when embedding model invocation fails or vector store
 * operations fail. GlobalExceptionHandler returns 500.
 */
public class EmbeddingException extends RagException {

    public EmbeddingException(Long documentId, String detail) {
        super(ErrorCode.EMBEDDING_FAILED,
                "Embedding failed: documentId=" + documentId + ", " + detail);
    }

    public EmbeddingException(String message) {
        super(ErrorCode.EMBEDDING_FAILED, message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(ErrorCode.EMBEDDING_FAILED, message, cause);
    }
}
