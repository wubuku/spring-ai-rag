package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.openai.OpenAiChatRequestMapper;
import com.springairag.core.openai.OpenAiModelAliasRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * claim 之后才发现的重放分支（Batch 331）：inspect 通过但
 * turnOperationService.claim 返回 replay=true 的并发场景——
 * JSON 与 SSE 两种传输均从操作快照应答，不再触达执行服务。
 */
class OpenAiCompatibilityClaimReplayTest {

    private static final UUID TURN_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
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

    private OpenAiChatCompletionRequest completionRequest(boolean stream) {
        OpenAiChatCompletionRequest request = new OpenAiChatCompletionRequest();
        request.setModel("fallback-m");
        request.setStream(stream);
        return request;
    }

    @Test
    void replayAfterClaimRespondsJsonFromOperationSnapshot() {
        ChatTurnOperation operation = operation(
                "{\"publicModelAlias\":\"public-a\","
                        + "\"declaredModelIdentifier\":\"declared-b\"}");
        ChatTurnOperationService.Prepared prepared = keyedPrepared(operation);
        ChatTurnOperationService.Claim inspected =
                new ChatTurnOperationService.Claim(operation, false);
        ChatTurnOperationService.Claim replayed =
                new ChatTurnOperationService.Claim(operation, true);
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
                .thenReturn(replayed);
        when(turnOperationService.replay(same(replayed)))
                .thenReturn(response("cached answer"));

        ResponseEntity<ResponseBodyEmitter> response =
                controller.chatCompletions(request, null);

        assertEquals(MediaType.APPLICATION_JSON,
                response.getHeaders().getContentType());
        assertEquals(TURN_ID.toString(),
                response.getHeaders().getFirst("X-RAG-Turn-Id"));
        assertEquals("true",
                response.getHeaders().getFirst("X-RAG-Idempotent-Replay"));
        verify(turnOperationService).replay(same(replayed));
        // 重放短路：不触达执行服务。
        verifyNoInteractions(executionService);
    }

    @Test
    void replayAfterClaimRespondsSnapshotStreamForStreamingRequest() {
        ChatTurnOperation operation = operation(
                "{\"publicModelAlias\":\"public-a\","
                        + "\"declaredModelIdentifier\":\"declared-b\"}");
        ChatTurnOperationService.Prepared prepared = keyedPrepared(operation);
        ChatTurnOperationService.Claim inspected =
                new ChatTurnOperationService.Claim(operation, false);
        ChatTurnOperationService.Claim replayed =
                new ChatTurnOperationService.Claim(operation, true);
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                isNull())).thenReturn(prepared);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(inspected);
        OpenAiChatCompletionRequest request = completionRequest(true);
        ChatCommand command = command();
        when(requestMapper.mapFromExecutionSnapshot(
                any(), isNull(), eq("session-1"), any(String.class)))
                .thenReturn(new OpenAiChatRequestMapper.MappedRequest(
                        "public-a", true, command));
        when(turnOperationService.claim(same(prepared), same(command),
                eq(ChatTurnOperation.Transport.OPENAI_SSE), eq(true)))
                .thenReturn(replayed);
        when(turnOperationService.replay(same(replayed)))
                .thenReturn(response("cached stream answer"));

        ResponseEntity<ResponseBodyEmitter> response =
                controller.chatCompletions(request, null);

        assertEquals(MediaType.TEXT_EVENT_STREAM,
                response.getHeaders().getContentType());
        assertEquals(TURN_ID.toString(),
                response.getHeaders().getFirst("X-RAG-Turn-Id"));
        assertEquals("true",
                response.getHeaders().getFirst("X-RAG-Idempotent-Replay"));
        verify(turnOperationService).replay(same(replayed));
        verifyNoInteractions(executionService);
    }

    private ChatResponse response(String answer) {
        return ChatResponse.builder().answer(answer).build();
    }
}
