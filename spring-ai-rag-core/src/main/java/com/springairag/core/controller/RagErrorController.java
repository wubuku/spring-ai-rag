package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.api.enums.FailureClass;
import com.springairag.core.filter.FailureClassHttpSupport;
import com.springairag.core.filter.NativeErrorResponseWriter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 容器级错误出口：被 servlet 容器转发到 {@code /error} 的失败也走统一错误体。
 *
 * <p>控制器内抛出的异常由 {@link GlobalExceptionHandler} 处理，但过滤器里抛出的异常
 * 在 DispatcherServlet 之外发生，不会进入 {@code @RestControllerAdvice}。没有这个
 * 控制器时，它们落到 Spring Boot 的 {@code BasicErrorController}，返回
 * {@code {timestamp,status,error,path}} —— 没有错误码、没有 {@code failureClass}、
 * 没有 traceId，也不设置 {@code X-RAG-Failure-Class}。契约门禁因此要求它存在。</p>
 *
 * <p>消息一律取 {@link ErrorCode#getTitle()}：容器级失败的信息无法可靠分类，
 * 原样透出 {@code ERROR_MESSAGE} 会把内部异常文本带进响应。</p>
 */
@RestController
public class RagErrorController implements ErrorController {

    private static final int DEFAULT_STATUS = 500;

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        int status = resolveStatus(request);
        ErrorCode code = codeForStatus(status);
        ErrorResponse body = NativeErrorResponseWriter.build(
                request, status, code.getCode(), code.getTitle());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(FailureClassHttpSupport.HEADER,
                        FailureClass.from(status, code.getCode()).name())
                .body(body);
    }

    private int resolveStatus(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (value instanceof Integer status) {
            return status;
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 落回默认值
            }
        }
        return DEFAULT_STATUS;
    }

    private ErrorCode codeForStatus(int status) {
        return switch (status) {
            case 400 -> ErrorCode.BAD_REQUEST;
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 409 -> ErrorCode.CONCURRENT_MODIFICATION;
            case 422 -> ErrorCode.UNPROCESSABLE_ENTITY;
            case 429 -> ErrorCode.TOO_MANY_REQUESTS;
            case 504 -> ErrorCode.GATEWAY_TIMEOUT;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.BAD_REQUEST;
        };
    }
}
