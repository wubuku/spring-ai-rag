package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ClearHistoryResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatSessionCoordinator;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.config.RagChatService;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.exception.RagException;
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
 * RagChatController 清史/幂等响应长尾（Batch 540，JaCoCo 驱动）：
 * clearHistory 经协调器与空删除拒绝、prepareTurn 无幂等键走 null 指纹、
 * idempotentResponse 按 keyed claim 与 trace 会话选择性写响应头。
 *
 * <h2>Batch 948：删掉两条 {@code startHeartbeat*} 用例</h2>
 *
 * 两条用例都用反射调私有 {@code startHeartbeat}，断言 {@code assertNotNull
 * (handles)} 再反射调一次 {@code stop()}。{@code startHeartbeat} 无条件返回
 * 一个 {@code HeartbeatHandles}，断言恒成立；而"启用时调度器真的建了、
 * 每秒真的把 {@code : heartbeat} 写到了线上"早在 Batch 945 就由
 * {@code RagChatControllerSseLifecycleTailTest#heartbeatTaskFiresWhenEnabled}
 * 走 HTTP 通道钉住了，关闭一侧则由 Batch 948 新增的
 * {@code #heartbeatDisabledEmitsNoCommentFrame} 钉住。留着它们只会让
 * "心跳两分支有覆盖"这句话看起来成立。
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

        RagException error = assertThrows(RagException.class,
                () -> controller.clearHistory(
                        "session-404", new MockHttpServletRequest()));
        assertEquals(ErrorCode.SESSION_NOT_FOUND, error.getErrorCodeEnum());
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
