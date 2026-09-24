package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ChatSource;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatSessionCoordinator;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.retrieval.RetrievalTraceHeaders;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagChatController 非键控与 SSE 追踪长尾（Batch 632，JaCoCo 驱动）：
 * 无快照键控回合走 mapper.map 归一映射（ask/chat/stream 三入口）、
 * 非键控 ask 的 scope 解析与旧构造器 chat 重载、诊断会话在
 * executeKeyedJson/executeKeyedSse 的挂载与 TRACE_ID 响应头、
 * stream 订阅前同步异常的 chat error 兜底、心跳关闭分支、
 * 无审计服务的清理路径、objectMapper null 配置忽略。
 */
class RagChatControllerNonKeyedSseTailTest {

    private static final UUID TURN_ID =
            UUID.fromString("66666666-6666-6666-6666-666666666666");

    private RagChatService ragChatService;
    private ChatTurnOperationService turnOperationService;
    private ChatCommandMapper commandMapper;
    private ChatExecutionService executionService;
    private ChatSessionCoordinator coordinator;
    private ChatSessionCoordinator.LeaseHandle lease;
    private CollectionRetrievalScopeResolver scopeResolver;
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
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        diagnosticsService = mock(RetrievalDiagnosticsService.class);
        controller = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                new RagSseProperties(),
                scopeResolver,
                mock(AuditLogService.class));
        controller.configureTurnOperationService(turnOperationService);
        controller.configureSessionCoordinator(coordinator);
        controller.configureModeAwareExecution(commandMapper, executionService);
        controller.configureDiagnostics(diagnosticsService);
        controller.configureObjectMapper(new ObjectMapper());
        httpRequest = new MockHttpServletRequest("POST", "/ask");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(httpRequest));
        httpResponse = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private ChatTurnOperation operation(String executionSnapshot) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, ChatPrincipal.local().id(), "key-hash", "fp-hash", 1,
                "session-1", TURN_ID,
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), now.plusSeconds(60),
                1, 1L, 1,
                executionSnapshot, null, null, null, "{}",
                now, now, null);
    }

    private ChatTurnOperationService.Prepared keyedPrepared(
            ChatTurnOperation operation) {
        return new ChatTurnOperationService.Prepared(
                ChatPrincipal.local(), "key-hash", "fp-hash", null,
                operation, true);
    }

    private ChatTurnOperationService.Prepared nonKeyedPrepared() {
        return new ChatTurnOperationService.Prepared(
                ChatPrincipal.local(), null, null, null, null, false);
    }

    private ChatTurnOperationService.Claim claimWithLease(
            ChatTurnOperation operation) throws Exception {
        Constructor<ChatTurnOperationService.Claim> ctor =
                ChatTurnOperationService.Claim.class.getDeclaredConstructor(
                        ChatTurnOperation.class, boolean.class,
                        ChatSessionCoordinator.LeaseHandle.class);
        ctor.setAccessible(true);
        return ctor.newInstance(operation, false, lease);
    }

    private ChatCommand command() {
        return new ChatCommand(
                "问题", "session-1", ChatPrincipal.local(), null,
                null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private void stubKeyedJsonFlow(ChatTurnOperation op) throws Exception {
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(keyedPrepared(op));
        when(turnOperationService.inspectExisting(any())).thenReturn(null);
        when(commandMapper.map(any(), any(), any())).thenReturn(command());
        when(turnOperationService.claim(any(), any(), any(), anyBoolean()))
                .thenReturn(claimWithLease(op));
        when(turnOperationService.commandForClaim(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(executionService.prepareForOperation(
                any(ChatCommand.class), any(), anyBoolean()))
                .thenReturn(mock(ChatExecutionService.PreparedExecution.class));
        when(turnOperationService.completePrepared(
                any(ChatTurnOperationService.Claim.class),
                any(ChatExecutionService.PreparedExecution.class)))
                .thenReturn(ChatResponse.builder().answer("ok").build());
    }

    @Test
    void keyedAskWithoutSnapshotUsesPlainMapperMap() throws Exception {
        stubKeyedJsonFlow(operation(null));

        ResponseEntity<ChatResponse> response = controller.ask(
                new ChatRequest("问题", "session-1"), httpRequest);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("ok", response.getBody().getAnswer());
        verify(commandMapper).map(any(), any(), any());
        verify(executionService).finalizePreparedOperation(any());
    }

    @Test
    void keyedChatWithoutSnapshotUsesPlainMapperMap() throws Exception {
        stubKeyedJsonFlow(operation(null));

        ResponseEntity<ChatResponse> response = controller.chat(
                new ChatRequest("问题", "session-1"), httpRequest);

        assertEquals(200, response.getStatusCode().value());
        verify(commandMapper).map(any(), any(), any());
    }

    @Test
    void keyedStreamWithoutSnapshotUsesPlainMapperMap() throws Exception {
        ChatTurnOperation op = operation(null);
        stubKeyedJsonFlow(op);
        when(turnOperationService.claim(
                any(), any(),
                eq(ChatTurnOperation.Transport.NATIVE_SSE),
                eq(true)))
                .thenReturn(claimWithLease(op));

        SseEmitter emitter = controller.stream(
                new ChatRequest("问题", "session-1"),
                httpRequest,
                httpResponse);

        assertNotNull(emitter);
        verify(commandMapper).map(any(), any(), any());
    }

    @Test
    void nonKeyedAskResolvesScopeAndDelegatesToChatService() {
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(nonKeyedPrepared());
        when(turnOperationService.inspectExisting(any())).thenReturn(null);
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.unscoped());
        when(ragChatService.chat(any(ChatRequest.class),
                any(RetrievalScope.class), isNull()))
                .thenReturn(ChatResponse.builder().answer("非键控回答").build());

        ResponseEntity<ChatResponse> response = controller.ask(
                new ChatRequest("问题", "session-1"), httpRequest);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("非键控回答", response.getBody().getAnswer());
    }

    @Test
    void nonKeyedAskWithoutResolverUsesPlainChatOverload() {
        RagChatController legacy = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                new RagSseProperties(),
                null);
        legacy.configureTurnOperationService(turnOperationService);
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(nonKeyedPrepared());
        when(turnOperationService.inspectExisting(any())).thenReturn(null);
        when(ragChatService.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().answer("旧路径回答").build());

        ResponseEntity<ChatResponse> response = legacy.ask(
                new ChatRequest("问题", "session-1"), httpRequest);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("旧路径回答", response.getBody().getAnswer());
    }

    @Test
    void keyedJsonAttachesTraceSessionAndTraceHeader() throws Exception {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "CHAT", "session-1");
        when(diagnosticsService.isEnabled()).thenReturn(true);
        when(diagnosticsService.createSession(any(), anyString(), anyString()))
                .thenReturn(session);
        stubKeyedJsonFlow(operation(null));

        ResponseEntity<ChatResponse> response = controller.ask(
                new ChatRequest("问题", "session-1"), httpRequest);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(session.traceId().toString(),
                response.getHeaders().getFirst(
                        RetrievalTraceHeaders.TRACE_ID));
        verify(executionService).prepareForOperation(
                any(ChatCommand.class), any(), eq(false));
    }

    @Test
    void keyedSseAttachesTraceResponseHeader() throws Exception {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "CHAT", "session-1");
        when(diagnosticsService.isEnabled()).thenReturn(true);
        when(diagnosticsService.createSession(any(), anyString(), anyString()))
                .thenReturn(session);
        ChatTurnOperation op = operation(null);
        stubKeyedJsonFlow(op);
        when(turnOperationService.claim(
                any(), any(),
                eq(ChatTurnOperation.Transport.NATIVE_SSE),
                eq(true)))
                .thenReturn(claimWithLease(op));

        controller.stream(
                new ChatRequest("问题", "session-1"),
                httpRequest,
                httpResponse);

        assertEquals(session.traceId().toString(),
                httpResponse.getHeader(RetrievalTraceHeaders.TRACE_ID));
        assertEquals(TURN_ID.toString(),
                httpResponse.getHeader("X-RAG-Turn-Id"));
    }

    @Test
    void streamSynchronousSubscriptionFailureSendsChatError() {
        RagSseProperties disabled = new RagSseProperties();
        disabled.setHeartbeatIntervalSeconds(0);
        RagChatController noHeartbeat = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                disabled,
                scopeResolver,
                mock(AuditLogService.class));
        noHeartbeat.configureTurnOperationService(turnOperationService);
        when(ragChatService.chatEvents(any(ChatRequest.class),
                isNull(), isNull()))
                .thenThrow(new IllegalStateException("订阅前同步失败"));

        SseEmitter emitter = noHeartbeat.stream(
                new ChatRequest("问题", "session-1"),
                httpRequest,
                httpResponse);

        assertThrows(IllegalStateException.class,
                () -> emitter.send(SseEmitter.event().data("late")));
    }

    @Test
    void clearHistoryWithoutAuditServiceSkipsAuditLogging() {
        RagChatController legacy = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                new RagSseProperties(),
                null);
        legacy.configureSessionCoordinator(coordinator);
        when(coordinator.clearSession(any(ChatPrincipal.class), anyString()))
                .thenReturn(2);

        ResponseEntity<com.springairag.api.dto.ClearHistoryResponse>
                response = legacy.clearHistory("session-1", httpRequest);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().deletedCount());
    }

    @Test
    void configureObjectMapperIgnoresNullInstance() {
        controller.configureObjectMapper(null);
        controller.configureObjectMapper(new ObjectMapper());
    }
}
