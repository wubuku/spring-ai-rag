package com.springairag.core.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;

import com.springairag.core.repository.ChatTurnOperationRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import com.springairag.core.exception.RagException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatTurnOperationService 序列化异常路径长尾（Batch 506，JaCoCo
 * 驱动）：ObjectMapper 写出失败时 completeOpenAi → CHAT_HISTORY_
 * PERSIST_FAILED、completePrepared → IDEMPOTENCY_RESPONSE_TOO_
 * LARGE、executionSnapshot → IDEMPOTENCY_EXECUTION_SNAPSHOT_
 * INVALID 三类包装；stableStepMetrics 的 null 元素拒绝与正常映射。
 */
class ChatTurnOperationSerializationTailTest {

    private ChatTurnOperationRepository repository;
    private ChatSessionCoordinator coordinator;
    private ChatAuthorizationService authorizationService;
    private ObjectMapper brokenMapper;
    private ChatExecutionService executionService;
    private RagChatProperties properties;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        coordinator = mock(ChatSessionCoordinator.class);
        authorizationService = mock(ChatAuthorizationService.class);
        when(authorizationService.initialSnapshot(any(ChatCommand.class)))
                .thenReturn("{}");
        brokenMapper = mock(ObjectMapper.class);
        executionService = mock(ChatExecutionService.class);
        when(executionService.resolveCandidateRefs(any(), anyBoolean()))
                .thenReturn(List.of("vendor/model-a"));
        properties = new RagChatProperties();
    }

    /** writeValueAsString 一律失败的 ObjectMapper（mock 抛受检异常）。 */
    private ObjectMapper brokenWriteMapper() {
        ObjectMapper broken = mock(ObjectMapper.class);
        try {
            Mockito.when(broken.writeValueAsString(any()))
                    .thenThrow(new JsonProcessingException("jackson down") {});
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
        return broken;
    }

    private ChatTurnOperationService service(ObjectMapper mapper) {
        var created = new ChatTurnOperationService(
                repository,
                mapper,
                properties,
                new RagProperties(),
                authorizationService,
                mock(ChatObservabilityService.class),
                executionService);
        created.setSessionCoordinator(coordinator);
        return created;
    }

    private ChatCommand command() {
        return new ChatCommand(
                "hello", "session-1", principal(), null,
                ChatMode.PLAIN, MemoryMode.SERVER, null, null,
                null, null, Map.of());
    }

    private ChatPrincipal principal() {
        return ChatPrincipal.local();
    }

    private ChatTurnOperation operation() {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, "principal-1", "key-hash", "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), now.plusSeconds(60),
                1, 0L, 1,
                null, null, null, null, "{}",
                now, now, null);
    }

    private ChatExecutionService.PreparedExecution preparedExecution() {
        return new ChatExecutionService.PreparedExecution(
                command(),
                new ChatExecutionResult(
                        "answer", "session-1", "trace-1", null, null,
                        ChatMode.PLAIN, List.of(), Map.of(), "STOP",
                        List.of(), Map.of()),
                List.of(), null, null, null);
    }

    private ChatResponse response(String answer) {
        return ChatResponse.builder().answer(answer).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void claimInsertSerializationFailureIsWrappedAsSnapshotInvalid() {
        when(repository.find("principal-1", "key-hash"))
                .thenReturn(null)
                .thenReturn(operation());
        when(coordinator.acquire(any(ChatCommand.class), anyBoolean()))
                .thenReturn(mock(ChatSessionCoordinator.LeaseHandle.class));

        RagException error = assertThrows(RagException.class,
                () -> service(brokenWriteMapper()).claim(
                        new ChatTurnOperationService.Prepared(
                                principal(), "key-hash", "fp-hash",
                                null, null, true),
                        command(), ChatTurnOperation.Transport.NATIVE_JSON,
                        false));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void completeOpenAiWriteFailureBecomesPersistFailed() {
        ChatTurnOperationService broken = service(brokenWriteMapper());

        RagException error = assertThrows(RagException.class,
                () -> broken.completeOpenAi(
                        new ChatTurnOperationService.Claim(operation(), false),
                        response("final"), "PLAIN", "SERVER", "model-x",
                        null));
        assertEquals(ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                error.getErrorCodeEnum());
    }

    @Test
    void completePreparedSerializationFailureIsResponseTooLarge() {
        ChatTurnOperationService broken = service(brokenWriteMapper());
        ChatTurnOperation op = operation();
        ChatTurnOperation opWithSnapshot = new ChatTurnOperation(
                op.id(), op.ownerPrincipalId(), op.idempotencyKeySha256(),
                op.requestFingerprintSha256(), op.fingerprintVersion(),
                op.sessionId(), op.turnId(), op.transport(), op.status(),
                op.operationToken(), op.leaseExpiresAt(), op.attemptCount(),
                op.rowVersion(), op.responseVersion(), "{}",
                op.responsePayload(), op.errorCode(), op.errorPayload(),
                op.authorizationScopeSnapshot(), op.createdAt(),
                op.updatedAt(), op.completedAt());
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(opWithSnapshot, false);

        RagException error = assertThrows(RagException.class,
                () -> broken.completePrepared(
                        claim, preparedExecution()));
        assertEquals(ErrorCode.IDEMPOTENCY_RESPONSE_TOO_LARGE,
                error.getErrorCodeEnum());
    }

    @Test
    void stableStepMetricsNullElementFailsSnapshot() {
        ChatTurnOperation op = operation();
        var claim = new ChatTurnOperationService.Claim(op, false);
        var metric = new ChatResponse.StepMetricRecord("Rerank", 12, 3);
        ChatResponse response = ChatResponse.builder()
                .answer("final")
                .stepMetrics(java.util.Arrays.asList(metric, null))
                .build();

        RagException error = assertThrows(RagException.class,
                () -> service(new ObjectMapper()).completeOpenAi(
                        claim, response, "PLAIN", "SERVER", "model-x", null));
        assertEquals(ErrorCode.IDEMPOTENCY_RESPONSE_TOO_LARGE,
                error.getErrorCodeEnum());
    }

    @Test
    void stableStepMetricsAreMappedThroughComplete() {
        var metric = new ChatResponse.StepMetricRecord("Rerank", 12, 3);
        when(repository.completeSuccess(any(), anyString(), anyString()))
                .thenReturn(true);
        var claim = new ChatTurnOperationService.Claim(operation(), false);
        ChatRequest request = new ChatRequest("问题", "session-1");
        request.setMode(ChatMode.KNOWLEDGE);

        var stable = service(new ObjectMapper()).complete(
                claim, responseWithMetrics(List.of(metric)), request);

        assertEquals(1, stable.getStepMetrics().size());
        assertEquals("Rerank",
                stable.getStepMetrics().getFirst().getStepName());
    }

    private ChatResponse responseWithMetrics(
            List<ChatResponse.StepMetricRecord> metrics) {
        return ChatResponse.builder()
                .answer("final")
                .stepMetrics(metrics)
                .build();
    }
}
