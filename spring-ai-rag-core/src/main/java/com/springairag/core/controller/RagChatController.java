package com.springairag.core.controller;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ClearHistoryResponse;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.util.SseEmitters;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * RAG chat controller.
 *
 * <p>Provides both non-streaming and streaming (SSE) Q&A interfaces, plus session history management.
 * Supports domainId parameter to select domain extensions.
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/chat")
@Tag(name = "RAG Chat", description = "RAG Q&A interface (non-streaming + SSE streaming)")
public class RagChatController {

    private static final Logger log = LoggerFactory.getLogger(RagChatController.class);

    private final RagChatService ragChatService;
    private final RagChatHistoryRepository historyRepository;
    private final ChatExportService chatExportService;
    private final RagSseProperties sseProperties;
    private AuditLogService auditLogService;  // optional: null when RagAuditLogRepository unavailable

    public RagChatController(RagChatService ragChatService,
                             RagChatHistoryRepository historyRepository,
                             ChatExportService chatExportService,
                             RagSseProperties sseProperties,
                             @Autowired(required = false) AuditLogService auditLogService) {
        this.ragChatService = ragChatService;
        this.historyRepository = historyRepository;
        this.chatExportService = chatExportService;
        this.sseProperties = sseProperties;
        this.auditLogService = auditLogService;
    }

    /**
     * RAG Q&A (non-streaming).
     *
     * <p>Request body may include an optional domainId field to specify a domain extension.
     */
    @Operation(summary = "RAG Q&A (non-streaming)", description = "Send a question and receive a complete answer. Supports domainId to specify domain extension.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Q&A succeeded, returns complete answer"),
            @ApiResponse(responseCode = "400", description = "Request parameter validation failed")
    })
    @PostMapping("/ask")
    @Timed(value = "rag.chat.ask", description = "RAG non-streaming chat", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<ChatResponse> ask(@Valid @RequestBody ChatRequest request) {
        // First message in a session has null sessionId; auto-generate
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
     * RAG Q&A (non-streaming) — /chat is an alias for /ask, unified entry point.
     */
    @Operation(summary = "RAG Q&A (non-streaming)", description = "Send a question and receive a complete answer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Q&A succeeded, returns complete answer"),
            @ApiResponse(responseCode = "400", description = "Request parameter validation failed")
    })
    @PostMapping
    @Timed(value = "rag.chat.non-stream", description = "RAG non-streaming chat", percentiles = {0.5, 0.95, 0.99})
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId(java.util.UUID.randomUUID().toString());
        }
        log.info("RAG chat: sessionId={}, domain={}, message={}",
                request.getSessionId(), request.getDomainId(),
                request.getMessage().length() > 100 ? request.getMessage().substring(0, 100) + "..." : request.getMessage());

        ChatResponse response = ragChatService.chat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * RAG Q&A (streaming, SSE).
     */
    @Operation(summary = "RAG Q&A (streaming SSE)", description = "Stream answer content, pushing chunks via Server-Sent Events.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE streaming response, pushing answer chunks"),
            @ApiResponse(responseCode = "400", description = "Request parameter validation failed")
    })
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Timed(value = "rag.chat.stream", description = "RAG streaming chat", percentiles = {0.5, 0.95, 0.99})
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        // First message in a session has null sessionId; auto-generate
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId(java.util.UUID.randomUUID().toString());
        }
        log.info("RAG stream: sessionId={}, domain={}, message={}",
                request.getSessionId(), request.getDomainId(),
                request.getMessage().length() > 100 ? request.getMessage().substring(0, 100) + "..." : request.getMessage());

        String traceId = MDC.get(RequestTraceFilter.TRACE_ID_KEY);
        SseEmitter emitter = SseEmitters.create();

        // SSE protocol: OpenAI-compatible format
        // Main channel: data:{"choices":[{"delta":{"content":"..."}}]}
        // done:    event:done + data:{"traceId":"...","status":"complete"}
        // heartbeat: comment (: heartbeat\n\n) if sse.heartbeatIntervalSeconds > 0

        final SseEmitter finalEmitter = emitter;
        final String finalTraceId = traceId;

        final ScheduledFuture<?>[] heartbeatTask = new ScheduledFuture<?>[1];
        final ScheduledExecutorService[] heartbeatScheduler = new ScheduledExecutorService[1];

        if (sseProperties != null && sseProperties.isHeartbeatEnabled()) {
            int interval = sseProperties.getHeartbeatIntervalSeconds();
            heartbeatScheduler[0] = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });
            heartbeatTask[0] = heartbeatScheduler[0].scheduleAtFixedRate(
                    () -> SseEmitters.sendHeartbeat(finalEmitter),
                    interval, interval, TimeUnit.SECONDS);
            log.debug("SSE heartbeat enabled: {}s interval", interval);
        }

        ragChatService.chatStream(request.getMessage(), request.getSessionId(), request.getDomainId())
                .subscribe(
                        chunk -> {
                            String json = "{\"choices\":[{\"delta\":{\"content\":\"" + SseEmitters.escapeJson(chunk) + "\"}}]}";
                            SseEmitters.sendRaw(finalEmitter, null, json, "chat chunk");
                        },
                        error -> {
                            if (heartbeatTask[0] != null) heartbeatTask[0].cancel(false);
                            if (heartbeatScheduler[0] != null) heartbeatScheduler[0].shutdown();
                            finalEmitter.completeWithError(error);
                        },
                        () -> {
                            if (heartbeatTask[0] != null) heartbeatTask[0].cancel(false);
                            if (heartbeatScheduler[0] != null) heartbeatScheduler[0].shutdown();
                            String doneJson = "{\"traceId\":\"" + (finalTraceId != null ? finalTraceId : "") + "\",\"status\":\"complete\"}";
                            SseEmitters.sendRaw(finalEmitter, "done", doneJson, "chat done");
                        }
                );

        return emitter;
    }

    /**
     * Get session history.
     */
    @Operation(summary = "Get session history", description = "Query chat history for the specified session, returned in reverse chronological order.")
    @ApiResponse(responseCode = "200", description = "Session history records returned")
    @GetMapping("/history/{sessionId}")
    public ResponseEntity<List<Map<String, Object>>> getHistory(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> history = historyRepository.findBySessionId(sessionId, limit);
        return ResponseEntity.ok(history);
    }

    /**
     * Clear session history.
     *
     * <p>Deletes records from the rag_chat_history table (business audit table).
     * The spring_ai_chat_memory table is managed by Spring AI ChatMemory and is not affected by this endpoint.
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
     * Export session history (JSON or Markdown format download).
     *
     * @param sessionId Session ID
     * @param format Export format: json or md (default json)
     * @param limit Maximum records to export (0 = unlimited)
     * @return File download response
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
