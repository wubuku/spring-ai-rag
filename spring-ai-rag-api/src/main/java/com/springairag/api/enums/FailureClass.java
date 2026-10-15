package com.springairag.api.enums;

import java.util.Locale;

/**
 * Stable client-facing classification for API failures.
 *
 * <p>The classification is deliberately coarser than {@link ErrorCode}. Clients can
 * use it to choose retry, credential, conflict, or request-correction behavior without
 * depending on every individual server error code.</p>
 */
public enum FailureClass {
    CLIENT_REQUEST,
    AUTHENTICATION,
    AUTHORIZATION,
    NOT_FOUND,
    CONFLICT,
    RATE_LIMITED,
    DEPENDENCY_UNAVAILABLE,
    TIMEOUT,
    INTERNAL;

    /**
     * Classifies a native HTTP error without inspecting provider error text.
     */
    public static FailureClass from(Integer status, String errorCode) {
        String normalized = errorCode == null
                ? ""
                : errorCode.trim().toUpperCase(Locale.ROOT);
        if (status != null) {
            if (status == 408 || status == 504) {
                return TIMEOUT;
            }
            if (status == 429) {
                return RATE_LIMITED;
            }
            if (status == 401) {
                return AUTHENTICATION;
            }
            if (status == 403) {
                return AUTHORIZATION;
            }
            if (status == 404) {
                return NOT_FOUND;
            }
            if (status == 409) {
                return CONFLICT;
            }
            if (status == 502 || status == 503) {
                return DEPENDENCY_UNAVAILABLE;
            }
            if (status >= 400 && status < 500) {
                return CLIENT_REQUEST;
            }
        }
        if (normalized.contains("TIMEOUT")) {
            return TIMEOUT;
        }
        if (normalized.contains("RATE_LIMIT") || normalized.contains("TOO_MANY")) {
            return RATE_LIMITED;
        }
        if (normalized.contains("CONFLICT") || normalized.contains("IDEMPOTENCY")
                || normalized.contains("LEASE")) {
            return CONFLICT;
        }
        return INTERNAL;
    }

    public static FailureClass fromErrorCode(String errorCode) {
        return from(null, errorCode);
    }
}
