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
 * 请求追踪过滤器（增强版）
 *
 * <p>为每个 HTTP 请求生成唯一 traceId + spanId，注入 SLF4J MDC，
 * 所有日志自动携带链路信息，便于问题排查和性能分析。
 *
 * <h3>采样策略</h3>
 * <p>通过 {@code rag.tracing.sampling-rate} 控制采样率（0.0~1.0）。
 * 未命中的请求不生成 traceId，MDC 为空。外部传入的 traceId 始终保留。
 *
 * <h3>W3C Trace Context</h3>
 * <p>启用 {@code rag.tracing.w3c-format} 后，同时输出 W3C {@code traceparent} 响应头，
 * 格式：{@code 00-<traceId 32hex>-<spanId 16hex>-01}，兼容 OpenTelemetry 等标准。
 *
 * <h3>Span ID</h3>
 * <p>启用 {@code rag.tracing.span-id-enabled} 后，每次请求生成独立 spanId，
 * 注入 MDC key {@code spanId}，便于区分嵌套调用。
 *
 * <p>日志配置：
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

    /** 响应头：自定义 traceId */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 响应头：W3C traceparent */
    public static final String W3C_TRACEPARENT_HEADER = "traceparent";

    /** 请求头：调用方传入的 traceId */
    public static final String INCOMING_TRACE_HEADER = "X-Trace-Id";

    /** 请求头：W3C traceparent（上游传递） */
    public static final String INCOMING_TRACEPARENT_HEADER = "traceparent";

    /** 请求属性：本次请求是否被采样 */
    public static final String SAMPLED_ATTRIBUTE = "traceSampled";

    private volatile boolean enabled = true;
    private volatile double samplingRate = 1.0;
    private volatile boolean w3cFormat = false;
    private volatile boolean spanIdEnabled = false;

    /**
     * 从配置刷新参数（由 Spring 生命周期调用）
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

        // 优先从上游获取 traceId（跨服务传播）
        TraceContext ctx = resolveTraceContext(httpRequest);

        boolean sampled = ctx.external || shouldSample();
        request.setAttribute(SAMPLED_ATTRIBUTE, sampled);

        if (!sampled) {
            // 未采样：不注入 MDC，但响应头仍返回（便于调试）
            if (ctx.traceId != null) {
                httpResponse.setHeader(TRACE_ID_HEADER, ctx.traceId);
            }
            chain.doFilter(request, response);
            return;
        }

        // 注入 MDC
        MDC.put(TRACE_ID_KEY, ctx.traceId);
        if (spanIdEnabled && ctx.spanId != null) {
            MDC.put(SPAN_ID_KEY, ctx.spanId);
        }

        // 响应头
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
     * 解析追踪上下文：优先 W3C traceparent，其次 X-Trace-Id
     */
    private TraceContext resolveTraceContext(HttpServletRequest request) {
        // 尝试 W3C traceparent
        String traceparent = request.getHeader(INCOMING_TRACEPARENT_HEADER);
        if (traceparent != null && !traceparent.isBlank()) {
            return parseTraceparent(traceparent);
        }

        // 尝试自定义 X-Trace-Id
        String traceId = request.getHeader(INCOMING_TRACE_HEADER);
        if (traceId != null && !traceId.isBlank()) {
            String spanId = spanIdEnabled ? generateHexId(16) : null;
            return new TraceContext(traceId, spanId, true);
        }

        // 生成新上下文
        String newTraceId = w3cFormat ? generateHexId(32) : generateTraceId();
        return new TraceContext(newTraceId, spanIdEnabled ? generateHexId(16) : null, false);
    }

    /**
     * 解析 W3C traceparent 格式：00-<traceId 32hex>-<spanId 16hex>-<flags>
     */
    private TraceContext parseTraceparent(String traceparent) {
        try {
            String[] parts = traceparent.split("-");
            if (parts.length == 4 && "00".equals(parts[0])) {
                String traceId = parts[1];
                String spanId = spanIdEnabled ? parts[2] : null;
                return new TraceContext(traceId, spanId, true);
            }
        } catch (Exception e) {
            log.debug("Failed to parse traceparent: {}", traceparent);
        }
        // 解析失败，生成新的
        return new TraceContext(generateTraceId(), spanIdEnabled ? generateHexId(16) : null, false);
    }

    /**
     * 采样判断
     */
    private boolean shouldSample() {
        if (samplingRate >= 1.0) return true;
        if (samplingRate <= 0.0) return false;
        return ThreadLocalRandom.current().nextDouble() < samplingRate;
    }

    /**
     * 生成短格式 traceId：前 8 位 UUID + 后 4 位随机，共 12 字符
     */
    private String generateTraceId() {
        return generateHexId(12);
    }

    /**
     * 生成指定长度的十六进制 ID
     */
    static String generateHexId(int length) {
        byte[] bytes = new byte[(length + 1) / 2];
        ThreadLocalRandom.current().nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes);
        return hex.substring(0, length);
    }

    // ==================== 内部类 ====================

    private record TraceContext(String traceId, String spanId, boolean external) {}

    // ==================== 测试辅助 ====================

    boolean isEnabled() { return enabled; }
    double getSamplingRate() { return samplingRate; }
    boolean isW3cFormat() { return w3cFormat; }
    boolean isSpanIdEnabled() { return spanIdEnabled; }
}
