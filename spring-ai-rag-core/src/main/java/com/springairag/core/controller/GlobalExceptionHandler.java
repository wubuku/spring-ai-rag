package com.springairag.core.controller;

import com.springairag.core.exception.RagException;
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

import java.time.Instant;
import java.util.Map;

/**
 * 全局异常处理
 * 捕获 Controller 未处理的异常，返回结构化错误响应
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 参数校验失败（缺少必需参数）
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Missing Parameter",
                "message", "缺少必需参数: " + e.getParameterName(),
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * 请求体格式错误（JSON 解析失败）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableMessage(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid Request Body",
                "message", "请求体格式错误，请检查 JSON 格式",
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Bean Validation 校验失败（@Valid + @NotBlank 等）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Validation Failed",
                "message", message,
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * 参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Type Mismatch",
                "message", "参数 '" + e.getName() + "' 类型不正确",
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * 业务参数非法
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Bad Request",
                "message", e.getMessage() != null ? e.getMessage() : "Invalid argument",
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * 请求方法不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of(
                "error", "Method Not Allowed",
                "message", "不支持的请求方法: " + e.getMethod(),
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * 404 路由未找到
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoHandlerFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "Not Found",
                "message", "接口不存在: " + e.getRequestURL(),
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * 数据库访问异常
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(DataAccessException e) {
        log.error("Database error: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Database Error",
                "message", "数据库操作失败",
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * RAG 业务异常（统一处理 DocumentNotFoundException / EmbeddingException / RetrievalException）
     */
    @ExceptionHandler(RagException.class)
    public ResponseEntity<Map<String, Object>> handleRagException(RagException e) {
        log.warn("RAG business error: [{}] {}", e.getErrorCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus()).body(Map.of(
                "error", e.getErrorCode(),
                "message", e.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * 兜底：所有未捕获异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("Request failed: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", e.getClass().getSimpleName(),
                "message", e.getMessage() != null ? e.getMessage() : "Unknown error",
                "timestamp", Instant.now().toString()
        ));
    }
}
