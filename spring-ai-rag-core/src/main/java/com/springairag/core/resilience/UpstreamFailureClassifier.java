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
    /** 对外诊断信息上限：足够辨别真实原因，又不会把整段上游响应带出去。 */
    private static final int MAX_UPSTREAM_MESSAGE = 300;

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
            if (error instanceof com.springairag.core.admission.DependencyCallException dependency) {
                String code = dependency.getMessage() == null ? "" : dependency.getMessage();
                if (code.contains("TIMEOUT")) {
                    return detail("UPSTREAM", "UPSTREAM_TIMEOUT", true, null, null);
                }
                return detail("REQUEST", "CANCELLED", false, null, null);
            }
            if (error instanceof CancellationException || error instanceof InterruptedException) {
                return detail("REQUEST", "CANCELLED", false, null, null);
            }
            if (error instanceof RestClientResponseException response) {
                int status = response.getStatusCode().value();
                return detail("UPSTREAM", reasonForStatus(status), retryableStatus(status),
                        retryAfter(response.getResponseHeaders() == null ? null
                                : response.getResponseHeaders().getFirst("Retry-After"), Instant.now()),
                        status, upstreamMessage(response.getResponseBodyAsString()));
            }
            if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException response) {
                int status = response.getStatusCode().value();
                return detail("UPSTREAM", reasonForStatus(status), retryableStatus(status),
                        retryAfter(response.getHeaders().getFirst("Retry-After"), Instant.now()),
                        status, upstreamMessage(response.getResponseBodyAsString()));
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
            } else if (error instanceof NonTransientAiException) {
                Integer status = leadingStatus(error.getMessage());
                if (status != null) {
                    return detail("UPSTREAM", reasonForStatus(status), retryableStatus(status),
                            null, status, upstreamMessage(messageAfterStatus(error.getMessage())));
                }
                if ("UNCLASSIFIED".equals(fallback.reason())) {
                    fallback = detail("UPSTREAM", "UPSTREAM_REJECTED", false, null, null);
                }
            } else if (error instanceof TransientAiException) {
                Integer status = leadingStatus(error.getMessage());
                if (status != null) {
                    return detail("UPSTREAM", reasonForStatus(status), retryableStatus(status),
                            null, status, upstreamMessage(messageAfterStatus(error.getMessage())));
                }
                if ("UNCLASSIFIED".equals(fallback.reason())) {
                    fallback = detail("UPSTREAM", "UPSTREAM_UNAVAILABLE", true, null, null);
                }
            }
        }
        return fallback;
    }

    public static boolean dependencyFailure(Throwable error) {
        FailureDetails details = classify(error);
        return "UPSTREAM".equals(details.origin()) && details.retryable();
    }

    /**
     * 上游失败到对外 {@link ErrorCode} 的单一映射点。超时 → 504，限流 → 429，
     * 其余（认证、被拒、5xx、连接断开）→ 503；非上游异常返回 {@code null}。
     * 幂等失败快照与异常处理器都走这里，避免两处状态码漂移。
     */
    public static com.springairag.api.enums.ErrorCode errorCode(FailureDetails details) {
        if (details == null || !"UPSTREAM".equals(details.origin())) {
            return null;
        }
        if ("UPSTREAM_TIMEOUT".equals(details.reason())) {
            return com.springairag.api.enums.ErrorCode.GATEWAY_TIMEOUT;
        }
        if ("UPSTREAM_RATE_LIMITED".equals(details.reason())) {
            return com.springairag.api.enums.ErrorCode.TOO_MANY_REQUESTS;
        }
        return com.springairag.api.enums.ErrorCode.LLM_UNAVAILABLE;
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

    /**
     * Spring AI 在部分 provider 上抛出的 {@code TransientAiException}/{@code NonTransientAiException}
     * 只把状态码写在消息前缀里（例如 {@code 401 - {"code":30014…}}），不保留
     * {@code RestClientResponseException} 的结构化字段。这里只认行首三位数字，
     * 不会去猜正文里的其它数字。
     */
    private static Integer leadingStatus(String message) {
        if (message == null || message.length() < 3) {
            return null;
        }
        String prefix = message.substring(0, 3);
        if (!prefix.chars().allMatch(Character::isDigit)) {
            return null;
        }
        int status = Integer.parseInt(prefix);
        if (status < 400 || status > 599) {
            return null;
        }
        // 只接受 `401 - ...` / `503 - ...` 这种 Spring AI 的固定前缀，
        // 避免把业务文本里的 "404" 当成上游状态。
        if (message.length() > 3 && message.charAt(3) != ' ') {
            return null;
        }
        return status;
    }

    private static String reasonForStatus(int status) {
        return status == 401 || status == 403 ? "UPSTREAM_AUTHENTICATION"
                : status == 429 ? "UPSTREAM_RATE_LIMITED"
                : status == 408 || status == 504 ? "UPSTREAM_TIMEOUT"
                : status >= 500 ? "UPSTREAM_UNAVAILABLE" : "UPSTREAM_REJECTED";
    }

    private static boolean retryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    /**
     * 从上游错误正文提取一段可诊断的真实原因：脱敏、去控制字符、压空白、截断。
     * 空正文或只有通用包装文本时返回 {@code null}，避免制造“有诊断”的假象。
     */
    private static String upstreamMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String masked = com.springairag.core.logging.SensitiveDataMaskingConverter
                .maskSensitiveData(body);
        String cleaned = masked.replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() <= MAX_UPSTREAM_MESSAGE
                ? cleaned
                : cleaned.substring(0, MAX_UPSTREAM_MESSAGE) + "...";
    }

    /** Spring AI 的 `429 - {...}` 前缀之后才是真正的上游正文。 */
    private static String messageAfterStatus(String message) {
        if (leadingStatus(message) == null || message.length() <= 4) {
            return null;
        }
        return message.substring(4);
    }

    private static FailureDetails detail(String origin, String reason, boolean retryable,
                                         Long wait, Integer status) {
        return detail(origin, reason, retryable, wait, status, null);
    }

    private static FailureDetails detail(String origin, String reason, boolean retryable,
                                         Long wait, Integer status, String upstreamMessage) {
        return new FailureDetails(origin, reason, retryable, wait, status, upstreamMessage);
    }
}
