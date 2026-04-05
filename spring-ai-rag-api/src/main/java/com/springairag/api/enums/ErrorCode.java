package com.springairag.api.enums;

/**
 * Unified API Error Code enumeration — RFC 7807 Problem Detail compatible.
 *
 * <p>All application error codes are defined here as a single source of truth.
 * Each code carries its HTTP status code and human-readable title.
 *
 * <p>Code naming convention: UPPER_SNAKE_CASE matching the RFC 7807 title in
 * kebab-case (e.g., {@code DOCUMENT_NOT_FOUND} → {@code document-not-found}).
 *
 * <p>This enum lives in the API module and intentionally does NOT depend on
 * Spring Web ({@code org.springframework.http.HttpStatus}) so the API DTO layer
 * remains framework-agnostic.
 */
public enum ErrorCode {

    // ==================== 400 Bad Request ====================

    BAD_REQUEST(400, "Bad Request"),
    MISSING_PARAMETER(400, "Missing Required Parameter"),
    INVALID_REQUEST_BODY(400, "Invalid Request Body"),
    VALIDATION_FAILED(400, "Validation Failed"),
    TYPE_MISMATCH(400, "Type Mismatch"),

    // ==================== 401 Unauthorized ====================

    UNAUTHORIZED(401, "Unauthorized"),

    // ==================== 403 Forbidden ====================

    FORBIDDEN(403, "Forbidden"),

    // ==================== 404 Not Found ====================

    NOT_FOUND(404, "Resource Not Found"),
    DOCUMENT_NOT_FOUND(404, "Document Not Found"),
    COLLECTION_NOT_FOUND(404, "Collection Not Found"),

    // ==================== 405 Method Not Allowed ====================

    METHOD_NOT_ALLOWED(405, "Method Not Allowed"),

    // ==================== 409 Conflict ====================

    DUPLICATE_RESOURCE(409, "Duplicate Resource"),

    // ==================== 422 Unprocessable Entity ====================

    UNPROCESSABLE_ENTITY(422, "Unprocessable Entity"),

    // ==================== 429 Too Many Requests ====================

    RATE_LIMIT_EXCEEDED(429, "Rate Limit Exceeded"),

    // ==================== 500 Internal Server Error ====================

    INTERNAL_ERROR(500, "Internal Server Error"),
    DATABASE_ERROR(500, "Database Error"),
    RETRIEVAL_FAILED(500, "Retrieval Failed"),
    EMBEDDING_FAILED(500, "Embedding Failed"),

    // ==================== 503 Service Unavailable ====================

    SERVICE_UNAVAILABLE(503, "Service Unavailable"),
    LLM_CIRCUIT_OPEN(503, "LLM Circuit Breaker Open"),
    LLM_UNAVAILABLE(503, "LLM Service Unavailable"),

    // ==================== 504 Gateway Timeout ====================

    GATEWAY_TIMEOUT(504, "Gateway Timeout");

    private final int httpStatus;
    private final String title;

    ErrorCode(int httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    /**
     * Returns the HTTP status code for this error code.
     */
    public int getHttpStatus() {
        return httpStatus;
    }

    /**
     * Returns the RFC 7807 title for this error code.
     */
    public String getTitle() {
        return title;
    }

    /**
     * Returns the RFC 7807 problem type URI for this error code.
     * Format: {@code https://springairag.dev/problems/{kebab-case-name}}
     */
    public String getProblemTypeUri() {
        return "https://springairag.dev/problems/" + this.name().toLowerCase().replace('_', '-');
    }

    /**
     * Returns the error code name (e.g., {@code DOCUMENT_NOT_FOUND}).
     */
    public String getCode() {
        return this.name();
    }
}
