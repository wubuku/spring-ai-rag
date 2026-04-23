package com.springairag.core.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Request trace filter (enhanced)
 *
 * <p>Generates unique traceId + spanId for each HTTP request, injects SLF4J MDC,
 * so all logs automatically carry trace context for troubleshooting and performance analysis.
 *
 * <h3>Sampling Strategy</h3>
 * <p>Controls sampling rate via {@code rag.tracing.sampling-rate} (0.0~1.0).
 * Unsampled requests do not generate traceId, MDC is empty. Externally provided traceId is always preserved.
 *
 * <h3>W3C Trace Context</h3>
 * <p>When {@code rag.tracing.w3c-format} is enabled, also outputs W3C {@code traceparent} response header,
 * Format: {@code 00-<traceId 32hex>-<spanId 16hex>-01}, compatible with OpenTelemetry and other standards.
 *
 * <h3>Span ID</h3>
 * <p>When {@code rag.tracing.span-id-enabled} is enabled, each request generates an independent spanId,
 * injecting MDC key {@code spanId} to distinguish nested calls.
 *
 * <p>Log configuration:
 * <pre>
 * &lt;pattern&gt;%d{HH:mm:ss.SSS} [%thread] [%X{traceId}:%X{spanId}] %-5level %logger - %msg&lt;/pattern&gt;
 * </pre>
 */
@Component
@Order(1)
public class RequestTraceFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);

    /** MDC key：traceId */
    public static final String TRACE_ID_KEY = "traceId";

    /** MDC key：spanId */
    public static final String SPAN_ID_KEY = "spanId";

    /** Response header: custom traceId */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** Response header: W3C traceparent */
    public static final String W3C_TRACEPARENT_HEADER = "traceparent";

    /** Request header: caller-provided traceId */
    public static final String INCOMING_TRACE_HEADER = "X-Trace-Id";

    /** Request header: W3C traceparent (from upstream) */
    public static final String INCOMING_TRACEPARENT_HEADER = "traceparent";

    /** Request attribute: whether this request is sampled */
    public static final String SAMPLED_ATTRIBUTE = "traceSampled";

    private volatile boolean enabled = true;
    private volatile double samplingRate = 1.0;
    private volatile boolean w3cFormat = false;
    private volatile boolean spanIdEnabled = false;

    /**
     * Refresh parameters from config (called by Spring lifecycle)
     */
    public void configure(boolean enabled, double samplingRate, boolean w3cFormat, boolean spanIdEnabled) {
        this.enabled = enabled;
        this.samplingRate = Math.max(0.0, Math.min(1.0, samplingRate));
        this.w3cFormat = w3cFormat;
        this.spanIdEnabled = spanIdEnabled;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Prioritize getting traceId from upstream (cross-service propagation)
        TraceContext ctx = resolveTraceContext(httpRequest);

        boolean sampled = ctx.external || shouldSample();
        request.setAttribute(SAMPLED_ATTRIBUTE, sampled);

        if (!sampled) {
            // Not sampled: do not inject MDC, but still return response header (for debugging)
            if (ctx.traceId != null) {
                httpResponse.setHeader(TRACE_ID_HEADER, ctx.traceId);
            }
            chain.doFilter(request, response);
            return;
        }

        // Inject MDC
        MDC.put(TRACE_ID_KEY, ctx.traceId);
        if (spanIdEnabled && ctx.spanId != null) {
            MDC.put(SPAN_ID_KEY, ctx.spanId);
        }

        // Response header
        httpResponse.setHeader(TRACE_ID_HEADER, ctx.traceId);
        if (w3cFormat) {
            String spanId = ctx.spanId != null ? ctx.spanId : generateHexId(16);
            httpResponse.setHeader(W3C_TRACEPARENT_HEADER,
                    "00-" + ctx.traceId + "-" + spanId + "-01");
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_KEY);
            if (spanIdEnabled) {
                MDC.remove(SPAN_ID_KEY);
            }
        }
    }

    /**
     * Parse trace context: prioritize W3C traceparent, then X-Trace-Id
     */
    private TraceContext resolveTraceContext(HttpServletRequest request) {
        // Try W3C traceparent
        String traceparent = request.getHeader(INCOMING_TRACEPARENT_HEADER);
        if (traceparent != null && !traceparent.isBlank()) {
            return parseTraceparent(traceparent);
        }

        // Try custom X-Trace-Id
        String traceId = request.getHeader(INCOMING_TRACE_HEADER);
        if (traceId != null && !traceId.isBlank()) {
            String spanId = spanIdEnabled ? generateHexId(16) : null;
            return new TraceContext(traceId, spanId, true);
        }

        // Generate new context
        String newTraceId = w3cFormat ? generateHexId(32) : generateTraceId();
        return new TraceContext(newTraceId, spanIdEnabled ? generateHexId(16) : null, false);
    }

    /**
     * Parse W3C traceparent format: 00-<traceId 32hex>-<spanId 16hex>-<flags>
     */
    private TraceContext parseTraceparent(String traceparent) {
        try {
            String[] parts = traceparent.split("-");
            if (parts.length == 4 && "00".equals(parts[0])) {
                String traceId = parts[1];
                String spanId = spanIdEnabled ? parts[2] : null;
                return new TraceContext(traceId, spanId, true);
            }
        } catch (Exception e) { // Resilience: traceparent parsing failure falls back to generated traceId
            log.debug("Failed to parse traceparent: {}", traceparent);
        }
        // Parse failed, generating new
        return new TraceContext(generateTraceId(), spanIdEnabled ? generateHexId(16) : null, false);
    }

    /**
     * Sampling decision
     */
    private boolean shouldSample() {
        if (samplingRate >= 1.0) return true;
        if (samplingRate <= 0.0) return false;
        return ThreadLocalRandom.current().nextDouble() < samplingRate;
    }

    /**
     * Generate short-format traceId: first 8 chars of UUID + last 4 random chars, 12 characters total
     */
    private String generateTraceId() {
        return generateHexId(12);
    }

    /**
     * Generate hexadecimal ID of specified length
     */
    static String generateHexId(int length) {
        byte[] bytes = new byte[(length + 1) / 2];
        ThreadLocalRandom.current().nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes);
        return hex.substring(0, length);
    }

    // ==================== Inner class ====================

    private record TraceContext(String traceId, String spanId, boolean external) {}

    // ==================== Test helper ====================

    boolean isEnabled() { return enabled; }
    double getSamplingRate() { return samplingRate; }
    boolean isW3cFormat() { return w3cFormat; }
    boolean isSpanIdEnabled() { return spanIdEnabled; }
}
