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
            "503,UPSTREAM_UNAVAILABLE,true"})
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
