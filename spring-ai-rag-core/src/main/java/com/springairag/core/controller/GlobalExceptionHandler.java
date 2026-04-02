package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.exception.RagException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 全局异常处理 — RFC 7807 Problem Detail 兼容
 *
 * <p>所有异常统一返回 {@link ErrorResponse} 格式，包含 RFC 7807 标准字段：
 * type / title / status / detail / instance。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e,
                                                            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .error("MISSING_PARAMETER")
                .status(400)
                .message("缺少必需参数: " + e.getParameterName())
                .path(request.getRequestURI())
                .build());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException e,
                                                                 HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .error("INVALID_REQUEST_BODY")
                .status(400)
                .message("请求体格式错误，请检查 JSON 格式")
                .path(request.getRequestURI())
                .build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e,
                                                          HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .error("VALIDATION_FAILED")
                .status(400)
                .message(message)
                .path(request.getRequestURI())
                .build());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .error("TYPE_MISMATCH")
                .status(400)
                .message("参数 '" + e.getName() + "' 类型不正确")
                .path(request.getRequestURI())
                .build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e,
                                                          HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .error("BAD_REQUEST")
                .status(400)
                .message(e.getMessage() != null ? e.getMessage() : "Invalid argument")
                .path(request.getRequestURI())
                .build());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                                  HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ErrorResponse.builder()
                .error("METHOD_NOT_ALLOWED")
                .status(405)
                .message("不支持的请求方法: " + e.getMethod())
                .path(request.getRequestURI())
                .build());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoHandlerFoundException e,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.builder()
                .error("NOT_FOUND")
                .status(404)
                .message("接口不存在: " + e.getRequestURL())
                .path(request.getRequestURI())
                .build());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException e,
                                                          HttpServletRequest request) {
        log.error("Database error: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.builder()
                .error("DATABASE_ERROR")
                .status(500)
                .message("数据库操作失败")
                .path(request.getRequestURI())
                .build());
    }

    @ExceptionHandler(RagException.class)
    public ResponseEntity<ErrorResponse> handleRagException(RagException e,
                                                            HttpServletRequest request) {
        log.warn("RAG business error: [{}] {}", e.getErrorCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus()).body(ErrorResponse.builder()
                .error(e.getErrorCode())
                .status(e.getHttpStatus())
                .message(e.getMessage())
                .path(request.getRequestURI())
                .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e,
                                                         HttpServletRequest request) {
        log.error("Request failed: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.builder()
                .error("INTERNAL_ERROR")
                .status(500)
                .message(e.getMessage() != null ? e.getMessage() : "Unknown error")
                .path(request.getRequestURI())
                .build());
    }
}
