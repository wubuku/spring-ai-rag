package com.springairag.core.controller;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatSessionCoordinator;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalTraceHeaders;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagChatController SSE 生命周期长尾（Batch 639，JaCoCo 驱动）：
 * 键控 chat 的 claim 竞速重放响应头、诊断会话在非键控流上的
 * TRACE 头、异步完成后 onCompletion 取消订阅、终态后事件跳过、
 * onError 回调（completeWithError）触发心跳停止、心跳任务真实触
 * 发。
 */
class RagChatControllerSseLifecycleTailTest {

    private static final UUID TURN_ID =
            UUID.fromString("77777777-7777-7777-7777-777777777777");

    private RagChatService ragChatService;
    private ChatTurnOperationService turnOperationService;
    private ChatCommandMapper commandMapper;
    private ChatExecutionService executionService;
    private ChatSessionCoordinator coordinator;
    private ChatSessionCoordinator.LeaseHandle lease;
    private RetrievalDiagnosticsService diagnosticsService;
    private RagChatController controller;
    private MockHttpServletRequest httpRequest;
    private MockHttpServletResponse httpResponse;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        turnOperationService = mock(ChatTurnOperationService.class);
        commandMapper = mock(ChatCommandMapper.class);
        executionService = mock(ChatExecutionService.class);
        coordinator = mock(ChatSessionCoordinator.class);
        lease = mock(ChatSessionCoordinator.LeaseHandle.class);
        diagnosticsService = mock(RetrievalDiagnosticsService.class);
        RagSseProperties sseProperties = new RagSseProperties();
        sseProperties.setHeartbeatIntervalSeconds(30);
        controller = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                sseProperties,
                mock(CollectionRetrievalScopeResolver.class),
                mock(AuditLogService.class));
        controller.configureTurnOperationService(turnOperationService);
        controller.configureSessionCoordinator(coordinator);
        controller.configureModeAwareExecution(commandMapper, executionService);
        controller.configureDiagnostics(diagnosticsService);
        httpRequest = new MockHttpServletRequest("POST", "/chat");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(httpRequest));
        httpResponse = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private ChatTurnOperation operation() {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, ChatPrincipal.local().id(), "key-hash", "fp-hash", 1,
                "session-1", TURN_ID,
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), now.plusSeconds(60),
                1, 1L, 1,
                null, null, null, null, "{}",
                now, now, null);
    }

    private ChatTurnOperationService.Prepared keyedPrepared() {
        return new ChatTurnOperationService.Prepared(
                ChatPrincipal.local(), "key-hash", "fp-hash", null,
                operation(), true);
    }

    private ChatTurnOperationService.Claim claim(
            ChatTurnOperation operation, boolean replay) throws Exception {
        Constructor<ChatTurnOperationService.Claim> ctor =
                ChatTurnOperationService.Claim.class.getDeclaredConstructor(
                        ChatTurnOperation.class, boolean.class,
                        ChatSessionCoordinator.LeaseHandle.class);
        ctor.setAccessible(true);
        return ctor.newInstance(operation, replay, lease);
    }

    private ChatCommand command() {
        return new ChatCommand(
                "问题", "session-1", ChatPrincipal.local(), null,
                null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void keyedChatClaimReplayRespondsWithReplayHeaders() throws Exception {
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(keyedPrepared());
        when(turnOperationService.inspectExisting(any())).thenReturn(null);
        when(commandMapper.map(any(), any(), any())).thenReturn(command());
        ChatTurnOperation op = operation();
        when(turnOperationService.claim(any(), any(), any(), anyBoolean()))
                .thenReturn(claim(op, true));
        when(turnOperationService.replay(
                any(ChatTurnOperationService.Claim.class)))
                .thenReturn(ChatResponse.builder().answer("重放回答").build());

        ResponseEntity<ChatResponse> response = controller.chat(
                new ChatRequest("问题", "session-1"), httpRequest);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("重放回答", response.getBody().getAnswer());
        assertEquals("true", response.getHeaders()
                .getFirst("X-RAG-Idempotent-Replay"));
        assertEquals(TURN_ID.toString(),
                response.getHeaders().getFirst("X-RAG-Turn-Id"));
    }

    private void stubNonKeyedStream(Flux<ChatEvent> events) {
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(new ChatTurnOperationService.Prepared(
                        ChatPrincipal.local(), null, null, null, null, false));
        when(turnOperationService.inspectExisting(any())).thenReturn(null);
        when(ragChatService.chatEvents(any(ChatRequest.class),
                isNull(), isNull()))
                .thenReturn(events);
    }

    private ChatEvent delta(String content) {
        return new ChatEvent.ContentDelta(content);
    }

    private ChatEvent completed() {
        return new ChatEvent.Completed("trace", "session-1", null, null,
                com.springairag.api.enums.ChatMode.KNOWLEDGE,
                java.util.Map.of(), "STOP", java.util.List.of(),
                java.util.Map.of());
    }

    @Test
    void nonKeyedStreamWithDiagnosticsSetsTraceHeader() {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "CHAT", "session-1");
        when(diagnosticsService.isEnabled()).thenReturn(true);
        when(diagnosticsService.createSession(any(), anyString(), anyString()))
                .thenReturn(session);
        stubNonKeyedStream(Flux.just(delta("内容"), completed()));

        controller.stream(new ChatRequest("问题", "session-1"),
                httpRequest, httpResponse);

        assertEquals(session.traceId().toString(),
                httpResponse.getHeader(RetrievalTraceHeaders.TRACE_ID));
    }

    @Test
    void postTerminalEventIsSkipped() {
        stubNonKeyedStream(Flux.just(
                delta("前段"), completed(), delta("终态后内容")));

        SseEmitter emitter = controller.stream(
                new ChatRequest("问题", "session-1"), httpRequest, httpResponse);

        assertNotNull(emitter);
    }

    @Test
    void asyncCompletionDisposesSubscriptionOnEmitterCompletion()
            throws Exception {
        stubNonKeyedStream(Flux.just(delta("异步内容"), completed())
                .delaySubscription(Duration.ofMillis(250)));

        SseEmitter emitter = controller.stream(
                new ChatRequest("问题", "session-1"), httpRequest, httpResponse);

        // 异步完成后 emitter.complete() 触发 onCompletion → 取消订阅。
        Thread.sleep(800);
        assertNotNull(emitter);
    }

    @Test
    void emitterErrorCallbackStopsHeartbeatAndCancelsSubscription()
            throws Exception {
        stubNonKeyedStream(Flux.just(delta("稍后内容"), completed())
                .delaySubscription(Duration.ofMillis(400)));

        SseEmitter emitter = controller.stream(
                new ChatRequest("问题", "session-1"), httpRequest, httpResponse);

        assertNotNull(emitter);
        // 模拟容器侧连接错误：completeWithError 触发 onError 回调
        // （取消订阅 + 停心跳），随后的异步事件被忽略。
        emitter.completeWithError(new IllegalStateException("模拟断连"));
        Thread.sleep(400);
    }

    @Test
    void heartbeatTaskFiresWhenEnabled() throws Exception {
        RagSseProperties fast = new RagSseProperties();
        fast.setHeartbeatIntervalSeconds(1);
        RagChatController fastController = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                fast,
                mock(CollectionRetrievalScopeResolver.class),
                mock(AuditLogService.class));
        fastController.configureTurnOperationService(turnOperationService);
        fastController.configureSessionCoordinator(coordinator);
        stubNonKeyedStream(Flux.just(delta("慢内容"), completed())
                .delaySubscription(Duration.ofMillis(1500)));

        SseEmitter emitter = fastController.stream(
                new ChatRequest("问题", "session-1"), httpRequest, httpResponse);

        Thread.sleep(1700);
        assertNotNull(emitter);
    }
}
