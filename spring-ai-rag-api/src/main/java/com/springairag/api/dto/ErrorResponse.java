package com.springairag.api.dto;

import com.springairag.api.enums.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Unified error response DTO — RFC 7807 Problem Detail compatible
 *
 * <p>All API errors return this format. Conforms to RFC 7807 (Problem Details for HTTP APIs):
 * <ul>
 *   <li>{@code type} — URI identifying the problem type (e.g. "https://springairag.dev/problems/validation-failed")</li>
 *   <li>{@code title} — Short description of the problem type (RFC title)</li>
 *   <li>{@code status} — HTTP status code</li>
 *   <li>{@code detail} — Specific error message (RFC detail)</li>
 *   <li>{@code instance} — Request path where the problem occurred (RFC instance)</li>
 * </ul>
 *
 * <p>Also preserves backward-compatible fields:
 * <ul>
 *   <li>{@code error} — Error code (same as title, backward-compatible alias)</li>
 *   <li>{@code message} — Human-readable message (same as detail, backward-compatible alias)</li>
 *   <li>{@code timestamp} — Error occurrence time</li>
 *   <li>{@code path} — Request path (same as instance, backward-compatible alias)</li>
 * </ul>
 */
@Schema(description = "RFC 7807 Problem Detail error response — all API errors use this format")
public class ErrorResponse {

    /** RFC 7807: problem type URI */
    @Schema(description = "URI identifying the problem type (e.g. https://springairag.dev/problems/validation-failed)")
    private String type;

    /** RFC 7807: problem title (alias for 'error' field, backward-compatible) */
    @Schema(description = "Short description of the problem type", example = "Validation Failed")
    private String title;

    /** RFC 7807: HTTP status code */
    @Schema(description = "HTTP status code", example = "400")
    private Integer status;

    /** RFC 7807: detailed description (alias for 'message' field, backward-compatible) */
    @Schema(description = "Specific error message describing what went wrong", example = "Query must not be blank")
    private String detail;

    /** RFC 7807: problem instance URI (alias for 'path' field, backward-compatible) */
    @Schema(description = "Request path where the problem occurred", example = "/api/v1/rag/chat/ask")
    private String instance;

    /** Error code (same as title, backward-compatible) */
    @Schema(description = "Error code identifier", example = "VALIDATION_FAILED")
    private String error;

    /** Human-readable error message (same as detail, backward-compatible) */
    @Schema(description = "Human-readable error message", example = "Query must not be blank")
    private String message;

    /** Error occurrence time (ISO-8601) */
    @Schema(description = "ISO-8601 timestamp when the error occurred", example = "2026-04-12T07:20:00Z")
    private String timestamp;

    /** Optional: request path (same as instance, backward-compatible) */
    @Schema(description = "Request path where the error occurred", example = "/api/v1/rag/chat/ask")
    private String path;

    /** Default problem type URI prefix */
    private static final String PROBLEM_TYPE_PREFIX = "https://springairag.dev/problems/";

    public ErrorResponse() {
        this.timestamp = Instant.now().toString();
    }

    public ErrorResponse(String error, String message) {
        this.error = error;
        this.title = error;
        this.message = message;
        this.detail = message;
        this.timestamp = Instant.now().toString();
    }

    public ErrorResponse(String error, String message, String path) {
        this.error = error;
        this.title = error;
        this.message = message;
        this.detail = message;
        this.path = path;
        this.instance = path;
        this.timestamp = Instant.now().toString();
    }

    // ==================== Builder ====================

    /** Simple error message factory method */
    public static ErrorResponse of(String detail) {
        return builder()
                .detail(detail)
                .title("Bad Request")
                .status(400)
                .type(PROBLEM_TYPE_PREFIX + "bad-request")
                .build();
    }

    /**
     * Creates an ErrorResponse from a typed {@link ErrorCode} enum.
     *
     * @param code   the standardized error code (determines status, title, type)
     * @param detail human-readable error detail message
     */
    public static ErrorResponse of(ErrorCode code, String detail) {
        return builder()
                .error(code.getCode())
                .title(code.getTitle())
                .status(code.getHttpStatus())
                .type(code.getProblemTypeUri())
                .detail(detail)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ErrorResponse response = new ErrorResponse();

        public Builder type(String type) {
            response.type = type;
            return this;
        }

        public Builder title(String title) {
            response.title = title;
            response.error = title;
            return this;
        }

        public Builder status(int status) {
            response.status = status;
            return this;
        }

        public Builder detail(String detail) {
            response.detail = detail;
            response.message = detail;
            return this;
        }

        public Builder instance(String instance) {
            response.instance = instance;
            response.path = instance;
            return this;
        }

        /** Sets the error code and auto-generates the type URI */
        public Builder error(String error) {
            response.error = error;
            response.title = error;
            response.type = PROBLEM_TYPE_PREFIX + error.toLowerCase().replace('_', '-');
            return this;
        }

        public Builder message(String message) {
            response.message = message;
            response.detail = message;
            return this;
        }

        public Builder path(String path) {
            response.path = path;
            response.instance = path;
            return this;
        }

        public ErrorResponse build() {
            // Ensure type is not null
            if (response.type == null && response.error != null) {
                response.type = PROBLEM_TYPE_PREFIX + response.error.toLowerCase().replace('_', '-');
            }
            // Ensure title/detail are in sync
            if (response.title == null && response.error != null) {
                response.title = response.error;
            }
            if (response.detail == null && response.message != null) {
                response.detail = response.message;
            }
            if (response.instance == null && response.path != null) {
                response.instance = response.path;
            }
            return response;
        }
    }

    // ==================== Getters ====================

    public String getType() { return type; }
    public String getTitle() { return title; }
    public Integer getStatus() { return status; }
    public String getDetail() { return detail; }
    public String getInstance() { return instance; }
    public String getError() { return error; }
    public String getMessage() { return message; }
    public String getTimestamp() { return timestamp; }
    public String getPath() { return path; }

    public void setType(String type) { this.type = type; }
    public void setTitle(String title) { this.title = title; }
    public void setStatus(Integer status) { this.status = status; }
    public void setDetail(String detail) { this.detail = detail; }
    public void setInstance(String instance) { this.instance = instance; }
    public void setError(String error) { this.error = error; }
    public void setMessage(String message) { this.message = message; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public void setPath(String path) { this.path = path; }
}
