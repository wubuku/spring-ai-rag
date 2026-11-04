package com.springairag.core.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;

/**
 * Servlet 层（filter / error controller）统一错误体写出口。
 *
 * <p>控制器层的错误体由 {@code GlobalExceptionHandler} + {@code FailureClassResponseBodyAdvice}
 * 负责；过滤器在 DispatcherServlet 之前短路请求，拿不到那套 advice，于是历史上每个
 * 过滤器各写一份体：有的带 {@code status}、有的不带，有的设 {@code X-RAG-Failure-Class}、
 * 有的漏了，也没有一处带 {@code traceId}。这个类把「体 + 头 + traceId」收敛成一个出口，
 * 让契约门禁只需要盯一个地方。</p>
 */
public final class NativeErrorResponseWriter {

    private NativeErrorResponseWriter() {
    }

    /** 组装 servlet 层的 RFC 7807 体，字段与控制器层保持一致。 */
    public static ErrorResponse build(HttpServletRequest request,
                                      int status,
                                      String errorCode,
                                      String detail) {
        return ErrorResponse.builder()
                .error(errorCode)
                .status(status)
                .message(detail)
                .path(request == null ? null : request.getRequestURI())
                .traceId(traceId(request))
                .build();
    }

    /** 写出体，并同时设置状态码、Content-Type 与失败分类头。 */
    public static void write(ObjectMapper mapper,
                             HttpServletRequest request,
                             HttpServletResponse response,
                             int status,
                             String errorCode,
                             String detail) throws IOException {
        response.setStatus(status);
        // 体是 RFC 7807 形状，内容类型也必须是 problem+json；此前过滤器写
        // application/json，控制器写 problem+json，同一个契约有两种内容类型。
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        FailureClassHttpSupport.apply(response, status, errorCode);
        response.getWriter().write(mapper.writeValueAsString(
                build(request, status, errorCode, detail)));
    }

    /** 读取 {@link RequestTraceFilter} 写入请求属性的 traceId；不存在时为 null。 */
    public static String traceId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(RequestTraceFilter.REQUEST_TRACE_ID_ATTRIBUTE);
        return value == null ? null : String.valueOf(value);
    }
}
