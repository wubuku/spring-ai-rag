package com.springairag.core.resilience;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class UpstreamFailureClassifierTest {
    @ParameterizedTest
    @CsvSource({"401,UPSTREAM_AUTHENTICATION,false", "403,UPSTREAM_AUTHENTICATION,false",
            "400,UPSTREAM_REJECTED,false", "404,UPSTREAM_REJECTED,false", "429,UPSTREAM_RATE_LIMITED,true",
            "408,UPSTREAM_TIMEOUT,true", "504,UPSTREAM_TIMEOUT,true", "502,UPSTREAM_UNAVAILABLE,true",
            "503,UPSTREAM_UNAVAILABLE,true", "500,UPSTREAM_UNAVAILABLE,true"})
    void wrappedHttpStatusIsAuthoritative(int status, String reason, boolean retryable) {
        var failure = new RestClientResponseException("secret must not leak", HttpStatusCode.valueOf(status),
                "", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        var details = UpstreamFailureClassifier.classify(new IllegalStateException(failure));
        assertEquals("UPSTREAM", details.origin());
        assertEquals(reason, details.reason());
        assertEquals(retryable, details.retryable());
        assertEquals(status, details.upstreamStatus());
        assertFalse(details.toString().contains("secret"));
    }

    @ParameterizedTest
    @CsvSource({"401,UPSTREAM_AUTHENTICATION,false", "403,UPSTREAM_AUTHENTICATION,false",
            "429,UPSTREAM_RATE_LIMITED,true", "503,UPSTREAM_UNAVAILABLE,true",
            "504,UPSTREAM_TIMEOUT,true"})
    void springAiExceptionsCarryTheirStatusInTheMessagePrefix(int status, String reason, boolean retryable) {
        var error = new org.springframework.ai.retry.NonTransientAiException(
                status + " - {\"code\":30014,\"message\":\"provider said no\"}");
        var details = UpstreamFailureClassifier.classify(error);
        assertEquals("UPSTREAM", details.origin());
        assertEquals(reason, details.reason());
        assertEquals(retryable, details.retryable());
        assertEquals(status, details.upstreamStatus());
    }

    @Test
    void messageDigitsAreNotGuessedWhenTheyAreNotAStatusPrefix() {
        var error = new org.springframework.ai.retry.NonTransientAiException(
                "provider failed with code 404 in body");
        var details = UpstreamFailureClassifier.classify(error);
        assertEquals("UPSTREAM_REJECTED", details.reason());
        assertNull(details.upstreamStatus());
    }

    @Test
    void timeoutCauseOverridesNetworkWrapperWithoutReadingText() {
        var details = UpstreamFailureClassifier.classify(new ResourceAccessException("opaque", new SocketTimeoutException()));
        assertEquals("UPSTREAM_TIMEOUT", details.reason());
        assertTrue(details.retryable());
    }

    @Test
    void domainRejectionAndCancellationNeverBecomeProviderFailures() {
        assertFalse(UpstreamFailureClassifier.dependencyFailure(new RagException(ErrorCode.FORBIDDEN, "denied")));
        assertFalse(UpstreamFailureClassifier.dependencyFailure(new CancellationException()));
        assertFalse(UpstreamFailureClassifier.dependencyFailure(new IllegalStateException("HTTP 503 timeout")));
        assertEquals("REQUEST_DEADLINE", UpstreamFailureClassifier.classify(new RagException(ErrorCode.CHAT_TIMEOUT, "deadline")).reason());
    }

    @Test
    void malformedJsonIsNotRetriedAsNetworkIo() {
        var error = new com.fasterxml.jackson.core.JsonParseException(null, "invalid provider payload");
        assertEquals("UPSTREAM_RESPONSE_INVALID", UpstreamFailureClassifier.classify(error).reason());
        assertFalse(UpstreamFailureClassifier.dependencyFailure(error));
    }

    @Test
    void dependencyFailureOnlyTreatsRetryableUpstreamErrorsAsDependencies() {
        assertTrue(UpstreamFailureClassifier.dependencyFailure(
                new ResourceAccessException("opaque", new SocketTimeoutException())));
        assertTrue(UpstreamFailureClassifier.dependencyFailure(
                new RestClientResponseException("fixture", HttpStatusCode.valueOf(503),
                        "", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8)));
    }

    @Test
    void classifiedDetailsAndDependencyCodesArePreserved() {
        var classified = new ClassifiedRagException(ErrorCode.RETRIEVAL_FAILED,
                new com.springairag.api.dto.FailureDetails(
                        "UPSTREAM", "UPSTREAM_RATE_LIMITED", true, 1000L, 429));
        assertEquals("UPSTREAM_RATE_LIMITED",
                UpstreamFailureClassifier.classify(classified).reason());

        assertEquals("UPSTREAM_TIMEOUT", UpstreamFailureClassifier.classify(
                new com.springairag.core.admission.DependencyCallException(
                        "UPSTREAM_TIMEOUT")).reason());
        var cancelled = UpstreamFailureClassifier.classify(
                new com.springairag.core.admission.DependencyCallException(
                        "CANCELLED"));
        assertEquals("REQUEST", cancelled.origin());
        assertEquals("CANCELLED", cancelled.reason());
    }

    @Test
    void reactiveWebClientErrorsReuseStatusClassification() {
        var failure = org.springframework.web.reactive.function.client
                .WebClientResponseException.create(
                        503, "unavailable", new HttpHeaders(), new byte[0],
                        StandardCharsets.UTF_8);

        var details = UpstreamFailureClassifier.classify(failure);
        assertEquals("UPSTREAM_UNAVAILABLE", details.reason());
        assertTrue(details.retryable());
        assertEquals(503, details.upstreamStatus());
    }

    @Test
    void upstreamErrorMessageIsSanitizedTruncatedAndNotLeakingSecrets() {
        var body = ("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\","
                + "\"message\":\"已达到 Token Plan 用量上限, 请升级套餐 (2056)\"},"
                + "\"authorization\":\"Bearer sk-very-secret-key\","
                + "\"padding\":\"" + "x".repeat(600) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        var failure = new RestClientResponseException("429", HttpStatusCode.valueOf(429),
                "429", new HttpHeaders(), body, StandardCharsets.UTF_8);

        var details = UpstreamFailureClassifier.classify(failure);
        assertNotNull(details.upstreamMessage());
        assertTrue(details.upstreamMessage().contains("Token Plan"));
        assertFalse(details.upstreamMessage().contains("sk-very-secret-key"));
        assertTrue(details.upstreamMessage().length() <= 303);
    }

    @Test
    void causeInspectionStopsAtSixteenLevels() {
        var deep = new ClassifiedRagException(ErrorCode.RETRIEVAL_FAILED,
                new com.springairag.api.dto.FailureDetails(
                        "UPSTREAM", "UPSTREAM_TIMEOUT", true, null, null));
        Throwable error = deep;
        for (int depth = 0; depth < 16; depth++) {
            error = new RuntimeException("wrapper-" + depth, error);
        }

        assertEquals("UNCLASSIFIED", UpstreamFailureClassifier.classify(error).reason());
    }

    @Test
    @SuppressWarnings("ThrowableNotThrown")
    void safeMessageFallsBackForMessagelessErrors() {
        assertEquals("Request failed",
                UpstreamFailureClassifier.safeMessage(new IllegalStateException()));
        String masked = UpstreamFailureClassifier.safeMessage(new IllegalStateException(
                "{\"password\":\"hunter2\",\"note\":\"denied\"}"));
        assertTrue(masked.contains("denied"));
        assertFalse(masked.contains("hunter2"));
    }

    @Test
    void serverErrorRagExceptionIsServiceFailureAndClientErrorIsRejected() {
        var server = UpstreamFailureClassifier.classify(
                new RagException(ErrorCode.RETRIEVAL_FAILED, "boom"));
        assertEquals("SERVICE", server.origin());
        assertEquals("BUSINESS_REJECTED", server.reason());
        assertFalse(server.retryable());

        var client = UpstreamFailureClassifier.classify(
                new RagException(ErrorCode.BAD_REQUEST, "bad"));
        assertEquals("REQUEST", client.origin());
    }

    @Test
    void timeoutWinsOverLaterConnectionWrappers() {
        java.net.SocketTimeoutException error =
                new java.net.SocketTimeoutException("timeout");
        error.initCause(new java.io.IOException("later connection"));

        assertEquals("UPSTREAM_TIMEOUT", UpstreamFailureClassifier.classify(error).reason());
    }

    @Test
    void safeMessageNeverExposesUpstreamPayloadAndMasksRequestText() {
        assertEquals("UPSTREAM_TIMEOUT",
                UpstreamFailureClassifier.safeMessage(new SocketTimeoutException()));
        String masked = UpstreamFailureClassifier.safeMessage(new IllegalStateException(
                "{\"password\":\"hunter2\",\"note\":\"denied\"}"));
        assertFalse(masked.contains("hunter2"));
    }

    @Test
    void retryAfterIsBoundedAndAcceptsDates() {
        Instant now = Instant.parse("2026-10-18T00:00:00Z");
        assertEquals(2000L, UpstreamFailureClassifier.retryAfter("2", now));
        assertEquals(3000L, UpstreamFailureClassifier.retryAfter("Sun, 18 Oct 2026 00:00:03 GMT", now));
        assertEquals(10000L, UpstreamFailureClassifier.retryAfter("99999999", now));
        assertEquals(0L, UpstreamFailureClassifier.retryAfter("Sun, 18 Oct 2026 00:00:00 GMT", now));
        assertNull(UpstreamFailureClassifier.retryAfter("-1", now));
        assertNull(UpstreamFailureClassifier.retryAfter("secret".repeat(100), now));
    }

    @Test
    void causeCyclesAreBounded() {
        RuntimeException first = new RuntimeException();
        RuntimeException second = new RuntimeException(first);
        first.initCause(second);
        assertEquals("UNCLASSIFIED", UpstreamFailureClassifier.classify(first).reason());
    }
}
