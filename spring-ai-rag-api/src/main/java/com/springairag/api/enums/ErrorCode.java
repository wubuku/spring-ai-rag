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
    // Batch 873: the three below were emitted by GlobalExceptionHandler but were
    // never declared here, so the enum this file calls "a single source of truth"
    // was missing codes the API really returns.
    MISSING_HEADER(400, "Missing Required Header"),
    MISSING_PART(400, "Missing Required Part"),
    UNSUPPORTED_MEDIA_TYPE(400, "Unsupported Media Type"),
    INVALID_REQUEST_BODY(400, "Invalid Request Body"),
    VALIDATION_FAILED(400, "Validation Failed"),
    TYPE_MISMATCH(400, "Type Mismatch"),
    UNKNOWN_DOMAIN(400, "Unknown Domain"),
    RETRIEVAL_OPTIONS_NOT_ALLOWED(400, "Retrieval Options Not Allowed"),
    IDEMPOTENCY_KEY_INVALID(400, "Invalid Idempotency Key"),
    IDEMPOTENCY_REQUEST_TOO_LARGE(400, "Idempotency Request Too Large"),
    IDEMPOTENCY_REQUEST_METADATA_INVALID(400, "Invalid Idempotency Request Metadata"),
    MODEL_CAPABILITY_UNSUPPORTED(400, "Model Capability Unsupported"),
    MODEL_STREAMING_UNSUPPORTED(400, "Model Streaming Unsupported"),
    CHAT_AGENT_DISABLED(400, "Chat Agent Disabled"),
    DOMAIN_MODE_UNSUPPORTED(400, "Domain Chat Mode Unsupported"),
    EMPTY_PATCH(400, "Empty Document Patch"),
    UNKNOWN_DOCUMENT_FIELD(400, "Unknown Document Field"),

    // ==================== 401 Unauthorized ====================

    UNAUTHORIZED(401, "Unauthorized"),

    // ==================== 403 Forbidden ====================

    FORBIDDEN(403, "Forbidden"),
    COLLECTION_PURGE_FORBIDDEN(403, "Collection Purge Forbidden"),

    // ==================== 404 Not Found ====================

    NOT_FOUND(404, "Resource Not Found"),
    DOCUMENT_NOT_FOUND(404, "Document Not Found"),
    COLLECTION_NOT_FOUND(404, "Collection Not Found"),
    SESSION_NOT_FOUND(404, "Chat Session Not Found"),
    CHAT_TURN_NOT_FOUND(404, "Chat Turn Not Found"),

    // ==================== 405 Method Not Allowed ====================

    METHOD_NOT_ALLOWED(405, "Method Not Allowed"),

    // ==================== 409 Conflict ====================

    DUPLICATE_RESOURCE(409, "Duplicate Resource"),
    STRUCTURED_RECORD_CONFLICT(409, "Structured Record Conflict"),
    DOCUMENT_REVISION_CONFLICT(409, "Document Revision Conflict"),
    CONCURRENT_MODIFICATION(409, "Concurrent Modification"),
    SESSION_BUSY(409, "Chat Session Busy"),
    CHAT_SESSION_LEASE_LOST(409, "Chat Session Lease Lost"),
    CONCURRENT_EVALUATION_LIMIT(409, "Concurrent Evaluation Limit"),
    EXTERNAL_DOCUMENT_MANAGED(409, "Externally Managed Document"),
    DOCUMENT_DISABLED(409, "Document Disabled"),
    SYNC_RUN_LEASE_CONFLICT(409, "Sync Run Lease Conflict"),
    ACTIVE_SYNC_RUN_EXISTS(409, "Active Sync Run Exists"),
    SYNC_RUN_INVALID_STATE(409, "Invalid Sync Run State"),
    SYNC_RUN_PREVIEW_CONFLICT(409, "Sync Run Preview Conflict"),
    SYNC_RUN_DELETE_PROTECTION(409, "Sync Run Delete Protection"),
    SYNC_RUN_ITEM_CONFLICT(409, "Sync Run Item Conflict"),
    SYNC_RUN_INCOMPLETE(409, "Sync Run Incomplete"),
    DOCUMENT_NOT_EXTERNAL_MANAGED(409, "Document Is Not Externally Managed"),
    TARGET_EXTERNAL_IDENTITY_EXISTS(409, "Target External Identity Exists"),
    TARGET_EXTERNAL_IDENTITY_RETIRED(409, "Target External Identity Retired"),
    LEGACY_EXTERNAL_IDENTITY_REQUIRES_CLAIM(409, "Legacy External Identity Requires Claim"),
    EXTERNAL_IDENTITY_RELOCATED(409, "External Identity Relocated"),
    IDEMPOTENCY_KEY_REUSED(409, "Idempotency Key Reused"),
    IDEMPOTENCY_OPERATION_IN_PROGRESS(409, "Idempotency Operation In Progress"),
    ACTIVE_SYNC_RUN_CONFLICT(409, "Active Sync Run Conflict"),
    DERIVATION_REPAIR_CONFLICT(409, "Derivation Repair Conflict"),
    DERIVATION_REPAIR_EXPIRED(409, "Derivation Repair Expired"),
    VERSION_NOT_RESTORABLE(409, "Version Not Restorable"),
    RESTORE_NOT_ALLOWED(409, "Restore Not Allowed"),
    CREDENTIAL_NOT_CURRENT(409, "Credential Is Not Current"),
    CREDENTIAL_ROTATION_PENDING(409, "Credential Rotation Is Pending"),
    CREDENTIAL_ROTATION_NOT_PENDING(409, "Credential Rotation Is Not Pending"),
    CREDENTIAL_ROTATION_EXPIRED(409, "Credential Rotation Has Expired"),
    PRINCIPAL_NOT_ACTIVE(409, "API Principal Is Not Active"),
    POLICY_VERSION_CONFLICT(409, "API Principal Policy Version Conflict"),
    LAST_ADMIN_REQUIRED(409, "Last Administrator Is Required"),
    COLLECTION_PURGE_CONFLICT(409, "Collection Purge Conflict"),
    COLLECTION_PURGE_PREVIEW_EXPIRED(409, "Collection Purge Preview Expired"),
    COLLECTION_PURGE_CONFIRMATION_INVALID(409, "Collection Purge Confirmation Invalid"),
    COLLECTION_ALREADY_RETIRED(409, "Collection Already Retired"),
    ALERT_NOTIFICATION_DELIVERY_CONFLICT(
            409, "Alert Notification Delivery Conflict"),

    // ==================== 422 Unprocessable Entity ====================

    UNPROCESSABLE_ENTITY(422, "Unprocessable Entity"),
    CHAT_BUDGET_EXHAUSTED(422, "Chat Execution Budget Exhausted"),
    CHAT_CONTEXT_BUDGET_EXCEEDED(422, "Chat Context Budget Exceeded"),

    // ==================== 429 Too Many Requests ====================

    // Batch 873. This used to be RATE_LIMIT_EXCEEDED, which nothing in the
    // repository ever emitted — RateLimitFilter has always written
    // "TOO_MANY_REQUESTS" to the wire, and three test classes assert that exact
    // string as the value of `error`. So the catalog held one name for the 429
    // and the API spoke another. Renaming the constant is cheaper than adding a
    // second 429 under a name no client has ever seen, and it cannot break a
    // caller: the wire value never changes.
    TOO_MANY_REQUESTS(429, "Too Many Requests"),

    // ==================== 500 Internal Server Error ====================

    INTERNAL_ERROR(500, "Internal Server Error"),
    DATABASE_ERROR(500, "Database Error"),
    RETRIEVAL_FAILED(500, "Retrieval Failed"),
    EMBEDDING_FAILED(500, "Embedding Failed"),

    // ==================== 503 Service Unavailable ====================

    SERVICE_UNAVAILABLE(503, "Service Unavailable"),
    // Batch 873: see MISSING_HEADER above — emitted by the handler, absent here.
    POLICY_SERVICE_UNAVAILABLE(503, "API Principal Policy Service Unavailable"),
    // The third leg of the same family: a dependency the request needs is down,
    // so the API answers 503 naming *which* one. This is what
    // ApiKeyAuthFilter.sendServiceUnavailable has always written to the wire.
    CREDENTIAL_SERVICE_UNAVAILABLE(503, "API Credential Service Unavailable"),
    LLM_CIRCUIT_OPEN(503, "LLM Circuit Breaker Open"),
    LLM_UNAVAILABLE(503, "LLM Service Unavailable"),
    CHAT_HISTORY_PERSIST_FAILED(503, "Chat History Persistence Failed"),
    IDEMPOTENCY_DISABLED(503, "Chat Idempotency Disabled"),
    IDEMPOTENCY_RESPONSE_TOO_LARGE(503, "Idempotency Response Too Large"),
    IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID(503, "Invalid Idempotency Execution Snapshot"),
    IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID(503, "Invalid Idempotency Authorization Snapshot"),
    IDEMPOTENCY_ATTEMPTS_EXHAUSTED(503, "Idempotency Attempts Exhausted"),
    EMBEDDING_JOBS_DISABLED(503, "Embedding Jobs Disabled"),
    EVALUATION_SUITES_DISABLED(503, "Evaluation Suites Disabled"),
    SYNC_RUNS_DISABLED(503, "Document Sync Runs Disabled"),
    DOCUMENT_RELOCATION_DISABLED(503, "Document Relocation Disabled"),
    DERIVATION_REPAIR_DISABLED(503, "Derivation Repair Disabled"),
    RATE_LIMIT_STORE_UNAVAILABLE(503, "Rate Limit Store Unavailable"),
    API_KEY_PROVISIONING_IDEMPOTENCY_DISABLED(503, "API Key Provisioning Idempotency Disabled"),
    COLLECTION_PROVISIONING_IDEMPOTENCY_DISABLED(
            503, "Collection Provisioning Idempotency Disabled"),
    INTEGRATION_OBSERVABILITY_DISABLED(
            503, "Integration Observability Disabled"),
    COLLECTION_PURGE_DISABLED(503, "Collection Purge Disabled"),

    // ==================== 504 Gateway Timeout ====================

    GATEWAY_TIMEOUT(504, "Gateway Timeout"),
    CHAT_TIMEOUT(504, "Chat Timeout");

    private final int httpStatus;
    private final String title;

    /** Cached for {@link #byCodeOrNull(String)}; the enum is fixed at runtime. */
    private static final ErrorCode[] VALUES = values();

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

    /**
     * Looks a code up by its wire name, or {@code null} when there is none.
     *
     * <p>Batch 873. {@code GlobalExceptionHandler} builds bodies from string
     * literals, so it needs a name-to-code lookup to stop hardcoding titles and
     * problem-type URIs. It deliberately does <b>not</b> throw: this is called
     * from inside exception handling, where a thrown
     * {@link IllegalArgumentException} would replace a 400 with a 500 and lose
     * the reason the caller was about to be told. A code the enum does not know
     * is a gap to be reported, not an error to be raised here.
     */
    public static ErrorCode byCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        for (ErrorCode candidate : VALUES) {
            if (candidate.name().equals(code)) {
                return candidate;
            }
        }
        return null;
    }
}
