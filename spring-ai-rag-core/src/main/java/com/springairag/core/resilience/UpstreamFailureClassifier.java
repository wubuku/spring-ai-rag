package com.springairag.core.resilience;

import com.springairag.api.dto.FailureDetails;
import com.springairag.core.exception.RagException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

public final class UpstreamFailureClassifier {
    private UpstreamFailureClassifier() { }

    public static FailureDetails classify(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        FailureDetails fallback = detail("SERVICE", "UNCLASSIFIED", false, null, null);
        for (int depth = 0; error != null && depth < 16 && seen.add(error); depth++, error = error.getCause()) {
            if (error instanceof ClassifiedRagException classified) return classified.details();
            if (error instanceof RagException rag) {
                if ("CHAT_TIMEOUT".equals(rag.getErrorCode())) return detail("REQUEST", "REQUEST_DEADLINE", false, null, null);
                return detail(rag.getErrorCodeEnum().getHttpStatus() < 500 ? "REQUEST" : "SERVICE", "BUSINESS_REJECTED", false, null, null);
            }
            if (error instanceof CancellationException || error instanceof InterruptedException) {
                return detail("REQUEST", "CANCELLED", false, null, null);
            }
            if (error instanceof RestClientResponseException response) {
                int status = response.getStatusCode().value();
                String reason = status == 401 || status == 403 ? "UPSTREAM_AUTHENTICATION"
                        : status == 429 ? "UPSTREAM_RATE_LIMITED"
                        : status == 408 || status == 504 ? "UPSTREAM_TIMEOUT"
                        : status >= 500 ? "UPSTREAM_UNAVAILABLE" : "UPSTREAM_REJECTED";
                return detail("UPSTREAM", reason, status == 408 || status == 429 || status >= 500,
                        retryAfter(response.getResponseHeaders() == null ? null
                                : response.getResponseHeaders().getFirst("Retry-After"), Instant.now()), status);
            }
            if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException response) {
                return classify(new RestClientResponseException("Upstream response", response.getStatusCode(),
                        "", response.getHeaders(), new byte[0], java.nio.charset.StandardCharsets.UTF_8));
            }
            if (error instanceof com.fasterxml.jackson.core.JsonProcessingException) {
                return detail("UPSTREAM", "UPSTREAM_RESPONSE_INVALID", false, null, null);
            }
            if (error instanceof java.net.http.HttpConnectTimeoutException) {
                fallback = detail("UPSTREAM", "UPSTREAM_CONNECTION", true, null, null);
            } else if (error instanceof SocketTimeoutException || error instanceof HttpTimeoutException || error instanceof TimeoutException) {
                fallback = detail("UPSTREAM", "UPSTREAM_TIMEOUT", true, null, null);
            } else if ((error instanceof ResourceAccessException || error instanceof IOException)
                    && !"UPSTREAM_TIMEOUT".equals(fallback.reason())) {
                fallback = detail("UPSTREAM", "UPSTREAM_CONNECTION", true, null, null);
            } else if (error instanceof NonTransientAiException && "UNCLASSIFIED".equals(fallback.reason())) {
                fallback = detail("UPSTREAM", "UPSTREAM_REJECTED", false, null, null);
            } else if (error instanceof TransientAiException && "UNCLASSIFIED".equals(fallback.reason())) {
                fallback = detail("UPSTREAM", "UPSTREAM_UNAVAILABLE", true, null, null);
            }
        }
        return fallback;
    }

    public static boolean dependencyFailure(Throwable error) {
        FailureDetails details = classify(error);
        return "UPSTREAM".equals(details.origin()) && details.retryable();
    }

    public static String safeMessage(Throwable error) {
        FailureDetails details = classify(error);
        if ("UPSTREAM".equals(details.origin())) return details.reason();
        return com.springairag.core.logging.SensitiveDataMaskingConverter.maskSensitiveData(
                error.getMessage() == null ? "Request failed" : error.getMessage());
    }

    public static Long retryAfter(String value, Instant now) {
        if (value == null || value.length() > 128) return null;
        try {
            long millis = value.matches("[0-9]{1,8}") ? Math.multiplyExact(Long.parseLong(value), 1000)
                    : java.time.Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
            return Math.max(0, Math.min(10_000, millis));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static FailureDetails detail(String origin, String reason, boolean retryable, Long wait, Integer status) {
        return new FailureDetails(origin, reason, retryable, wait, status);
    }
}
