package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.DatabaseFailureClassifier;
import com.springairag.core.exception.RagException;
import com.springairag.core.exception.ChatTurnInProgressException;
import com.springairag.core.filter.RequestTraceFilter;
import com.springairag.core.logging.ErrorMessageSanitizer;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.web.ApiKeyRotationHttpPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Global exception handler — RFC 7807 Problem Detail.
 *
 * <p>All exceptions return a unified {@link ErrorResponse} format, following RFC 7807 standard fields:
 * type / title / status / detail / instance.
 *
 * <p>Response Content-Type is set to {@code application/problem+json} (RFC 7807 §3).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** RFC 7807 standard Content-Type */
    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    // ==================== 400 Bad Request ====================

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e,
                                                            HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                "Missing required parameter: " + e.getParameterName(), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e,
                                                             HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "MISSING_HEADER",
                "Missing required header: " + e.getHeaderName(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e,
                                                                      HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type not supported: " + e.getMessage(), request);
    }

    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(org.springframework.web.multipart.support.MissingServletRequestPartException e,
                                                           HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "MISSING_PART",
                "Missing required file part: " + e.getRequestPartName(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException e,
                                                                 HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY",
                "Invalid request body, please check JSON format", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e,
                                                          HttpServletRequest request) {
        List<String> violations = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        String detail = String.join("; ", violations);

        // Batch 873: this built its own body — a third construction site, with
        // the same non-RFC-7807 shape `buildResponse` had. The handler below
        // already reported this exact code through `buildResponse`, so the two
        // were doing one job twice and could disagree.
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e,
                                                                   HttpServletRequest request) {
        List<String> violations = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .toList();
        String detail = String.join("; ", violations);

        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(
            HandlerMethodValidationException e,
            HttpServletRequest request) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Validation failure",
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                            HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "TYPE_MISMATCH",
                "Parameter '" + e.getName() + "' has wrong type", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e,
                                                          HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                e.getMessage() != null ? e.getMessage() : "Invalid argument", request);
    }

    @ExceptionHandler(com.springairag.documents.chunk.ChunkingException.class)
    public ResponseEntity<ErrorResponse> handleChunking(
            com.springairag.documents.chunk.ChunkingException error, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, error.getCode(), error.getCode(), request);
    }

    // ==================== 404 Not Found ====================

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoHandlerFoundException e,
                                                        HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Endpoint not found: " + e.getRequestURL(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(NoResourceFoundException e,
                                                                HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Resource not found: " + e.getResourcePath(), request);
    }

    // ==================== 405 Method Not Allowed ====================

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                                  HttpServletRequest request) {
        return buildResponse(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "HTTP method not supported: " + e.getMethod(), request);
    }

    // ==================== 409 Conflict ====================

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(
            OptimisticLockingFailureException e,
            HttpServletRequest request) {
        ErrorCode code = ErrorCode.CONCURRENT_MODIFICATION;
        log.warn("Optimistic locking conflict: {}", e.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .error(code.getCode())
                .status(code.getHttpStatus())
                .type(code.getProblemTypeUri())
                .detail("Resource changed concurrently; refresh its revision and retry")
                .instance(request.getRequestURI())
                .build();
        body.setTitle(code.getTitle());
        body.setTraceId(requestTraceId(request));
        body.setFailureDetails(com.springairag.core.resilience.UpstreamFailureClassifier.classify(e));
        ResponseEntity.BodyBuilder builder = ApiKeyRotationHttpPolicy.apply(
                        ResponseEntity.status(code.getHttpStatus()),
                        request)
                .contentType(PROBLEM_JSON);
        return builder.body(body);
    }

    // ==================== 500 Internal Server Error ====================

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException e,
                                                          HttpServletRequest request) {
        return buildDatabaseFailureResponse(e, request);
    }

    /**
     * Spring's transaction manager wraps a failure to borrow a connection as a
     * {@link org.springframework.transaction.CannotCreateTransactionException},
     * which is a {@code TransactionException}, <em>not</em> a
     * {@code DataAccessException}. Pool exhaustion therefore bypassed the
     * data-access branch entirely and was reported as an internal 500
     * ("Could not open JPA EntityManager for transaction"). Routing it through
     * the same classifier gives saturation the same 503 contract as any other
     * unavailable database.
     */
    @ExceptionHandler(CannotCreateTransactionException.class)
    public ResponseEntity<ErrorResponse> handleCannotCreateTransaction(
            CannotCreateTransactionException e, HttpServletRequest request) {
        return buildDatabaseFailureResponse(e, request);
    }

    private ResponseEntity<ErrorResponse> buildDatabaseFailureResponse(
            Throwable error, HttpServletRequest request) {
        // A database that is unreachable or has no free pool connection is a
        // dependency outage, not a bug in this request. Answering 500 here made
        // clients stop retrying exactly when retrying was the correct action,
        // and made saturation look like an internal error in the metrics.
        DatabaseFailureClassifier.Kind kind = DatabaseFailureClassifier.classify(error);
        switch (kind) {
            case UNAVAILABLE -> {
                log.warn("Database unavailable: {}", ErrorMessageSanitizer.sanitize(error.getMessage()));
                return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                        "Database temporarily unavailable; retry later", request, 5);
            }
            case TIMEOUT -> {
                log.warn("Database query timed out: {}", ErrorMessageSanitizer.sanitize(error.getMessage()));
                return buildResponse(HttpStatus.GATEWAY_TIMEOUT, "DATABASE_TIMEOUT",
                        "Database query timed out", request, 1);
            }
            default -> {
                log.error("Database operation failed: {}", error.getMessage(), error);
                return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_ERROR",
                        "Database operation failed", request);
            }
        }
    }

    // ==================== Multipart limits / upload storage ====================

    /**
     * A body larger than {@code spring.servlet.multipart.max-file-size} or
     * {@code max-request-size}; the container rejects it while parsing, before
     * any controller sees the file. 413 is the status clients already retry for
     * payload limits, and it must not be reported as an internal error.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException e,
                                                             HttpServletRequest request) {
        log.warn("Upload rejected by multipart size limit: {}", e.getMessage());
        return buildResponse(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "Uploaded payload exceeds the configured multipart size limit", request);
    }

    /**
     * Any other multipart parse failure.
     *
     * <p>Two very different causes arrive here: a malformed request (client
     * error) and a temp directory that is full or read-only (server storage
     * outage). Reporting both as 500 hid the second from operators, and
     * retrying could never fix it.</p>
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException e,
                                                         HttpServletRequest request) {
        if (isUploadStorageFailure(e)) {
            log.error("Multipart temp storage failed: {}", e.getMessage(), e);
            return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_STORAGE_UNAVAILABLE",
                    "Upload storage is temporarily unavailable; retry later", request, 5);
        }
        log.warn("Malformed multipart request: {}", e.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "Malformed multipart request", request);
    }

    /**
     * Walks the cause chain for the IO failures that mean the temp store cannot
     * accept bytes. Message matching is confined to {@link IOException}s, where
     * these strings come from the JDK/OS; anything else stays a client 400.
     */
    private static boolean isUploadStorageFailure(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (current instanceof IOException) {
                String message = current.getMessage();
                if (message != null) {
                    String normalized = message.toLowerCase(Locale.ROOT);
                    if (normalized.contains("no space left")
                            || normalized.contains("read-only")
                            || normalized.contains("permission denied")
                            || normalized.contains("not a directory")
                            || normalized.contains("too many open files")
                            || normalized.contains("disk quota")
                            || normalized.contains("file system is full")) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }

    @ExceptionHandler(ApiCapabilitySupport.InvalidPersistedCapabilitiesException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPersistedCapabilities(
            ApiCapabilitySupport.InvalidPersistedCapabilitiesException e,
            HttpServletRequest request) {
        log.error("Invalid persisted API capability policy: {}", e.getMessage());
        return buildResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                "POLICY_SERVICE_UNAVAILABLE",
                "API principal policy is unavailable",
                request);
    }

    @ExceptionHandler(RagException.class)
    public ResponseEntity<ErrorResponse> handleRagException(RagException e,
                                                            HttpServletRequest request) {
        var code = e.getErrorCodeEnum();
        log.warn("RAG business error: [{}] {}", code.getCode(), e.getMessage());
        // 5xx 的 detail 可能来自内部组件（SQL、Provider、转换器），不能原样外泄；
        // 4xx 的 detail 是调用方改请求的依据，保持原样。
        String detail = code.getHttpStatus() >= 500
                ? ErrorMessageSanitizer.sanitize(e.getMessage())
                : e.getMessage();
        ErrorResponse body = ErrorResponse.builder()
                .error(code.getCode())
                .status(code.getHttpStatus())
                .type(code.getProblemTypeUri())
                .detail(detail)
                .instance(request.getRequestURI())
                .build();
        // Set human-readable title without triggering the builder's title() side effect
        body.setTitle(code.getTitle());
        body.setTraceId(requestTraceId(request));
        var failureDetails = com.springairag.core.resilience.UpstreamFailureClassifier.classify(e);
        body.setFailureDetails(failureDetails);
        ResponseEntity.BodyBuilder builder = ApiKeyRotationHttpPolicy.apply(
                        ResponseEntity.status(code.getHttpStatus()),
                        request)
                .contentType(PROBLEM_JSON)
                ;
        if (e instanceof ChatTurnInProgressException inProgress) {
            builder.header(HttpHeaders.RETRY_AFTER,
                    Integer.toString(inProgress.retryAfterSeconds()));
        } else if (e.getRetryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER,
                    Integer.toString(e.getRetryAfterSeconds()));
        } else if (failureDetails.retryAfterMs() != null) {
            // 幂等重放的上游失败也要透传上游给的 Retry-After。
            builder.header(HttpHeaders.RETRY_AFTER, Integer.toString(
                    Math.max(1, (int) Math.ceil(failureDetails.retryAfterMs() / 1000.0))));
        }
        return builder.body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e,
                                                         HttpServletRequest request) {
        // Exception messages may contain internal paths, SQL errors or fully-qualified
        // class names; desensitize before returning to user. Batch 952 added the
        // internal-type/stack-frame scrub: masking secrets alone still let
        // `com.springairag.core.foo.Bar` and `at com.foo.Bar.m(Bar.java:42)` through.
        String rawMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
        String safeMessage = ErrorMessageSanitizer.sanitize(rawMessage);
        var details = com.springairag.core.resilience.UpstreamFailureClassifier.classify(e);
        if ("UPSTREAM".equals(details.origin())) {
            // 上游失败不是调用方请求错误：继续回 500 会让客户端把“provider 挂了”
            // 当成“服务端 bug”，也无法按 Failure-Class 做有界重试。这里改成
            // 503/504/429 三类稳定语义，并保留分类原因与 upstreamStatus，
            // 让调用方能区分“换 provider / 退避重试 / 裁剪上下文”。
            ErrorCode code = com.springairag.core.resilience.UpstreamFailureClassifier.errorCode(details);
            HttpStatus status = HttpStatus.valueOf(code.getHttpStatus());
            safeMessage = ErrorMessageSanitizer.sanitize(
                    "Model provider request failed: " + details.reason()
                            + (details.upstreamStatus() != null
                            ? " (upstream HTTP " + details.upstreamStatus() + ")"
                            : "")
                            + (details.upstreamMessage() != null && !details.upstreamMessage().isBlank()
                            ? ": " + details.upstreamMessage()
                            : ""));
            Integer retryAfterSeconds = details.retryAfterMs() == null ? null
                    : Math.max(1, (int) Math.ceil(details.retryAfterMs() / 1000.0));
            log.error("Upstream request failed: status={}, reason={}, upstreamStatus={}, retryable={}, upstreamMessage={}",
                    status.value(), details.reason(), details.upstreamStatus(), details.retryable(),
                    details.upstreamMessage());
            ResponseEntity<ErrorResponse> response = buildResponse(
                    status, code.name(), safeMessage, request, retryAfterSeconds);
            response.getBody().setFailureDetails(details);
            return response;
        }
        // Batch 920. The status is named in the line rather than left implicit.
        // It used to read `Request failed: {message}` and nothing else — and for
        // a provider that answers 401, the *message* begins with `401 - {…}`,
        // because that is what Spring AI puts in it. The line therefore read
        // `Request failed: 401 - {"code":30014…}`, which is indistinguishable
        // from a handler that knew the status was 401 and answered 500
        // afterwards. It did not know: this branch always answers 500, and the
        // number in front was never the status. Chasing that reading cost a full
        // batch of misdiagnosis before it was pinned by a test.
        log.error("Request failed: status={}, detail={}", 500, safeMessage, e);
        ResponseEntity<ErrorResponse> response = buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", safeMessage, request);
        response.getBody().setFailureDetails(details);
        return response;
    }

    // ==================== Helper ====================

    /**
     * Build RFC 7807 Problem Detail response, Content-Type set to application/problem+json.
     */
    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String error,
                                                         String detail, HttpServletRequest request) {
        return buildResponse(status, error, detail, request, null);
    }

    /**
     * @param retryAfterSeconds when non-null, advertises the dependency-recovery
     *     window on retryable failures; {@code Retry-After} is what lets a
     *     saturated client back off instead of hammering the pool.
     */
    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String error,
                                                         String detail, HttpServletRequest request,
                                                         Integer retryAfterSeconds) {
        ErrorResponse body = ErrorResponse.builder()
                .error(error)
                .status(status.value())
                .message(detail)
                .path(request.getRequestURI())
                .build();

        // Batch 873. This method declared `application/problem+json` while
        // emitting a body that was not RFC 7807: no type, no title, no detail,
        // no instance. Sixteen handlers reached it, so the same exception class
        // produced two different shapes depending on which branch caught it —
        // and a client reading `detail`, as RFC 7807 says to, found nothing.
        //
        // Titles and problem-type URIs are now read from `ErrorCode` rather than
        // written here, which is what stops the two shapes drifting apart again.
        // `message` and `path` deliberately stay: they are what the WebUI falls
        // back to when `detail` is missing, and removing them would break a
        // published contract for no gain.
        body.setDetail(detail);
        body.setInstance(request.getRequestURI());
        body.setTraceId(requestTraceId(request));
        ErrorCode code = ErrorCode.byCodeOrNull(error);
        if (code != null) {
            body.setType(code.getProblemTypeUri());
            body.setTitle(code.getTitle());
        }

        ResponseEntity.BodyBuilder builder = ApiKeyRotationHttpPolicy.apply(
                ResponseEntity.status(status),
                request);
        if (retryAfterSeconds != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        }
        return builder
                .contentType(PROBLEM_JSON)
                .body(body);
    }

    private String requestTraceId(HttpServletRequest request) {
        Object value = request == null ? null
                : request.getAttribute(RequestTraceFilter.REQUEST_TRACE_ID_ATTRIBUTE);
        return value == null ? null : String.valueOf(value);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurityException(SecurityException e,
                                                                 HttpServletRequest request) {
        return buildResponse(HttpStatus.FORBIDDEN, "FORBIDDEN",
                e.getMessage() != null ? e.getMessage() : "Access denied", request);
    }

}
