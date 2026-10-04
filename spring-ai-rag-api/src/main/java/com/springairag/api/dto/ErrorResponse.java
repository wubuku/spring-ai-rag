package com.springairag.api.dto;

import com.springairag.api.enums.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Objects;

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
 *   <li>{@code error} — Machine code for the problem, e.g. "VALIDATION_FAILED"</li>
 *   <li>{@code message} — Human-readable message (same as detail, backward-compatible alias)</li>
 *   <li>{@code timestamp} — Error occurrence time</li>
 *   <li>{@code path} — Request path (same as instance, backward-compatible alias)</li>
 * </ul>
 *
 * <p>Note on {@code error} vs {@code title}. An earlier version of this Javadoc
 * described {@code error} as "same as title". It is not, and treating it that way
 * is what put a human phrase into a machine field for eighteen endpoints — see
 * {@link Builder#title(String)}. {@code error} carries the code; {@code title}
 * carries the summary, taken from {@link ErrorCode} when the code is one the
 * catalog knows.
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

    /** Machine code for the problem (e.g. "VALIDATION_FAILED") */
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

    /**
     * Simple error message factory method.
     *
     * <p>Batch 873. This used to set {@code .title("Bad Request")}, and because
     * {@link Builder#title(String)} also writes {@code error}, the eighteen
     * endpoints that call this one shipped {@code "Bad Request"} in the
     * {@code error} field — a human phrase where the rest of the API puts a
     * machine code. A client that switches on {@code body.error} therefore met
     * two vocabularies: {@code UNAUTHORIZED} from the filters, {@code Bad
     * Request} here. The field's own Javadoc calls it an "Error code
     * identifier" and gives {@code VALIDATION_FAILED} as the example, and
     * {@code GlobalExceptionHandlerTest} already asserts {@code error ==
     * "BAD_REQUEST"} alongside {@code title == "Bad Request"}. So this delegates
     * instead of hand-assembling, and the shape is now defined once.
     */
    public static ErrorResponse of(String detail) {
        return of(ErrorCode.BAD_REQUEST, detail);
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

    /**
     * Creates an ErrorResponse that also names the request it is about.
     *
     * <p>Batch 873. The two-argument {@link #of(ErrorCode, String)} leaves
     * {@code instance} (and its {@code path} alias) null, and that is fine for
     * callers with no request in scope. It is not fine for a controller: a
     * problem+json body that says nothing about <em>which</em> request failed is
     * harder to triage than one that does, and the one caller that had the URI
     * in hand was reaching past the factory to do it.
     */
    public static ErrorResponse of(ErrorCode code, String detail, String instance) {
        return builder()
                .error(code.getCode())
                .title(code.getTitle())
                .status(code.getHttpStatus())
                .type(code.getProblemTypeUri())
                .detail(detail)
                .instance(instance)
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

        /**
         * Sets the human-readable summary.
         *
         * <p>Batch 873. This used to write {@code error} as well, on the theory
         * that the two fields were aliases — which the class Javadoc still
         * claimed. They are not: every body in this repository puts a
         * {@code SNAKE_CASE} code in {@code error} and a phrase in
         * {@code title}, and {@code GlobalExceptionHandler} had already worked
         * around the clobber by calling {@code setTitle} after {@code build()}.
         * Because {@link #of(ErrorCode, String)} lists {@code .error(...)} before
         * {@code .title(...)}, the side effect silently replaced the code it had
         * just written, so a body built through the factory shipped
         * {@code "Bad Request"} in its {@code error} field. Eighteen endpoints
         * reach these factories.
         */
        public Builder title(String title) {
            response.title = title;
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

        /**
         * Sets the machine code and auto-generates the type URI.
         *
         * <p>Batch 873. This used to write {@code title} as well, which is why
         * {@code build()} had nothing left to derive and every code-only body
         * ended up with its code echoed into the summary field. The title is now
         * resolved once, in {@link #build()}, from the catalog.
         */
        public Builder error(String error) {
            response.error = error;
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
                // Batch 873. This used to copy the code verbatim, so every body
                // that named its code and nothing else — which is nine of the
                // thirteen hand-assembled sites — shipped "UNAUTHORIZED" in the
                // field RFC 7807 defines as a short human-readable summary. The
                // catalog already holds that summary, and the enum's own lookup
                // is the only honest way to get it: an unknown code keeps the
                // old behaviour rather than being invented a title for.
                ErrorCode code = ErrorCode.byCodeOrNull(response.error);
                response.title = code != null ? code.getTitle() : response.error;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ErrorResponse that = (ErrorResponse) o;
        // timestamp intentionally excluded — it changes on every construction
        return Objects.equals(type, that.type) &&
                Objects.equals(title, that.title) &&
                Objects.equals(status, that.status) &&
                Objects.equals(detail, that.detail) &&
                Objects.equals(instance, that.instance) &&
                Objects.equals(error, that.error) &&
                Objects.equals(message, that.message) &&
                Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        // timestamp intentionally excluded
        return Objects.hash(type, title, status, detail, instance, error, message, path);
    }

    @Override
    public String toString() {
        return "ErrorResponse{" +
                "type='" + type + '\'' +
                ", title='" + title + '\'' +
                ", status=" + status +
                ", detail='" + detail + '\'' +
                ", instance='" + instance + '\'' +
                ", error='" + error + '\'' +
                ", message='" + message + '\'' +
                ", path='" + path + '\'' +
                ", timestamp='" + timestamp + '\'' +
                '}';
    }
}
