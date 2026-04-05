package com.springairag.core.exception;

import com.springairag.api.enums.ErrorCode;

/**
 * Retrieval exception — thrown when hybrid search, vector search, or full-text
 * search fails. GlobalExceptionHandler returns the HTTP status from the enum.
 */
public class RetrievalException extends RagException {

    public RetrievalException(String query, String detail) {
        super(ErrorCode.RETRIEVAL_FAILED, "Retrieval failed: query=" + query + ", " + detail);
    }

    public RetrievalException(String message) {
        super(ErrorCode.RETRIEVAL_FAILED, message);
    }

    public RetrievalException(String message, Throwable cause) {
        super(ErrorCode.RETRIEVAL_FAILED, message, cause);
    }
}
