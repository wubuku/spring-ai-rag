package com.springairag.core.controller;

import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ClearHistoryResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalScopeSummary;
import com.springairag.core.retrieval.RetrievalTraceHeaders;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatSessionCoordinator;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.retrieval.RetrievalTraceHeaders;
import com.springairag.core.chat.SessionIdValidator;
import com.springairag.core.entity.RagApiKey;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.util.SseEmitters;
import com.springairag.core.versioning.ApiVersion;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
import jakarta.servlet.http.HttpServletResponse;
import com.springairag.core.filter.RequestTraceFilter;

import org.slf4j.MDC;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletResponse;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AuditLogService auditLogService;  // optional: null when RagAuditLogRepository unavailable
    private final CollectionRetrievalScopeResolver retrievalScopeResolver;
    private ChatSessionCoordinator sessionCoordinator;
    private RetrievalDiagnosticsService diagnosticsService;

    @Autowired
    public RagChatController(RagChatService ragChatService,
                             RagChatHistoryRepository historyRepository,
                             ChatExportService chatExportService,
                             RagSseProperties sseProperties,
                             CollectionRetrievalScopeResolver retrievalScopeResolver,
                             @Autowired(required = false) AuditLogService auditLogService) {
        this.ragChatService = ragChatService;
        this.historyRepository = historyRepository;
        this.chatExportService = chatExportService;
        this.sseProperties = sseProperties;
        this.retrievalScopeResolver = retrievalScopeResolver;
        this.auditLogService = auditLogService;
    }

    @Autowired(required = false)
    void configureSessionCoordinator(
            ChatSessionCoordinator sessionCoordinator) {
        this.sessionCoordinator = sessionCoordinator;
    }

    @Autowired(required = false)
    void configureDiagnostics(RetrievalDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    public RagChatController(RagChatService ragChatService,
                             RagChatHistoryRepository historyRepository,
                             ChatExportService chatExportService,
                             RagSseProperties sseProperties,
                             AuditLogService auditLogService) {
        this(ragChatService, historyRepository, chatExportService, sseProperties,
                null, auditLogService);
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
    public ResponseEntity<ChatResponse> ask(@Valid @RequestBody ChatRequest request,
                                            HttpServletRequest httpRequest) {
        // First message in a session has null sessionId; auto-generate
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId(java.util.UUID.randomUUID().toString());
        }
        RetrievalScope scope = resolveScope(request, httpRequest);
        log.info("RAG ask: sessionId={}, domain={}, collectionIds={}, message={}",
                request.getSessionId(), request.getDomainId(), request.getCollectionIds(),
                request.getMessage().length() > 100 ? request.getMessage().substring(0, 100) + "..." : request.getMessage());

        RetrievalTraceSession session = beginChatTrace(request, scope, httpRequest);
        ChatResponse response = scope != null
                ? ragChatService.chat(request, scope, session)
                : ragChatService.chat(request);
        return traced(response, session);
    }

    ResponseEntity<ChatResponse> ask(ChatRequest request) {
        return ask(request, null);
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
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request,
                                             HttpServletRequest httpRequest) {
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId(java.util.UUID.randomUUID().toString());
        }
        RetrievalScope scope = resolveScope(request, httpRequest);
        log.info("RAG chat: sessionId={}, domain={}, message={}",
                request.getSessionId(), request.getDomainId(),
                request.getMessage().length() > 100 ? request.getMessage().substring(0, 100) + "..." : request.getMessage());

        RetrievalTraceSession session = beginChatTrace(request, scope, httpRequest);
        ChatResponse response = scope != null
                ? ragChatService.chat(request, scope, session)
                : ragChatService.chat(request);
        return traced(response, session);
    }

    ResponseEntity<ChatResponse> chat(ChatRequest request) {
        return chat(request, null);
    }

    /**
     * Holds heartbeat scheduler resources.
     */
    private record HeartbeatHandles(ScheduledFuture<?> task, ScheduledExecutorService scheduler) {
        void stop() {
            if (task != null) task.cancel(false);
            if (scheduler != null) scheduler.shutdown();
        }
    }

    /**
     * Starts SSE heartbeat scheduler if enabled in configuration.
     */
    private HeartbeatHandles startHeartbeat(SseEmitter emitter) {
        if (sseProperties == null || !sseProperties.isHeartbeatEnabled()) {
            return new HeartbeatHandles(null, null);
        }
        int interval = sseProperties.getHeartbeatIntervalSeconds();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                () -> SseEmitters.sendHeartbeat(emitter),
                interval, interval, TimeUnit.SECONDS);
        log.debug("SSE heartbeat enabled: {}s interval", interval);
        return new HeartbeatHandles(task, scheduler);
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
    public SseEmitter stream(@Valid @RequestBody ChatRequest request,
                               HttpServletRequest httpRequest,
                               HttpServletResponse httpResponse) {
        // First message in a session has null sessionId; auto-generate
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId(java.util.UUID.randomUUID().toString());
        }
        RetrievalScope scope = resolveScope(request, httpRequest);
        String sessionId = request.getSessionId();
        log.info("RAG stream: sessionId={}, domain={}, collectionIds={}, message={}",
                sessionId, request.getDomainId(), request.getCollectionIds(),
                request.getMessage().length() > 100 ? request.getMessage().substring(0, 100) + "..." : request.getMessage());

        RetrievalTraceSession session = beginChatTrace(request, scope, httpRequest);
        if (session != null && httpResponse != null) {
            httpResponse.setHeader(
                    RetrievalTraceHeaders.TRACE_ID, session.traceId().toString());
        }
        SseEmitter emitter = SseEmitters.create();
        String traceId = MDC.get(RequestTraceFilter.TRACE_ID_KEY);
        HeartbeatHandles heartbeat = startHeartbeat(emitter);
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Runnable cancelSubscription = () -> {
            Disposable disposable = subscription.getAndSet(null);
            if (disposable != null) {
                disposable.dispose();
            }
        };
        emitter.onCompletion(cancelSubscription);
        emitter.onTimeout(() -> {
            cancelSubscription.run();
            heartbeat.stop();
        });
        emitter.onError(error -> {
            cancelSubscription.run();
            heartbeat.stop();
        });

        try {
            Disposable disposable = ragChatService.chatEvents(request, scope, session)
                    .subscribe(event -> {
                        if (terminal.get()) {
                            return;
                        }
                        sendChatEvent(emitter, event, traceId, sessionId);
                        if (event instanceof ChatEvent.Completed) {
                            if (terminal.compareAndSet(false, true)) {
                                heartbeat.stop();
                                emitter.complete();
                            }
                        } else if (event instanceof ChatEvent.Failed) {
                            terminal.compareAndSet(false, true);
                            heartbeat.stop();
                        }
                    },
                            error -> {
                                if (terminal.compareAndSet(false, true)) {
                                    heartbeat.stop();
                                    sendChatError(
                                            emitter, error, traceId, sessionId);
                                }
                            },
                            () -> {
                                if (terminal.compareAndSet(false, true)) {
                                    heartbeat.stop();
                                    emitter.complete();
                                }
                            });
            subscription.set(disposable);
            if (terminal.get()) {
                disposable.dispose();
            }
        } catch (RuntimeException error) {
            if (terminal.compareAndSet(false, true)) {
                heartbeat.stop();
                sendChatError(emitter, error, traceId, sessionId);
            }
        }

        return emitter;
    }

    private void sendChatEvent(
            SseEmitter emitter,
            ChatEvent event,
            String traceId,
            String sessionId) {
        if (event instanceof ChatEvent.ContentDelta delta) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("choices", List.of(Map.of(
                    "delta", Map.of("content", delta.content()))));
            SseEmitters.sendProgress(emitter, "content", payload, "chat content");
        } else if (event instanceof ChatEvent.ToolStarted started) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", started.tool());
            putIfPresent(payload, "toolCallId", started.toolCallId());
            putIfPresent(payload, "query", started.query());
            SseEmitters.sendProgress(
                    emitter, "tool_start", payload, "chat tool start");
        } else if (event instanceof ChatEvent.ToolFinished finished) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", finished.tool());
            payload.put("resultCount", finished.resultCount());
            payload.put("elapsedMs", finished.elapsedMs());
            putIfPresent(payload, "toolCallId", finished.toolCallId());
            SseEmitters.sendProgress(
                    emitter, "tool_result", payload, "chat tool result");
        } else if (event instanceof ChatEvent.SourcesAvailable sources) {
            SseEmitters.sendProgress(
                    emitter,
                    "sources",
                    Map.of(
                            "sessionId", sources.sessionId(),
                            "sources", sources.sources()),
                    "chat sources");
        } else if (event instanceof ChatEvent.Completed completed) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", firstNonBlank(
                    completed.traceId(), traceId));
            payload.put("sessionId", completed.sessionId());
            payload.put("requestedModel", completed.requestedModel());
            payload.put("resolvedModel", completed.resolvedModel());
            payload.put("mode", completed.mode());
            payload.put("usage", completed.usage());
            payload.put("finishReason", completed.finishReason());
            payload.put("stepMetrics", completed.stepMetrics());
            payload.put("metadata", completed.metadata());
            payload.put("status", "complete");
            if (completed.metadata() != null
                    && completed.metadata().get("retrievalTraceId") != null) {
                payload.put("retrievalTraceId",
                        completed.metadata().get("retrievalTraceId"));
            }
            SseEmitters.sendProgress(emitter, "done", payload, "chat done");
        } else if (event instanceof ChatEvent.Failed failed) {
            sendChatError(
                    emitter,
                    new ChatStreamFailure(
                            failed.code(), failed.message()),
                    firstNonBlank(failed.traceId(), traceId),
                    firstNonBlank(failed.sessionId(), sessionId));
        }
    }

    private void sendChatError(
            SseEmitter emitter,
            Throwable error,
            String traceId,
            String sessionId) {
        Map<String, Object> errorPayload = new LinkedHashMap<>();
        errorPayload.put("message", error.getMessage() != null
                ? error.getMessage() : "Chat stream failed");
        if (error instanceof ChatStreamFailure failure
                && failure.code != null) {
            errorPayload.put("code", failure.code);
        } else if (error instanceof RagException ragException) {
            errorPayload.put("code", ragException.getErrorCode());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", errorPayload);
        putIfPresent(payload, "traceId", traceId);
        putIfPresent(payload, "sessionId", sessionId);
        SseEmitters.sendProgress(emitter, "error", payload, "chat error");
        emitter.complete();
    }

    private void putIfPresent(
            Map<String, Object> payload,
            String key,
            Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private static final class ChatStreamFailure extends RuntimeException {
        private final String code;

        private ChatStreamFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /**
     * Get session history.
     */
    @Operation(summary = "Get session history", description = "Query chat history for the specified session, returned in reverse chronological order.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Session history records returned")
    })
    @GetMapping("/history/{sessionId}")
    public ResponseEntity<List<ChatHistoryResponse>> getHistory(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest httpRequest) {
        String validSession = SessionIdValidator.resolve(sessionId);
        List<ChatHistoryResponse> history =
                historyRepository.findByPrincipalAndSession(
                        ChatPrincipal.from(httpRequest),
                        validSession,
                        limit);
        if (history.isEmpty()) {
            throw new RagException(
                    ErrorCode.SESSION_NOT_FOUND,
                    "Chat session was not found");
        }
        return ResponseEntity.ok(history);
    }

    ResponseEntity<List<ChatHistoryResponse>> getHistory(
            String sessionId,
            int limit) {
        return ResponseEntity.ok(
                historyRepository.findBySessionId(sessionId, limit));
    }

    /**
     * Clear session history.
     *
     * <p>Deletes the principal-owned business history and its Spring AI memory
     * snapshot as one coordinated session operation.
     */
    @DeleteMapping("/history/{sessionId}")
    public ResponseEntity<ClearHistoryResponse> clearHistory(
            @PathVariable String sessionId,
            HttpServletRequest httpRequest) {
        String validSession = SessionIdValidator.resolve(sessionId);
        ChatPrincipal principal = ChatPrincipal.from(httpRequest);
        log.info("Clearing chat history for session: {}", validSession);
        int deleted = sessionCoordinator != null
                ? sessionCoordinator.clearSession(principal, validSession)
                : historyRepository.deleteByPrincipalAndSession(
                        principal, validSession);
        if (deleted == 0) {
            throw new RagException(
                    ErrorCode.SESSION_NOT_FOUND,
                    "Chat session was not found");
        }

        auditDelete(AuditLogService.ENTITY_CHAT_HISTORY,
                validSession,
                "Chat history cleared: " + deleted + " messages");

        return ResponseEntity.ok(ClearHistoryResponse.of(validSession, deleted));
    }

    ResponseEntity<ClearHistoryResponse> clearHistory(String sessionId) {
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
            @RequestParam(defaultValue = "0") int limit,
            HttpServletRequest httpRequest) {

        if (!format.equalsIgnoreCase("json") && !format.equalsIgnoreCase("md")) {
            throw new IllegalArgumentException("format must be 'json' or 'md', got: " + format);
        }

        String validSession = SessionIdValidator.resolve(sessionId);
        ChatPrincipal principal = ChatPrincipal.from(httpRequest);
        log.info("Exporting chat history for session: {}, format: {}, limit: {}", validSession, format, limit);

        byte[] content;
        String contentType;
        String filename;

        if (format.equalsIgnoreCase("md")) {
            content = chatExportService.exportAsMarkdown(
                    principal, validSession, limit);
            contentType = "text/markdown; charset=utf-8";
            filename = validSession + ".md";
        } else {
            content = chatExportService.exportAsJson(
                    principal, validSession, limit);
            contentType = "application/json; charset=utf-8";
            filename = validSession + ".json";
        }

        ByteArrayResource resource = new ByteArrayResource(content);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                .body(resource);
    }

    ResponseEntity<ByteArrayResource> exportHistory(
            String sessionId,
            String format,
            int limit) {
        if (!format.equalsIgnoreCase("json") && !format.equalsIgnoreCase("md")) {
            throw new IllegalArgumentException(
                    "format must be 'json' or 'md', got: " + format);
        }
        byte[] content = format.equalsIgnoreCase("md")
                ? chatExportService.exportAsMarkdown(sessionId, limit)
                : chatExportService.exportAsJson(sessionId, limit);
        String contentType = format.equalsIgnoreCase("md")
                ? "text/markdown; charset=utf-8"
                : "application/json; charset=utf-8";
        String extension = format.equalsIgnoreCase("md") ? ".md" : ".json";
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"" + sessionId + extension + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(new ByteArrayResource(content));
    }

    // Null-safe audit logging helper
    private void auditDelete(String entityType, String entityId, String message) {
        if (auditLogService != null) auditLogService.logDelete(entityType, entityId, message);
    }

    private RetrievalScope resolveScope(
            ChatRequest request, HttpServletRequest httpRequest) {
        if (request.getMode() == ChatMode.PLAIN) {
            return RetrievalScope.unscoped();
        }
        RagApiKey key = ApiKeyCollectionAccess.currentKey(httpRequest);
        if (retrievalScopeResolver != null) {
            return retrievalScopeResolver.resolve(
                    request.getCollectionScopeMode(),
                    request.getCollectionIds(),
                    request.getCollectionKeys(),
                    request.getDocumentIds(),
                    null,
                    key);
        }
        // 仅供旧 Java 构造器测试兼容；Spring 生产构造器总是注入新 resolver。
        request.setCollectionIds(ApiKeyCollectionAccess.resolveCollectionIds(
                request.getCollectionIds(), key));
        return null;
    }

    private RetrievalTraceSession beginChatTrace(
            ChatRequest request,
            RetrievalScope scope,
            HttpServletRequest httpRequest) {
        if (diagnosticsService == null || !diagnosticsService.isEnabled()) {
            return null;
        }
        try {
            RetrievalTraceSession session = diagnosticsService.createSession(
                    ChatPrincipal.from(httpRequest),
                    RetrievalTraceHeaders.OPERATION_CHAT,
                    request.getSessionId());
            RetrievalFilters filters = new com.springairag.core.retrieval.RetrievalFilterValidator()
                    .validate(request.getFilters());
            session.attachScope(
                    RetrievalScopeSummary.from(
                            request.getCollectionScopeMode(),
                            scope,
                            request.getCollectionKeys(),
                            filters,
                            null),
                    filters);
            return session;
        } catch (Exception e) {
            log.warn("Retrieval diagnostics failed to start chat trace: {}", e.getMessage());
            return null;
        }
    }

    private ResponseEntity<ChatResponse> traced(
            ChatResponse response,
            RetrievalTraceSession session) {
        if (session == null) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.ok()
                .header(RetrievalTraceHeaders.TRACE_ID, session.traceId().toString())
                .body(response);
    }

    SseEmitter stream(ChatRequest request) {
        return stream(request, null, null);
    }

    SseEmitter stream(ChatRequest request, HttpServletRequest httpRequest) {
        return stream(request, httpRequest, null);
    }

}
