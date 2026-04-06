package com.springairag.core.controller;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ClearHistoryResponse;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.config.RagChatService;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.versioning.ApiVersion;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.springairag.core.filter.RequestTraceFilter;

import org.slf4j.MDC;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * RAG 聊天控制器
 *
 * <p>提供非流式和流式（SSE）两种问答接口，以及会话历史管理。
 * 支持通过 domainId 参数选择领域扩展。
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/chat")
@Tag(name = "RAG Chat", description = "RAG 问答接口（非流式 + SSE 流式）")
public class RagChatController {

    private static final Logger log = LoggerFactory.getLogger(RagChatController.class);

    private final RagChatService ragChatService;
    private final RagChatHistoryRepository historyRepository;
    private final ChatExportService chatExportService;
    private AuditLogService auditLogService;  // optional: null when RagAuditLogRepository unavailable

    public RagChatController(RagChatService ragChatService,
                             RagChatHistoryRepository historyRepository,
                             ChatExportService chatExportService,
                             @Autowired(required = false) AuditLogService auditLogService) {
        this.ragChatService = ragChatService;
        this.historyRepository = historyRepository;
        this.chatExportService = chatExportService;
        this.auditLogService = auditLogService;
    }

    /**
     * RAG 问答（非流式）
     *
     * <p>请求体中可选 domainId 字段指定领域扩展。
     */
    @Operation(summary = "RAG 问答（非流式）", description = "发送问题，返回完整回答。支持 domainId 指定领域扩展。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "问答成功，返回完整回答"),
            @ApiResponse(responseCode = "400", description = "请求参数校验失败")
    })
    @PostMapping("/ask")
    @Timed(value = "rag.chat.ask", description = "RAG non-streaming chat", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<ChatResponse> ask(@Valid @RequestBody ChatRequest request) {
        // 首次对话时 sessionId 为空，自动生成
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId(java.util.UUID.randomUUID().toString());
        }
        log.info("RAG ask: sessionId={}, domain={}, message={}",
                request.getSessionId(), request.getDomainId(),
                request.getMessage().length() > 100 ? request.getMessage().substring(0, 100) + "..." : request.getMessage());

        ChatResponse response = ragChatService.chat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * RAG 问答（流式，SSE）
     */
    @Operation(summary = "RAG 问答（流式 SSE）", description = "流式返回回答内容，通过 Server-Sent Events 逐块推送。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE 流式响应，逐块推送回答内容"),
            @ApiResponse(responseCode = "400", description = "请求参数校验失败")
    })
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Timed(value = "rag.chat.stream", description = "RAG streaming chat", percentiles = {0.5, 0.95, 0.99})
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        // 首次对话时 sessionId 为空，自动生成
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId(java.util.UUID.randomUUID().toString());
        }
        log.info("RAG stream: sessionId={}, domain={}, message={}",
                request.getSessionId(), request.getDomainId(),
                request.getMessage().length() > 100 ? request.getMessage().substring(0, 100) + "..." : request.getMessage());

        String traceId = MDC.get(RequestTraceFilter.TRACE_ID_KEY);
        SseEmitter emitter = new SseEmitter(0L); // 无超时

        // 发送 traceId 事件，使流式响应可全链路追踪
        try {
            emitter.send(SseEmitter.event().name("trace").data(traceId != null ? traceId : ""));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        ragChatService.chatStream(request.getMessage(), request.getSessionId(), request.getDomainId())
                .subscribe(
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        () -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data("{\"traceId\":\"" + (traceId != null ? traceId : "") + "\",\"status\":\"complete\"}"));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        }
                );

        return emitter;
    }

    /**
     * 获取会话历史
     */
    @Operation(summary = "获取会话历史", description = "查询指定会话的聊天记录，按时间倒序返回。")
    @ApiResponse(responseCode = "200", description = "返回会话历史记录列表")
    @GetMapping("/history/{sessionId}")
    public ResponseEntity<List<Map<String, Object>>> getHistory(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> history = historyRepository.findBySessionId(sessionId, limit);
        return ResponseEntity.ok(history);
    }

    /**
     * 清空会话历史
     *
     * <p>删除 rag_chat_history 表中的记录（业务审计表）。
     * spring_ai_chat_memory 表由 Spring AI ChatMemory 管理，不受此接口影响。
     */
    @DeleteMapping("/history/{sessionId}")
    public ResponseEntity<ClearHistoryResponse> clearHistory(@PathVariable String sessionId) {
        log.info("Clearing chat history for session: {}", sessionId);
        int deleted = historyRepository.deleteBySessionId(sessionId);

        auditDelete(AuditLogService.ENTITY_CHAT_HISTORY,
                sessionId,
                "Chat history cleared: " + deleted + " messages");

        return ResponseEntity.ok(ClearHistoryResponse.of(sessionId, deleted));
    }

    /**
     * 导出会话历史（JSON 或 Markdown 格式下载）
     *
     * @param sessionId 会话 ID
     * @param format 导出格式: json 或 md（默认 json）
     * @param limit 最大导出记录数（0 = 不限）
     * @return 文件下载响应
     */
    @Operation(summary = "Export chat history", description = "Download session history as JSON or Markdown file.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "File download with appropriate Content-Type"),
            @ApiResponse(responseCode = "400", description = "Invalid format parameter (must be json or md)")
    })
    @GetMapping("/export/{sessionId}")
    public ResponseEntity<org.springframework.core.io.ByteArrayResource> exportHistory(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "json") String format,
            @RequestParam(defaultValue = "0") int limit) {

        if (!format.equalsIgnoreCase("json") && !format.equalsIgnoreCase("md")) {
            throw new IllegalArgumentException("format must be 'json' or 'md', got: " + format);
        }

        log.info("Exporting chat history for session: {}, format: {}, limit: {}", sessionId, format, limit);

        byte[] content;
        String contentType;
        String filename;

        if (format.equalsIgnoreCase("md")) {
            content = chatExportService.exportAsMarkdown(sessionId, limit);
            contentType = "text/markdown; charset=utf-8";
            filename = sessionId + ".md";
        } else {
            content = chatExportService.exportAsJson(sessionId, limit);
            contentType = "application/json; charset=utf-8";
            filename = sessionId + ".json";
        }

        ByteArrayResource resource = new ByteArrayResource(content);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                .body(resource);
    }

    // Null-safe audit logging helper
    private void auditDelete(String entityType, String entityId, String message) {
        if (auditLogService != null) auditLogService.logDelete(entityType, entityId, message);
    }
}
