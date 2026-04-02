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
import java.util.UUID;

/**
 * 请求追踪过滤器
 *
 * <p>为每个 HTTP 请求生成唯一 traceId，注入 SLF4J MDC，
 * 所有日志自动携带该 ID，便于链路追踪和问题排查。
 *
 * <p>同时在响应头返回 {@code X-Trace-Id}，方便前端/调用方关联日志。
 *
 * <p>使用方式：日志中通过 {@code %X{traceId}} 占位符输出，
 * 或在 logback-spring.xml 中配置 {@code <pattern>} 包含 {@code %X{traceId}}。
 */
@Component
@Order(1)
public class RequestTraceFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);

    /** MDC key */
    public static final String TRACE_ID_KEY = "traceId";

    /** 响应头名 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 请求头名（调用方可传入自己的 traceId） */
    public static final String INCOMING_TRACE_HEADER = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 优先使用调用方传入的 traceId，便于跨服务链路追踪
        String traceId = httpRequest.getHeader(INCOMING_TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = generateTraceId();
        }

        // 注入 MDC — 所有后续日志自动携带 traceId
        MDC.put(TRACE_ID_KEY, traceId);

        // 响应头返回 traceId
        httpResponse.setHeader(TRACE_ID_HEADER, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            // 清理 MDC，避免线程池复用时串数据
            MDC.remove(TRACE_ID_KEY);
        }
    }

    /**
     * 生成短格式 traceId：前 8 位时间戳 + 后 4 位随机，共 12 字符
     *
     * <p>比完整 UUID 更短，日志中更紧凑，仍然足够唯一。
     */
    private String generateTraceId() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return uuid.substring(0, 12);
    }
}
