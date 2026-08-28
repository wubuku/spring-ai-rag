package com.springairag.core.alertdelivery;

import java.time.Duration;

/** 单次 provider 调用的结构化结果。 */
public record AlertNotificationAttemptResult(
        Outcome outcome,
        String errorCode,
        Integer httpStatus,
        Duration retryAfter) {

    public enum Outcome {
        SUCCESS,
        TRANSIENT_FAILURE,
        PERMANENT_FAILURE
    }

    public static AlertNotificationAttemptResult success() {
        return new AlertNotificationAttemptResult(
                Outcome.SUCCESS, null, null, null);
    }

    public static AlertNotificationAttemptResult transientFailure(
            String errorCode, Integer httpStatus, Duration retryAfter) {
        return new AlertNotificationAttemptResult(
                Outcome.TRANSIENT_FAILURE, errorCode, httpStatus, retryAfter);
    }

    public static AlertNotificationAttemptResult permanentFailure(
            String errorCode, Integer httpStatus) {
        return new AlertNotificationAttemptResult(
                Outcome.PERMANENT_FAILURE, errorCode, httpStatus, null);
    }
}
