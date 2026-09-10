package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.openai.OpenAiChatRequestMapper;
import com.springairag.core.openai.OpenAiModelAliasRegistry;
import com.springairag.core.openai.OpenAiRequestRetrievalScopeAdapter;
import com.springairag.core.retrieval.RetrievalTraceHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OpenAI 兼容端点的键控回合与流式语义：幂等重放（JSON/SSE 快照流）、
 * 新鲜 claim 的 prepared 持久完成、失败标记与重抛、非键控 SSE 流
 * （角色块 + 增量 + [DONE]、错误降级块）、诊断会话附加。
 */
class OpenAiCompatibilityKeyedTurnTest {

    private static final UUID TURN_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final ChatPrincipal PRINCIPAL =
            new ChatPrincipal("db:1", "DATABASE_API_KEY", false);

    private OpenAiModelAliasRegistry aliasRegistry;
    private OpenAiChatRequestMapper requestMapper;
    private ChatExecutionService executionService;
    private ChatTurnOperationService turnOperationService;
    private OpenAiCompatibilityController controller;

    @BeforeEach
    void setUp() {
        aliasRegistry = mock(OpenAiModelAliasRegistry.class);
        requestMapper = mock(OpenAiChatRequestMapper.class);
        executionService = mock(ChatExecutionService.class);
        turnOperationService = mock(ChatTurnOperationService.class);
        controller = new OpenAiCompatibilityController(
                aliasRegistry,
                requestMapper,
                executionService,
                new ObjectMapper());
        controller.setTurnOperationService(turnOperationService);
    }

    private ChatCommand command() {
        return new ChatCommand(
                "hello", "session-1", PRINCIPAL, null,
                ChatMode.KNOWLEDGE, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private ChatTurnOperation operation(String executionSnapshot) {
        return new ChatTurnOperation(
                1L, "db:1", "key-hash", "fp-hash", 1,
                "session-1", TURN_ID,
                ChatTurnOperation.Transport.OPENAI_JSON,
                ChatTurnOperation.Status.SUCCEEDED,
                UUID.randomUUID(), Instant.now().plusSeconds(60),
                1, 1L, 1,
                executionSnapshot, null, null, null, null,
                Instant.now(), Instant.now(), null);
    }

    private ChatTurnOperationService.Prepared keyedPrepared(
            ChatTurnOperation operation) {
        return new ChatTurnOperationService.Prepared(
                PRINCIPAL, "key-hash", "fp-hash", null, operation, true);
    }

    private ChatTurnOperationService.Prepared unkeyedPrepared() {
        return new ChatTurnOperationService.Prepared(
                PRINCIPAL, null, null, null, null, false);
    }

    private ChatResponse response(String answer) {
        return ChatResponse.builder()
                .answer(answer)
                .sessionId("session-1")
                .traceId("trace-1")
                .mode(ChatMode.KNOWLEDGE)
                .sources(List.of())
                .usage(Map.of("totalTokens", 7))
                .finishReason("STOP")
                .metadata(Map.of())
                .stepMetrics(List.of())
                .build();
    }

    private OpenAiChatCompletionRequest completionRequest(boolean stream) {
        OpenAiChatCompletionRequest request = new OpenAiChatCompletionRequest();
        request.setModel("fallback-m");
        request.setStream(stream);
        return request;
    }

    @Test
    void keyedReplayStreamEmitsSnapshotChunksWithReplayHeaders() {
        ChatTurnOperation operation = operation(
                "{\"publicModelAlias\":\"public-a\","
                        + "\"declaredModelIdentifier\":\"declared-b\"}");
        ChatTurnOperationService.Prepared prepared = keyedPrepared(operation);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(operation, true);
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(claim);
        when(turnOperationService.replay(same(claim)))
                .thenReturn(response("cached answer"));
        OpenAiChatCompletionRequest request = completionRequest(true);

        ResponseEntity<ResponseBodyEmitter> response =
                controller.chatCompletions(request, null);

        assertEquals(MediaType.TEXT_EVENT_STREAM,
                response.getHeaders().getContentType());
        assertEquals(TURN_ID.toString(),
                response.getHeaders().getFirst("X-RAG-Turn-Id"));
        assertEquals("true",
                response.getHeaders().getFirst("X-RAG-Idempotent-Replay"));
        verify(turnOperationService).replay(same(claim));
        // 重放短路：不映射请求、不触达执行服务。
        verify(requestMapper, never()).map(any(), any());
        verifyNoInteractions(executionService);
    }

    @Test
    void keyedReplayJsonUsesDeclaredModelWhenNoPublicAlias() {
        ChatTurnOperation operation = operation(
                "{\"declaredModelIdentifier\":\"declared-b\"}");
        ChatTurnOperationService.Prepared prepared = keyedPrepared(operation);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(operation, true);
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(claim);
        when(turnOperationService.replay(same(claim)))
                .thenReturn(response("cached answer"));
        OpenAiChatCompletionRequest request = completionRequest(false);

        ResponseEntity<ResponseBodyEmitter> response =
                controller.chatCompletions(request, null);

        assertEquals(MediaType.APPLICATION_JSON,
                response.getHeaders().getContentType());
        assertEquals("true",
                response.getHeaders().getFirst("X-RAG-Idempotent-Replay"));
        verify(turnOperationService).replay(same(claim));
        verifyNoInteractions(executionService);
    }

    @Test
    void keyedFreshClaimRunsPreparedJsonCompletion() {
        ChatTurnOperation operation = operation(
                "{\"publicModelAlias\":\"public-a\"}");
        ChatTurnOperationService.Prepared prepared = keyedPrepared(operation);
        ChatTurnOperationService.Claim inspected =
                new ChatTurnOperationService.Claim(operation, false);
        ChatTurnOperationService.Claim fresh =
                new ChatTurnOperationService.Claim(operation, false);
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(inspected);
        OpenAiChatCompletionRequest request = completionRequest(false);
        ChatCommand command = command();
        when(requestMapper.mapFromExecutionSnapshot(
                same(request), isNull(), eq("session-1"),
                same(operation.executionSnapshot())))
                .thenReturn(new OpenAiChatRequestMapper.MappedRequest(
                        "public-a", false, command));
        when(turnOperationService.claim(same(prepared), same(command),
                eq(ChatTurnOperation.Transport.OPENAI_JSON), eq(false)))
                .thenReturn(fresh);
        ChatCommand claimedCommand = command();
        when(turnOperationService.commandForClaim(same(command), same(fresh)))
                .thenReturn(claimedCommand);
        ChatExecutionService.PreparedExecution preparedExecution =
                new ChatExecutionService.PreparedExecution(
                        claimedCommand, null, List.of(), null, null, null);
        when(executionService.prepareForOperation(
                same(claimedCommand), isNull(), eq(false)))
                .thenReturn(preparedExecution);
        when(turnOperationService.completePrepared(
                same(fresh), same(preparedExecution)))
                .thenReturn(response("final answer"));

        ResponseEntity<ResponseBodyEmitter> response =
                controller.chatCompletions(request, null);

        assertEquals(MediaType.APPLICATION_JSON,
                response.getHeaders().getContentType());
        assertEquals(TURN_ID.toString(),
                response.getHeaders().getFirst("X-RAG-Turn-Id"));
        assertEquals("false",
                response.getHeaders().getFirst("X-RAG-Idempotent-Replay"));
        verify(executionService).finalizePreparedOperation(
                same(preparedExecution));
        verify(executionService, never()).execute(any());
        verify(turnOperationService, never()).fail(any(), any());
        verify(turnOperationService, never()).release(any());
    }

    @Test
    void keyedPreparedJsonFailureMarksFailedAndRethrows() {
        ChatTurnOperation operation = operation(
                "{\"publicModelAlias\":\"public-a\"}");
        ChatTurnOperationService.Prepared prepared = keyedPrepared(operation);
        ChatTurnOperationService.Claim inspected =
                new ChatTurnOperationService.Claim(operation, false);
        ChatTurnOperationService.Claim fresh =
                new ChatTurnOperationService.Claim(operation, false);
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(inspected);
        OpenAiChatCompletionRequest request = completionRequest(false);
        ChatCommand command = command();
        when(requestMapper.mapFromExecutionSnapshot(
                any(), isNull(), eq("session-1"), any(String.class)))
                .thenReturn(new OpenAiChatRequestMapper.MappedRequest(
                        "public-a", false, command));
        when(turnOperationService.claim(same(prepared), same(command),
                eq(ChatTurnOperation.Transport.OPENAI_JSON), eq(false)))
                .thenReturn(fresh);
        when(turnOperationService.commandForClaim(same(command), same(fresh)))
                .thenReturn(command);
        ChatExecutionService.PreparedExecution preparedExecution =
                new ChatExecutionService.PreparedExecution(
                        command, null, List.of(), null, null, null);
        when(executionService.prepareForOperation(
                same(command), isNull(), eq(false)))
                .thenReturn(preparedExecution);
        IllegalStateException error = new IllegalStateException("provider down");
        when(turnOperationService.completePrepared(
                same(fresh), same(preparedExecution))).thenThrow(error);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> controller.chatCompletions(request, null));

        assertSame(error, thrown);
        verify(turnOperationService).fail(same(fresh), same(error));
        // 本测试 Claim 无会话租约：失败路径不得释放。
        verify(turnOperationService, never()).release(any());
    }

    @Test
    void keyedSnapshotStreamFreshClaimCompletesWithoutLiveExecution() {
        ChatTurnOperation operation = operation(
                "{\"publicModelAlias\":\"public-a\"}");
        ChatTurnOperationService.Prepared prepared = keyedPrepared(operation);
        ChatTurnOperationService.Claim inspected =
                new ChatTurnOperationService.Claim(operation, false);
        ChatTurnOperationService.Claim fresh =
                new ChatTurnOperationService.Claim(operation, false);
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(inspected);
        OpenAiChatCompletionRequest request = completionRequest(true);
        ChatCommand command = command();
        when(requestMapper.mapFromExecutionSnapshot(
                same(request), isNull(), eq("session-1"),
                same(operation.executionSnapshot())))
                .thenReturn(new OpenAiChatRequestMapper.MappedRequest(
                        "public-a", true, command));
        when(turnOperationService.claim(same(prepared), same(command),
                eq(ChatTurnOperation.Transport.OPENAI_SSE), eq(true)))
                .thenReturn(fresh);
        ChatCommand claimedCommand = command();
        when(turnOperationService.commandForClaim(same(command), same(fresh)))
                .thenReturn(claimedCommand);
        ChatExecutionService.PreparedExecution preparedExecution =
                new ChatExecutionService.PreparedExecution(
                        claimedCommand, null, List.of(), null, null, null);
        when(executionService.prepareForOperation(
                same(claimedCommand), isNull(), eq(true)))
                .thenReturn(preparedExecution);
        when(turnOperationService.completePrepared(
                same(fresh), same(preparedExecution)))
                .thenReturn(response("stream answer"));

        ResponseEntity<ResponseBodyEmitter> response =
                controller.chatCompletions(request, null);

        assertEquals(MediaType.TEXT_EVENT_STREAM,
                response.getHeaders().getContentType());
        assertEquals("false",
                response.getHeaders().getFirst("X-RAG-Idempotent-Replay"));
        verify(executionService).finalizePreparedOperation(
                same(preparedExecution));
        // 快照流消费 prepared 结果，不走实时流执行。
        verify(executionService, never()).stream(any());
        verify(turnOperationService, never()).fail(any(), any());
    }

    @Test
    void nonKeyedStreamEmitsDeltasWithoutTouchingTurnExecution() {
        ChatTurnOperationService.Prepared prepared = unkeyedPrepared();
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
        ChatCommand command = command();
        when(requestMapper.map(any(), any()))
                .thenReturn(new OpenAiChatRequestMapper.MappedRequest(
                        "m1", true, command));
        when(executionService.stream(same(command))).thenReturn(Flux.just(
                new ChatEvent.ContentDelta("hel"),
                new ChatEvent.ContentDelta("lo"),
                new ChatEvent.Completed("trace-1", "session-1",
                        null, null, ChatMode.KNOWLEDGE, Map.of(), "STOP",
                        List.of(), Map.of())));
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader(
                OpenAiRequestRetrievalScopeAdapter.COLLECTION_KEY_HEADER, "kb");
        OpenAiChatCompletionRequest request = completionRequest(true);

        ResponseEntity<ResponseBodyEmitter> response =
                controller.chatCompletions(request, httpRequest);

        assertEquals(MediaType.TEXT_EVENT_STREAM,
                response.getHeaders().getContentType());
        verify(executionService).stream(same(command));
        verify(executionService, never()).execute(any());
        ArgumentCaptor<List<String>> headerCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(requestMapper).validateDeclaration(same(request),
                headerCaptor.capture());
        assertEquals(List.of("kb"), headerCaptor.getValue());
    }

    @Test
    void nonKeyedStreamErrorFallsBackToErrorChunkAndDone() {
        ChatTurnOperationService.Prepared prepared = unkeyedPrepared();
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
        ChatCommand command = command();
        when(requestMapper.map(any(), any()))
                .thenReturn(new OpenAiChatRequestMapper.MappedRequest(
                        "m1", true, command));
        when(executionService.stream(same(command)))
                .thenReturn(Flux.error(new RuntimeException("boom")));
        OpenAiChatCompletionRequest request = completionRequest(true);

        // onErrorResume 消费错误：以错误块 + [DONE] 正常收尾，不向外抛。
        ResponseEntity<ResponseBodyEmitter> response = assertDoesNotThrow(
                () -> controller.chatCompletions(request, null));

        assertEquals(MediaType.TEXT_EVENT_STREAM,
                response.getHeaders().getContentType());
    }

    @Test
    void diagnosticsEnabledAttachesTraceSessionAndTraceHeader() {
        RetrievalDiagnosticsService diagnostics =
                mock(RetrievalDiagnosticsService.class);
        when(diagnostics.isEnabled()).thenReturn(true);
        RetrievalTraceSession session = new RetrievalTraceSession(
                PRINCIPAL, RetrievalTraceHeaders.OPERATION_OPENAI_CHAT,
                "session-1");
        when(diagnostics.createSession(any(ChatPrincipal.class),
                eq(RetrievalTraceHeaders.OPERATION_OPENAI_CHAT),
                eq("session-1"))).thenReturn(session);
        controller.setDiagnosticsService(diagnostics);
        ChatTurnOperationService.Prepared prepared = unkeyedPrepared();
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
        ChatCommand command = command();
        when(requestMapper.map(any(), any()))
                .thenReturn(new OpenAiChatRequestMapper.MappedRequest(
                        "m1", false, command));
        ChatExecutionResult result = new ChatExecutionResult(
                "answer", "session-1", "trace-1", null, null,
                ChatMode.KNOWLEDGE, List.of(), Map.of(), "STOP",
                List.of(), Map.of());
        when(executionService.execute(any(ChatCommand.class))).thenReturn(result);
        OpenAiChatCompletionRequest request = completionRequest(false);

        ResponseEntity<ResponseBodyEmitter> response =
                controller.chatCompletions(request, null);

        assertEquals(MediaType.APPLICATION_JSON,
                response.getHeaders().getContentType());
        assertEquals(session.traceId().toString(),
                response.getHeaders().getFirst(RetrievalTraceHeaders.TRACE_ID));
        ArgumentCaptor<ChatCommand> commandCaptor =
                ArgumentCaptor.forClass(ChatCommand.class);
        verify(executionService).execute(commandCaptor.capture());
        assertSame(session, commandCaptor.getValue().retrievalTraceSession());
    }
}
