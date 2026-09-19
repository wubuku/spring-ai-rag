package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ClearHistoryResponse;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatSessionCoordinator;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.config.RagChatService;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.ChatExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagChatController 心跳/清史/幂等响应长尾（Batch 540，JaCoCo 驱
 * 动）：startHeartbeat 关闭与启用两分支、clearHistory 经协调器与
 * 空删除拒绝、prepareTurn 无幂等键走 null 指纹、idempotentResponse
 * 按 keyed claim 与 trace 会话选择性写响应头。
 */
class RagChatControllerHeartbeatClearTailTest {

    private static final UUID TURN_ID =
            UUID.fromString("66666666-6666-6666-6666-666666666666");

    private ChatTurnOperationService turnOperationService;
    private ChatSessionCoordinator coordinator;
    private RagChatController controller;
    private RagSseProperties sseProperties;

    @BeforeEach
    void setUp() {
        turnOperationService = mock(ChatTurnOperationService.class);
        coordinator = mock(ChatSessionCoordinator.class);
        sseProperties = new RagSseProperties();
        controller = new RagChatController(
                mock(RagChatService.class),
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                sseProperties,
                mock(CollectionRetrievalScopeResolver.class),
                mock(AuditLogService.class));
        controller.configureTurnOperationService(turnOperationService);
        controller.configureSessionCoordinator(coordinator);
        controller.configureModeAwareExecution(
                mock(ChatCommandMapper.class),
                mock(ChatExecutionService.class));
        controller.configureObjectMapper(new ObjectMapper());
    }

    private Object invoke(String name, Class<?>[] params, Object... args)
            throws Exception {
        Method method = RagChatController.class
                .getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method.invoke(controller, args);
    }

    @Test
    void startHeartbeatWithoutConfigurationReturnsInactiveHandles()
            throws Exception {
        // sseProperties 已注入但心跳未启用 → 空句柄。
        var handles = invoke("startHeartbeat",
                new Class<?>[]{org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class},
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter());

        assertNotNull(handles);
        // stop() 对空句柄必须是安全的（包级私有，反射调用）。
        var stop = handles.getClass().getDeclaredMethod("stop");
        stop.setAccessible(true);
        stop.invoke(handles);
    }

    @Test
    void startHeartbeatWithEnabledConfigStartsAndStopsScheduler()
            throws Exception {
        sseProperties.setHeartbeatIntervalSeconds(1);

        var handles = invoke("startHeartbeat",
                new Class<?>[]{org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class},
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter());
        assertNotNull(handles);

        var stop = handles.getClass().getDeclaredMethod("stop");
        stop.setAccessible(true);
        stop.invoke(handles);
    }

    @Test
    void clearHistoryDelegatesToCoordinatorAndReportsMessage() {
        when(coordinator.clearSession(any(), anyString())).thenReturn(3);

        ClearHistoryResponse response =
                controller.clearHistory("session-1", new MockHttpServletRequest()).getBody();

        assertNotNull(response);
        assertEquals("session-1", response.sessionId());
    }

    @Test
    void clearHistoryWithUnknownSessionSurfacesConflict() {
        when(coordinator.clearSession(any(), anyString())).thenReturn(0);

        assertThrows(RuntimeException.class,
                () -> controller.clearHistory(
                        "session-404", new MockHttpServletRequest()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void prepareTurnWithoutIdempotencyKeyUsesNullFingerprint()
            throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/chat");

        var prepared = (ChatTurnOperationService.Prepared) invoke(
                "prepareTurn",
                new Class<?>[]{com.springairag.api.dto.ChatRequest.class,
                        jakarta.servlet.http.HttpServletRequest.class},
                new com.springairag.api.dto.ChatRequest(), request);

        assertNull(prepared);
        verify(turnOperationService).prepare(
                any(), anyList(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void idempotentResponseWritesHeadersOnlyForKeyedClaimAndTrace()
            throws Exception {
        var response = new ChatResponse();
        response.setAnswer("answer");

        ChatTurnOperationService.Claim keyedClaim =
                new ChatTurnOperationService.Claim(
                        operation(), true);

        var keyed = (org.springframework.http.ResponseEntity<ChatResponse>)
                invoke("idempotentResponse",
                        new Class<?>[]{ChatResponse.class,
                                ChatTurnOperationService.Claim.class,
                                RetrievalTraceSession.class},
                        response, keyedClaim, null);
        assertEquals("66666666-6666-6666-6666-666666666666",
                keyed.getHeaders().getFirst("X-RAG-Turn-Id"));
        // SUCCEEDED 操作的 keyed claim 语义为可重放。
        assertEquals("true",
                keyed.getHeaders().getFirst("X-RAG-Idempotent-Replay"));

        var plain = (org.springframework.http.ResponseEntity<ChatResponse>)
                invoke("idempotentResponse",
                        new Class<?>[]{ChatResponse.class,
                                ChatTurnOperationService.Claim.class,
                                RetrievalTraceSession.class},
                        response, null, null);
        assertNull(plain.getHeaders().getFirst("X-RAG-Turn-Id"));
    }

    private ChatTurnOperation operation() {
        return new ChatTurnOperation(
                1L, "db:1", "key-hash", "fp-hash", 1,
                "session-1", TURN_ID,
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.SUCCEEDED,
                UUID.randomUUID(), java.time.Instant.now().plusSeconds(60),
                1, 1L, 1,
                null, null, null, null, "{}",
                java.time.Instant.now(), java.time.Instant.now(), null);
    }
}
