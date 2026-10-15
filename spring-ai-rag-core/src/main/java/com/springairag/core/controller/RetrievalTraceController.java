package com.springairag.core.controller;

import com.springairag.api.dto.RetrievalTraceDetailResponse;
import com.springairag.api.dto.RetrievalTracePageResponse;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.versioning.ApiVersion;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 当前认证 principal 的检索诊断只读 API。
 */
@RestController
@ApiVersion("v1")
@com.springairag.core.security.ApiRoutePolicy
@RequestMapping("/rag/retrieval-traces")
public class RetrievalTraceController {

    private final RetrievalDiagnosticsService diagnosticsService;

    public RetrievalTraceController(RetrievalDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    @GetMapping
    public RetrievalTracePageResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String outcomeCode,
            @RequestParam(required = false) String emptyReasonCode,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String requestTraceId,
            @RequestParam(required = false) String citationStatus,
            HttpServletRequest request) {
        if (requestTraceId == null || requestTraceId.isBlank()) {
            return diagnosticsService.list(
                    ChatPrincipal.from(request),
                    operation,
                    outcomeCode,
                    emptyReasonCode,
                    sessionId,
                    citationStatus,
                    page,
                    size);
        }
        return diagnosticsService.list(
                ChatPrincipal.from(request),
                operation,
                outcomeCode,
                emptyReasonCode,
                sessionId,
                requestTraceId,
                citationStatus,
                page,
                size);
    }

    /** 保留已有 Java 调用方的 8 参数控制器方法签名。 */
    public RetrievalTracePageResponse list(
            int page,
            int size,
            String operation,
            String outcomeCode,
            String emptyReasonCode,
            String sessionId,
            String citationStatus,
            HttpServletRequest request) {
        return list(page, size, operation, outcomeCode, emptyReasonCode,
                sessionId, null, citationStatus, request);
    }

    @GetMapping("/{traceId}")
    public RetrievalTraceDetailResponse get(
            @PathVariable UUID traceId,
            HttpServletRequest request) {
        return diagnosticsService.get(ChatPrincipal.from(request), traceId);
    }
}
