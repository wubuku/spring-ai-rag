package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.api.openai.OpenAiErrorResponse;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatSessionCoordinator;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.exception.RagException;
import com.springairag.core.openai.OpenAiChatRequestMapper;
import com.springairag.core.openai.OpenAiModelAliasRegistry;
import com.springairag.core.openai.OpenAiProtocolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenAI 兼容层 keyed 非重放路径长尾（Batch 477，JaCoCo 驱动）：
 * keyed JSON 成功应答的 toResponse 映射、execute 失败 /
 * prepareForOperation 失败时 fail + 释放会话租约 + 原样重抛，
 * 以及 toStreamError 的三种错误映射（OpenAI 协议错误 / RagException
 * 4xx 与 5xx / 兜底 server_error）。
 */
class OpenAiCompatibilityKeyedFailureTailTest {

    private static final UUID TURN_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final ChatPrincipal PRINCIPAL =
            new ChatPrincipal("db:1", "DATABASE_API_KEY", false);

    private OpenAiModelAliasRegistry aliasRegistry;
    private OpenAiChatRequestMapper requestMapper;
    private ChatExecutionService executionService;
    private ChatTurnOperationService turnOperationService;
    private OpenAiCompatibilityController controller;
    private ChatSessionCoordinator.LeaseHandle lease;
    private ChatCommand command;

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
        command = command();
    }

    private ChatCommand command() {
        return new ChatCommand(
                "hello", "session-1", PRINCIPAL, null,
                ChatMode.KNOWLEDGE, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private ChatTurnOperation operation() {
        return new ChatTurnOperation(
                1L, "db:1", "key-hash", "fp-hash", 1,
                "session-1", TURN_ID,
                ChatTurnOperation.Transport.OPENAI_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), Instant.now().plusSeconds(60),
                1, 1L, 1,
                "{\"publicModelAlias\":\"public-a\","
                + "\"declaredModelIdentifier\":\"declared-b\"}",
                null, null, null, null,
                Instant.now(), Instant.now(), null);
    }

    /** 反射构造带租约的 keyed Claim（覆盖 finally 释放分支）。 */
    private ChatTurnOperationService.Claim keyedClaimWithLease()
            throws Exception {
        var ctor = ChatTurnOperationService.Claim.class
                .getDeclaredConstructor(
                        ChatTurnOperation.class, boolean.class,
                        ChatSessionCoordinator.LeaseHandle.class);
        ctor.setAccessible(true);
        return ctor.newInstance(operation(), false, lease);
    }

    private ChatTurnOperationService.Prepared keyedPrepared() {
        return new ChatTurnOperationService.Prepared(
                PRINCIPAL, "key-hash", "fp-hash", null, operation(), true);
    }

    private void stubClaimPath(boolean stream) throws Exception {
        ChatTurnOperationService.Prepared prepared = keyedPrepared();
        when(turnOperationService.prepare(any(ChatPrincipal.class),
                anyList(), isNull())).thenReturn(prepared);
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(null);
        ChatCommand command = command();
        when(requestMapper.mapFromExecutionSnapshot(
                any(), isNull(), eq("session-1"), any(String.class)))
                .thenReturn(new OpenAiChatRequestMapper.MappedRequest(
                        "public-a", stream, command));
        when(turnOperationService.claim(same(prepared), same(command),
                eq(stream
                        ? ChatTurnOperation.Transport.OPENAI_SSE
                        : ChatTurnOperation.Transport.OPENAI_JSON),
                eq(stream)))
                .thenReturn(keyedClaimWithLease());
        // keyed claim 会经 commandForClaim 绑定执行上下文；测试中
        // 原样透传请求 command。
        when(turnOperationService.commandForClaim(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** 供各用例复用：mapper stub 中持有的 command 实例。 */
    private ChatCommand stubbedCommand() {
        return command;
    }

    private OpenAiChatCompletionRequest completionRequest(boolean stream) {
        OpenAiChatCompletionRequest request =
                new OpenAiChatCompletionRequest();
        request.setModel("fallback-m");
        request.setStream(stream);
        return request;
    }

    @Test
    void keyedJsonSuccessMapsOpenAiResponse() throws Exception {
        stubClaimPath(false);
        // keyed JSON：prepareForOperation → completePrepared 落账应答。
        when(executionService.prepareForOperation(
                any(ChatCommand.class), any(), eq(false)))
                .thenReturn(mock(ChatExecutionService.PreparedExecution.class));
        when(turnOperationService.completePrepared(
                any(ChatTurnOperationService.Claim.class),
                any(ChatExecutionService.PreparedExecution.class)))
                .thenReturn(ChatResponse.builder()
                        .answer("final answer").build());

        ResponseEntity<ResponseBodyEmitter> response =
                controller.chatCompletions(
                        completionRequest(false), null);

        assertEquals(MediaType.APPLICATION_JSON,
                response.getHeaders().getContentType());
        assertEquals(TURN_ID.toString(),
                response.getHeaders().getFirst("X-RAG-Turn-Id"));
        verify(executionService).finalizePreparedOperation(any());
        verify(turnOperationService, never()).fail(any(), any());
    }

    @Test
    void keyedJsonPrepareFailureFailsOperationAndRethrows()
            throws Exception {
        stubClaimPath(false);
        IllegalStateException failure =
                new IllegalStateException("provider down");
        when(executionService.prepareForOperation(
                any(ChatCommand.class), any(), eq(false))).thenThrow(failure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> controller.chatCompletions(
                        completionRequest(false), null));

        assertEquals("provider down", thrown.getMessage());
        verify(turnOperationService).fail(
                any(ChatTurnOperationService.Claim.class), same(failure));
    }

    @Test
    void keyedStreamPrepareFailureFailsOperationAndRethrows()
            throws Exception {
        stubClaimPath(true);
        IllegalStateException failure =
                new IllegalStateException("prepare failed");
        when(executionService.prepareForOperation(
                any(ChatCommand.class), any(), eq(true)))
                .thenThrow(failure);
        when(executionService.prepareForOperation(
                any(ChatCommand.class), any(), eq(true)))
                .thenThrow(failure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> controller.chatCompletions(
                        completionRequest(true), null));

        assertEquals("prepare failed", thrown.getMessage());
        verify(turnOperationService).fail(
                any(ChatTurnOperationService.Claim.class), same(failure));
    }

    // ── toStreamError 三类错误映射（反射直调私有方法）────────────

    private OpenAiErrorResponse toStreamError(Throwable error)
            throws Exception {
        Method method = OpenAiCompatibilityController.class
                .getDeclaredMethod("toStreamError", Throwable.class);
        method.setAccessible(true);
        return (OpenAiErrorResponse) method.invoke(controller, error);
    }

    @Test
    void openAiProtocolErrorKeepsProtocolFields() throws Exception {
        OpenAiErrorResponse response = toStreamError(
                new OpenAiProtocolException(
                        429, "rate limited", "rate_limit_error",
                        "model", "rate_limit_exceeded"));

        assertEquals("rate limited", response.error().message());
        assertEquals("rate_limit_error", response.error().type());
        assertEquals("model", response.error().param());
        assertEquals("rate_limit_exceeded", response.error().code());
    }

    @Test
    void ragExceptionMapsHttpStatusToErrorType() throws Exception {
        OpenAiErrorResponse clientError = toStreamError(
                new RagException(ErrorCode.FORBIDDEN, "denied"));
        assertEquals("invalid_request_error", clientError.error().type());
        assertEquals("denied", clientError.error().message());

        OpenAiErrorResponse serverError = toStreamError(
                new RagException(ErrorCode.INTERNAL_ERROR, "boom"));
        assertEquals("server_error", serverError.error().type());
    }

    @Test
    void genericErrorFallsBackToServerErrorMessage() throws Exception {
        OpenAiErrorResponse response = toStreamError(
                new IllegalStateException("unexpected"));

        assertEquals(
                "The RAG service could not complete the streaming request",
                response.error().message());
        assertEquals("server_error", response.error().type());
    }
}
