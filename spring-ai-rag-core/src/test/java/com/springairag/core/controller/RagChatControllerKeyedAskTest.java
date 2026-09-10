package com.springairag.core.controller;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatPrincipal;
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
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * RagChatController /ask 与 /chat 别名的键控 JSON 语义：幂等重放、
 * prepared 持久执行链、失败标记重抛、无键控时 legacy 聊天回退。
 */
@ExtendWith(MockitoExtension.class)
class RagChatControllerKeyedAskTest {

    private static final UUID TURN_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");
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
                ChatTurnOperation.Transport.NATIVE_JSON,
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

    private ChatTurnOperationService.Prepared unkeyedPrepared() {
        return new ChatTurnOperationService.Prepared(
                PRINCIPAL, null, null, null, null, false);
    }

    private ChatCommand command() {
        return new ChatCommand(
                "hello", "session-1", PRINCIPAL, null,
                ChatMode.KNOWLEDGE, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private ChatResponse response(String answer) {
        return ChatResponse.builder()
                .answer(answer)
                .sessionId("session-1")
                .traceId("trace-1")
                .mode(ChatMode.KNOWLEDGE)
                .finishReason("STOP")
                .build();
    }

    private ChatRequest chatRequest() {
        ChatRequest request = new ChatRequest();
        request.setMessage("hello");
        request.setSessionId("session-1");
        return request;
    }

    private void stubPrepared(ChatTurnOperationService.Prepared prepared) {
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
    }

    @Test
    void askReplaysInspectedTurnWithIdempotencyHeaders() {
        ChatTurnOperationService.Prepared prepared = keyedPrepared();
        stubPrepared(prepared);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(prepared.operation(), true);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(claim);
        when(turnOperationService.replay(same(claim)))
                .thenReturn(response("cached"));

        ResponseEntity<ChatResponse> response =
                controller.ask(chatRequest(), new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("cached", response.getBody().getAnswer());
        assertEquals(TURN_ID.toString(),
                response.getHeaders().getFirst("X-RAG-Turn-Id"));
        assertEquals("true",
                response.getHeaders().getFirst("X-RAG-Idempotent-Replay"));
        verify(turnOperationService).replay(same(claim));
        verifyNoInteractions(chatExecutionService);
    }

    @Test
    void askRunsPreparedKeyedJsonChain() {
        ChatTurnOperationService.Prepared prepared = keyedPrepared();
        stubPrepared(prepared);
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
                eq(ChatTurnOperation.Transport.NATIVE_JSON), eq(false)))
                .thenReturn(fresh);
        ChatCommand claimedCommand = command();
        when(turnOperationService.commandForClaim(same(command), same(fresh)))
                .thenReturn(claimedCommand);
        ChatExecutionService.PreparedExecution preparedExecution =
                new ChatExecutionService.PreparedExecution(
                        claimedCommand, null, List.of(), null, null, null);
        when(chatExecutionService.prepareForOperation(
                same(claimedCommand), isNull(), eq(false)))
                .thenReturn(preparedExecution);
        when(turnOperationService.completePrepared(
                same(fresh), same(preparedExecution)))
                .thenReturn(response("prepared answer"));

        ResponseEntity<ChatResponse> response =
                controller.ask(chatRequest(), new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("prepared answer", response.getBody().getAnswer());
        assertEquals(TURN_ID.toString(),
                response.getHeaders().getFirst("X-RAG-Turn-Id"));
        assertEquals("false",
                response.getHeaders().getFirst("X-RAG-Idempotent-Replay"));
        verify(ragChatService).assertCircuitBreakerAllowsCall();
        verify(chatExecutionService).finalizePreparedOperation(
                same(preparedExecution));
        verify(turnOperationService, never()).fail(any(), any());
    }

    @Test
    void askMarksFailedWhenPreparedChainFails() {
        ChatTurnOperationService.Prepared prepared = keyedPrepared();
        stubPrepared(prepared);
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
                eq(ChatTurnOperation.Transport.NATIVE_JSON), eq(false)))
                .thenReturn(fresh);
        when(turnOperationService.commandForClaim(same(command), same(fresh)))
                .thenReturn(command);
        IllegalStateException error = new IllegalStateException("provider down");
        when(chatExecutionService.prepareForOperation(
                any(ChatCommand.class), isNull(), eq(false))).thenThrow(error);

        assertThrows(IllegalStateException.class,
                () -> controller.ask(chatRequest(), new MockHttpServletRequest()));

        verify(turnOperationService).fail(same(fresh), same(error));
    }

    @Test
    void askFallsBackToLegacyChatWhenPreparedUnkeyed() {
        ChatTurnOperationService.Prepared prepared = unkeyedPrepared();
        stubPrepared(prepared);
        ChatRequest request = chatRequest();
        ChatResponse legacy = response("legacy answer");
        when(ragChatService.chat(same(request))).thenReturn(legacy);

        ResponseEntity<ChatResponse> response =
                controller.ask(request, new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        assertSame(legacy, response.getBody());
        // legacy 请求无键控回合头。
        assertNull(response.getHeaders().getFirst("X-RAG-Turn-Id"));
        verify(turnOperationService, never()).claim(
                any(), any(), any(), any(Boolean.class));
        verifyNoInteractions(chatExecutionService);
    }

    @Test
    void askUsesScopedChatForPlainModeWithoutResolver() {
        stubPrepared(unkeyedPrepared());
        ChatRequest request = chatRequest();
        request.setMode(ChatMode.PLAIN);
        ChatResponse legacy = response("plain answer");
        when(ragChatService.chat(same(request), any(), isNull()))
                .thenReturn(legacy);

        ResponseEntity<ChatResponse> response =
                controller.ask(request, new MockHttpServletRequest());

        assertEquals("plain answer", response.getBody().getAnswer());
        verify(ragChatService).chat(same(request), any(), isNull());
    }

    @Test
    void chatAliasMirrorsAskKeyedReplay() {
        ChatTurnOperationService.Prepared prepared = keyedPrepared();
        stubPrepared(prepared);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(prepared.operation(), true);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(claim);
        when(turnOperationService.replay(same(claim)))
                .thenReturn(response("aliased replay"));

        ResponseEntity<ChatResponse> response =
                controller.chat(chatRequest(), new MockHttpServletRequest());

        assertEquals("aliased replay", response.getBody().getAnswer());
        assertEquals("true",
                response.getHeaders().getFirst("X-RAG-Idempotent-Replay"));
        verify(turnOperationService).replay(same(claim));
    }

    @Test
    void chatRunsPreparedKeyedJsonChainLikeAsk() {
        ChatTurnOperationService.Prepared prepared = keyedPrepared();
        stubPrepared(prepared);
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
                eq(ChatTurnOperation.Transport.NATIVE_JSON), eq(false)))
                .thenReturn(fresh);
        ChatCommand claimedCommand = command();
        when(turnOperationService.commandForClaim(same(command), same(fresh)))
                .thenReturn(claimedCommand);
        ChatExecutionService.PreparedExecution preparedExecution =
                new ChatExecutionService.PreparedExecution(
                        claimedCommand, null, List.of(), null, null, null);
        when(chatExecutionService.prepareForOperation(
                same(claimedCommand), isNull(), eq(false)))
                .thenReturn(preparedExecution);
        when(turnOperationService.completePrepared(
                same(fresh), same(preparedExecution)))
                .thenReturn(response("chat prepared answer"));

        ResponseEntity<ChatResponse> response =
                controller.chat(chatRequest(), new MockHttpServletRequest());

        assertEquals("chat prepared answer", response.getBody().getAnswer());
        assertEquals(TURN_ID.toString(),
                response.getHeaders().getFirst("X-RAG-Turn-Id"));
        verify(chatExecutionService).finalizePreparedOperation(
                same(preparedExecution));
    }

    @Test
    void chatFallsBackToLegacyChatWhenPreparedUnkeyed() {
        stubPrepared(unkeyedPrepared());
        ChatRequest request = chatRequest();
        ChatResponse legacy = response("chat legacy");
        when(ragChatService.chat(same(request))).thenReturn(legacy);

        ResponseEntity<ChatResponse> response =
                controller.chat(request, new MockHttpServletRequest());

        assertSame(legacy, response.getBody());
        assertNull(response.getHeaders().getFirst("X-RAG-Turn-Id"));
        verifyNoInteractions(chatExecutionService);
    }
}
