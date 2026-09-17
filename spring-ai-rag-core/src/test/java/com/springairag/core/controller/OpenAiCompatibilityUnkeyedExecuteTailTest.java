package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatSessionCoordinator;
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

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenAI 兼容层非键控执行路径长尾（Batch 503，JaCoCo 驱动）：
 * 非键控 JSON 成功链（execute → toNativeResponse → toResponse 结
 * 果重载）及其执行失败重抛；键控路径下快照模型标识为 DEFAULT 时
 * 的别名回退与损坏快照 JSON 的容错回退。
 */
class OpenAiCompatibilityUnkeyedExecuteTailTest {

    private static final ChatPrincipal PRINCIPAL =
            new ChatPrincipal("local:auth-disabled", "LOCAL", false);

    private OpenAiModelAliasRegistry aliasRegistry;
    private OpenAiChatRequestMapper requestMapper;
    private ChatExecutionService executionService;
    private ChatTurnOperationService turnOperationService;
    private OpenAiCompatibilityController controller;
    private ChatSessionCoordinator.LeaseHandle lease;

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
        lease = mock(ChatSessionCoordinator.LeaseHandle.class);
    }

    private ChatCommand command() {
        return new ChatCommand(
                "hello", "session-1", PRINCIPAL, null,
                ChatMode.KNOWLEDGE, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private ChatTurnOperation operationWithSnapshot(String snapshot) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, PRINCIPAL.id(), "key-hash", "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.OPENAI_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), now.plusSeconds(60),
                1, 1L, 1,
                snapshot, null, null, null, "{}",
                now, now, null);
    }

    private OpenAiChatCompletionRequest completionRequest(boolean stream) {
        OpenAiChatCompletionRequest request =
                new OpenAiChatCompletionRequest();
        request.setModel("fallback-m");
        request.setStream(stream);
        return request;
    }

    private ChatExecutionService.PreparedExecution preparedExecution() {
        return new ChatExecutionService.PreparedExecution(
                command(),
                new ChatExecutionResult(
                        "stable answer", "session-1", "trace-1",
                        null, null, ChatMode.KNOWLEDGE,
                        List.of(), Map.of(), "STOP",
                        List.of(), Map.of()),
                List.of(), null, null, null);
    }

    /** 键控 JSON 链路公共 stub：claim 带租约、command 原样透传。 */
    private void stubKeyedJson(ChatTurnOperation op) throws Exception {
        Constructor<ChatTurnOperationService.Claim> ctor =
                ChatTurnOperationService.Claim.class.getDeclaredConstructor(
                        ChatTurnOperation.class, boolean.class,
                        ChatSessionCoordinator.LeaseHandle.class);
        ctor.setAccessible(true);
        ChatTurnOperationService.Claim claim =
                ctor.newInstance(op, false, lease);

        when(turnOperationService.prepare(any(ChatPrincipal.class),
                anyList(), isNull()))
                .thenReturn(new ChatTurnOperationService.Prepared(
                        PRINCIPAL, "key-hash", "fp-hash", null, op, true));
        when(turnOperationService.inspectExisting(any())).thenReturn(null);
        when(requestMapper.mapFromExecutionSnapshot(
                any(), isNull(), eq("session-1"),
                anyString()))
                .thenReturn(new OpenAiChatRequestMapper.MappedRequest(
                        "public-a", false, command()));
        when(turnOperationService.claim(any(), any(), any(), anyBoolean()))
                .thenReturn(claim);
        when(turnOperationService.commandForClaim(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(executionService.prepareForOperation(
                any(ChatCommand.class), any(), eq(false)))
                .thenReturn(mock(ChatExecutionService.PreparedExecution.class));
        when(turnOperationService.completePrepared(
                any(ChatTurnOperationService.Claim.class),
                any(ChatExecutionService.PreparedExecution.class)))
                .thenReturn(ChatResponse.builder()
                        .answer("stable answer").build());
    }

    @Test
    void unkeyedJsonSuccessMapsResultToOpenAiResponse() {
        when(requestMapper.map(any(), any()))
                .thenReturn(new OpenAiChatRequestMapper.MappedRequest(
                        "public-a", false, command()));
        when(executionService.execute(any(ChatCommand.class)))
                .thenReturn(new ChatExecutionResult(
                        "plain answer", "session-1", "trace-1",
                        null, null, ChatMode.KNOWLEDGE,
                        List.of(), Map.of("totalTokens", 7), "STOP",
                        List.of(), Map.of()));

        ResponseEntity<ResponseBodyEmitter> response =
                controller.chatCompletions(
                        completionRequest(false), null);

        assertEquals(MediaType.APPLICATION_JSON,
                response.getHeaders().getContentType());
        verify(turnOperationService, never()).fail(any(), any());
    }

    @Test
    void unkeyedJsonExecutionFailureRethrowsWithoutFail() {
        when(requestMapper.map(any(), any()))
                .thenReturn(new OpenAiChatRequestMapper.MappedRequest(
                        "public-a", false, command()));
        when(executionService.execute(any(ChatCommand.class)))
                .thenThrow(new IllegalStateException("boom"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.chatCompletions(
                        completionRequest(false), null));
        assertEquals("boom", error.getMessage());
        verify(turnOperationService, never()).fail(any(), any());
    }

    @Test
    void keyedDefaultSnapshotFallsBackToMappedAlias() throws Exception {
        ChatTurnOperation op = operationWithSnapshot(
                "{\"publicModelAlias\":\"DEFAULT\","
                        + "\"declaredModelIdentifier\":\"DEFAULT\"}");
        stubKeyedJson(op);

        ResponseEntity<ResponseBodyEmitter> response =
                controller.chatCompletions(
                        completionRequest(false), null);

        assertEquals(MediaType.APPLICATION_JSON,
                response.getHeaders().getContentType());
        verify(turnOperationService, never()).fail(any(), any());
    }

    @Test
    void keyedCorruptSnapshotFallsBackGracefully() throws Exception {
        ChatTurnOperation op = operationWithSnapshot("{corrupt");
        stubKeyedJson(op);

        ResponseEntity<ResponseBodyEmitter> response =
                controller.chatCompletions(
                        completionRequest(false), null);

        assertEquals(MediaType.APPLICATION_JSON,
                response.getHeaders().getContentType());
    }
}
