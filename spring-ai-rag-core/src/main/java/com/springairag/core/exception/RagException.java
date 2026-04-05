package com.springairag.core.exception;

import com.springairag.api.enums.ErrorCode;

/**
 * Base RAG business exception with a typed {@link ErrorCode}.
 *
 * <p>All RAG-related business exceptions extend this class.
 * Subclasses only need to specify the {@link ErrorCode};
 * HTTP status is derived from the enum.
 *
 * <p>Example:
 * <pre>
 * throw new DocumentNotFoundException(42L);
 * throw new EmbeddingException(42L, "embedding model timeout");
 * </pre>
 */
public class RagException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * @param errorCode typed error code (must not be null)
     * @param message   user-friendly error description
     */
    public RagException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * @param errorCode typed error code
     * @param message   error description
     * @param cause     original exception
     */
    public RagException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the typed error code enum.
     */
    public ErrorCode getErrorCodeEnum() {
        return errorCode;
    }

    /**
     * Returns the error code name as a String (e.g., {@code "DOCUMENT_NOT_FOUND"}).
     * Kept for backward compatibility.
     */
    public String getErrorCode() {
        return errorCode.getCode();
    }

    /**
     * @deprecated use {@link #getErrorCodeEnum()} and {@link ErrorCode#getHttpStatus()}
     */
    @Deprecated
    public int getHttpStatus() {
        return errorCode.getHttpStatus();
    }
}
