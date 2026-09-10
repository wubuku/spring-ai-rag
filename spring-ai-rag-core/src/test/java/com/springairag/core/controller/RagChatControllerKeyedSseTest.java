package com.springairag.core.controller;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatSessionCoordinator;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RagChatController /stream 键控回合 SSE 语义：幂等重放快照流、
 * claim 竞速重放、prepared 持久执行链（prepare→complete→finalize→
 * nativeSnapshotEmitter）、失败标记重抛、会话租约释放。
 */
@ExtendWith(MockitoExtension.class)
class RagChatControllerKeyedSseTest {

    private static final UUID TURN_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final ChatPrincipal PRINCIPAL =
            new ChatPrincipal("db:1", "DATABASE_API_KEY", false);

    @Mock RagChatService ragChatService;
    @Mock RagChatHistoryRepository historyRepository;
    @Mock ChatExportService chatExportService;
    @Mock AuditLogService auditLogService;
    @Mock ChatTurnOperationService turnOperationService;
    @Mock ChatCommandMapper commandMapper;
    @Mock ChatExecutionService chatExecutionService;

    private RagChatController controller;

    @BeforeEach
    void setUp() {
        controller = new RagChatController(
                ragChatService, historyRepository, chatExportService,
                new RagSseProperties(), auditLogService);
        controller.configureTurnOperationService(turnOperationService);
        controller.configureModeAwareExecution(commandMapper, chatExecutionService);
    }

    private ChatTurnOperation operation() {
        return new ChatTurnOperation(
                1L, "db:1", "key-hash", "fp-hash", 1,
                "session-1", TURN_ID,
                ChatTurnOperation.Transport.NATIVE_SSE,
                ChatTurnOperation.Status.SUCCEEDED,
                UUID.randomUUID(), Instant.now().plusSeconds(60),
                1, 1L, 1,
                "{\"sessionId\":\"session-1\"}", null, null, null, null,
                Instant.now(), Instant.now(), null);
    }

    private ChatTurnOperationService.Prepared keyedPrepared() {
        return new ChatTurnOperationService.Prepared(
                PRINCIPAL, "key-hash", "fp-hash", null, operation(), true);
    }

    private ChatCommand command() {
        return new ChatCommand(
                "hello", "session-1", PRINCIPAL, null,
                ChatMode.KNOWLEDGE, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private ChatSource source() {
        ChatSource source = new ChatSource();
        source.setCitationId("c-1");
        source.setTitle("doc-1");
        return source;
    }

    private ChatResponse response(String answer) {
        return ChatResponse.builder()
                .answer(answer)
                .sessionId("session-1")
                .traceId("trace-1")
                .mode(ChatMode.KNOWLEDGE)
                .sources(List.of(source()))
                .usage(Map.of("totalTokens", 9))
                .finishReason("STOP")
                .turnId("turn-request")
                .metadata(Map.of())
                .stepMetrics(List.of())
                .build();
    }

    private ChatRequest chatRequest() {
        ChatRequest request = new ChatRequest();
        request.setMessage("hello");
        request.setSessionId("session-1");
        return request;
    }

    @Test
    void streamReplaysInspectedTurnAsNativeSse() {
        ChatTurnOperationService.Prepared prepared = keyedPrepared();
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(prepared.operation(), true);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(claim);
        when(turnOperationService.replay(same(claim)))
                .thenReturn(response("cached"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        SseEmitter emitter = controller.stream(
                chatRequest(), new MockHttpServletRequest(), servletResponse);

        assertNotNull(emitter);
        assertEquals(TURN_ID.toString(),
                servletResponse.getHeader("X-RAG-Turn-Id"));
        assertEquals("true",
                servletResponse.getHeader("X-RAG-Idempotent-Replay"));
        verify(turnOperationService).replay(same(claim));
        // 重放短路：不抢占新 claim、不触达执行链。
        verify(turnOperationService, never()).claim(
                any(), any(), any(), any(Boolean.class));
        verifyNoInteractions(chatExecutionService);
    }

    @Test
    void streamReplaysClaimRaceAsNativeSse() {
        ChatTurnOperationService.Prepared prepared = keyedPrepared();
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(null);
        ChatCommand command = command();
        when(commandMapper.mapFromExecutionSnapshot(
                any(ChatRequest.class), any(ChatPrincipal.class),
                eq("session-1"), anyString()))
                .thenReturn(command);
        ChatTurnOperationService.Claim claimed =
                new ChatTurnOperationService.Claim(prepared.operation(), true);
        when(turnOperationService.claim(same(prepared), same(command),
                eq(ChatTurnOperation.Transport.NATIVE_SSE), eq(true)))
                .thenReturn(claimed);
        when(turnOperationService.replay(same(claimed)))
                .thenReturn(response("raced"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        SseEmitter emitter = controller.stream(
                chatRequest(), new MockHttpServletRequest(), servletResponse);

        assertNotNull(emitter);
        assertEquals("true",
                servletResponse.getHeader("X-RAG-Idempotent-Replay"));
        verify(turnOperationService).replay(same(claimed));
        verify(turnOperationService, never()).commandForClaim(any(), any());
        verifyNoInteractions(chatExecutionService);
    }

    @Test
    void streamRunsPreparedChainToSnapshotEmitter() {
        ChatTurnOperationService.Prepared prepared = keyedPrepared();
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(null);
        ChatCommand command = command();
        when(commandMapper.mapFromExecutionSnapshot(
                any(ChatRequest.class), any(ChatPrincipal.class),
                eq("session-1"), anyString()))
                .thenReturn(command);
        ChatTurnOperationService.Claim fresh =
                new ChatTurnOperationService.Claim(prepared.operation(), false);
        when(turnOperationService.claim(same(prepared), same(command),
                eq(ChatTurnOperation.Transport.NATIVE_SSE), eq(true)))
                .thenReturn(fresh);
        ChatCommand claimedCommand = command();
        when(turnOperationService.commandForClaim(same(command), same(fresh)))
                .thenReturn(claimedCommand);
        ChatExecutionService.PreparedExecution preparedExecution =
                new ChatExecutionService.PreparedExecution(
                        claimedCommand, null, List.of(), null, null, null);
        when(chatExecutionService.prepareForOperation(
                same(claimedCommand), isNull(), eq(true)))
                .thenReturn(preparedExecution);
        when(turnOperationService.completePrepared(
                same(fresh), same(preparedExecution)))
                .thenReturn(response("snapshot answer"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        SseEmitter emitter = controller.stream(
                chatRequest(), new MockHttpServletRequest(), servletResponse);

        assertNotNull(emitter);
        assertEquals(TURN_ID.toString(),
                servletResponse.getHeader("X-RAG-Turn-Id"));
        assertEquals("false",
                servletResponse.getHeader("X-RAG-Idempotent-Replay"));
        verify(ragChatService).assertCircuitBreakerAllowsCall();
        verify(chatExecutionService).finalizePreparedOperation(
                same(preparedExecution));
        verify(turnOperationService, never()).fail(any(), any());
    }

    @Test
    void streamFailsOperationWhenPreparedChainFails() {
        ChatTurnOperationService.Prepared prepared = keyedPrepared();
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(null);
        ChatCommand command = command();
        when(commandMapper.mapFromExecutionSnapshot(
                any(ChatRequest.class), any(ChatPrincipal.class),
                eq("session-1"), anyString()))
                .thenReturn(command);
        ChatTurnOperationService.Claim fresh =
                new ChatTurnOperationService.Claim(prepared.operation(), false);
        when(turnOperationService.claim(same(prepared), same(command),
                eq(ChatTurnOperation.Transport.NATIVE_SSE), eq(true)))
                .thenReturn(fresh);
        when(turnOperationService.commandForClaim(same(command), same(fresh)))
                .thenReturn(command);
        IllegalStateException error = new IllegalStateException("provider down");
        when(chatExecutionService.prepareForOperation(
                any(ChatCommand.class), isNull(), eq(true))).thenThrow(error);

        assertThrows(IllegalStateException.class,
                () -> controller.stream(chatRequest(),
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse()));

        verify(turnOperationService).fail(same(fresh), same(error));
    }

    @Test
    void streamReleasesSessionLeaseAfterKeyedChain() {
        ChatSessionCoordinator sessionCoordinator =
                mock(ChatSessionCoordinator.class);
        controller.configureSessionCoordinator(sessionCoordinator);
        ChatTurnOperationService.Prepared prepared = keyedPrepared();
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(null);
        ChatCommand command = command();
        when(commandMapper.mapFromExecutionSnapshot(
                any(ChatRequest.class), any(ChatPrincipal.class),
                eq("session-1"), anyString()))
                .thenReturn(command);
        ChatTurnOperationService.Claim fresh =
                new ChatTurnOperationService.Claim(prepared.operation(), false);
        when(turnOperationService.claim(same(prepared), same(command),
                eq(ChatTurnOperation.Transport.NATIVE_SSE), eq(true)))
                .thenReturn(fresh);
        when(turnOperationService.commandForClaim(same(command), same(fresh)))
                .thenReturn(command);
        ChatExecutionService.PreparedExecution preparedExecution =
                new ChatExecutionService.PreparedExecution(
                        command, null, List.of(), null, null, null);
        when(chatExecutionService.prepareForOperation(
                same(command), isNull(), eq(true)))
                .thenReturn(preparedExecution);
        when(turnOperationService.completePrepared(
                same(fresh), same(preparedExecution)))
                .thenReturn(response("snapshot answer"));

        controller.stream(chatRequest(),
                new MockHttpServletRequest(), new MockHttpServletResponse());

        // 键控链路结束（成功路径 finally）释放会话租约。
        verify(sessionCoordinator).release(isNull());
    }
}
