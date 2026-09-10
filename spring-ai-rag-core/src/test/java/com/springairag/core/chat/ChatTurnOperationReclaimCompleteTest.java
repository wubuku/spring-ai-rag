package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatTurnOperationService} 回收与完成语义：过期 IN_PROGRESS
 * 的 reclaim 成功（缺快照重生成 / 已有快照保留）、reclaim 竞速失败
 * 回落到刷新后的重放、complete 快照持久化（稳定 turnId、超限拒绝、
 * 租约丢失失败标记）。claim 分派矩阵见 ChatTurnOperationClaimMatrixTest。
 */
class ChatTurnOperationReclaimCompleteTest {

    private static final String PRINCIPAL_ID = "local:auth-disabled";
    private static final String KEY_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private ChatTurnOperationRepository repository;
    private ChatAuthorizationService authorizationService;
    private ChatExecutionService executionService;
    private ChatTurnOperationService service;
    private ChatPrincipal principal;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        authorizationService = mock(ChatAuthorizationService.class);
        executionService = mock(ChatExecutionService.class);
        when(executionService.resolveCandidateRefs(any(), anyBoolean()))
                .thenReturn(List.of("vendor/model-a"));
        service = new ChatTurnOperationService(
                repository,
                new ObjectMapper().findAndRegisterModules(),
                new RagChatProperties(),
                new com.springairag.core.config.RagProperties(),
                authorizationService,
                mock(ChatObservabilityService.class),
                executionService);
        principal = ChatPrincipal.local();
    }

    private ChatTurnOperation operation(
            ChatTurnOperation.Status status,
            int attemptCount,
            Instant leaseExpiresAt,
            String executionSnapshot) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L,
                PRINCIPAL_ID,
                KEY_HASH,
                "fp-hash",
                1,
                "session-1",
                UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                status,
                UUID.randomUUID(),
                leaseExpiresAt,
                attemptCount,
                0L,
                1,
                executionSnapshot,
                null,
                null,
                null,
                "{}",
                now,
                now,
                null);
    }

    private ChatCommand command() {
        return new ChatCommand(
                "hello", "session-1", principal, null,
                ChatMode.KNOWLEDGE, null, null, null,
                null, null, null, null, List.of("vendor/model-a"),
                null, null, null);
    }

    private ChatTurnOperationService.Prepared prepared(
            ChatTurnOperation operation) {
        return new ChatTurnOperationService.Prepared(
                principal, KEY_HASH, "fp-hash", null, operation, true);
    }

    private ChatResponse response(String answer, String turnId) {
        return ChatResponse.builder()
                .answer(answer)
                .sessionId("session-1")
                .traceId("trace-1")
                .mode(ChatMode.KNOWLEDGE)
                .finishReason("STOP")
                .turnId(turnId)
                .build();
    }

    @Test
    void reclaimSuccessRegeneratesMissingExecutionSnapshot() {
        ChatTurnOperation current = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 1,
                Instant.now().minusSeconds(60), null);
        ChatTurnOperation reclaimed = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 2,
                Instant.now().plusSeconds(60), null);
        when(repository.find(PRINCIPAL_ID, KEY_HASH)).thenReturn(current);
        when(repository.reclaim(same(current), any(UUID.class), anyInt(),
                isNotNull(), anyInt())).thenReturn(reclaimed);

        ChatTurnOperationService.Claim claim = service.claim(
                prepared(current), command(),
                ChatTurnOperation.Transport.NATIVE_JSON, false);

        assertFalse(claim.replay());
        assertSame(reclaimed, claim.operation());
        verify(authorizationService).verifyReplay(current, principal);
        // 原快照缺失：回收时用当前命令重新生成执行快照。
        ArgumentCaptor<String> snapshotCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(repository).reclaim(same(current), any(UUID.class), anyInt(),
                snapshotCaptor.capture(), anyInt());
        assertNotNull(snapshotCaptor.getValue());
        assertTrue(snapshotCaptor.getValue().contains("executionSnapshotVersion"));
    }

    @Test
    void reclaimKeepsExistingExecutionSnapshot() {
        ChatTurnOperation current = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 1,
                Instant.now().minusSeconds(60),
                "{\"executionSnapshotVersion\":1}");
        ChatTurnOperation reclaimed = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 2,
                Instant.now().plusSeconds(60), null);
        when(repository.find(PRINCIPAL_ID, KEY_HASH)).thenReturn(current);
        when(repository.reclaim(same(current), any(UUID.class), anyInt(),
                isNull(), anyInt())).thenReturn(reclaimed);

        ChatTurnOperationService.Claim claim = service.claim(
                prepared(current), command(),
                ChatTurnOperation.Transport.NATIVE_JSON, false);

        assertFalse(claim.replay());
        assertSame(reclaimed, claim.operation());
        // 原快照存在：回收保持快照为 null（数据库已有值不被覆盖）。
        verify(repository).reclaim(same(current), any(UUID.class), anyInt(),
                isNull(), anyInt());
    }

    @Test
    void reclaimRaceFallsThroughToRefreshedReplay() {
        ChatTurnOperation current = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 1,
                Instant.now().minusSeconds(60), null);
        ChatTurnOperation refreshed = operation(
                ChatTurnOperation.Status.SUCCEEDED, 2,
                Instant.now().plusSeconds(60), null);
        when(repository.find(PRINCIPAL_ID, KEY_HASH))
                .thenReturn(current, refreshed);
        when(repository.reclaim(same(current), any(UUID.class), anyInt(),
                any(), anyInt())).thenReturn(null);

        ChatTurnOperationService.Claim claim = service.claim(
                prepared(current), command(),
                ChatTurnOperation.Transport.NATIVE_JSON, false);

        // 回收失败（他人抢先）→ 以刷新后的 operation 走重放。
        assertTrue(claim.replay());
        assertSame(refreshed, claim.operation());
        // find 首次仍返回过期 operation，递归回收两次后才读到刷新行。
        verify(repository, times(2)).reclaim(same(current), any(UUID.class),
                anyInt(), any(), anyInt());
    }

    @Test
    void completePersistsSnapshotAndReturnsStableResponse() {
        ChatTurnOperation operation = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 1,
                Instant.now().plusSeconds(60), null);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(operation, false);
        when(repository.completeSuccess(same(operation), anyString(),
                anyString())).thenReturn(true);
        ChatRequest request = new ChatRequest();
        request.setMode(ChatMode.KNOWLEDGE);
        request.setModel("model-x");

        ChatResponse stable = service.complete(
                claim, response("final", "req-turn"), request);

        assertEquals(operation.turnId().toString(), stable.getTurnId());
        assertEquals("final", stable.getAnswer());
        ArgumentCaptor<String> executionCaptor =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(repository).completeSuccess(same(operation),
                executionCaptor.capture(), payloadCaptor.capture());
        assertTrue(executionCaptor.getValue()
                .contains("\"publicModelAlias\":\"model-x\""));
        assertTrue(executionCaptor.getValue().contains("KNOWLEDGE"));
        assertTrue(payloadCaptor.getValue().contains("final"));
    }

    @Test
    void completeRejectsOversizedResponseSnapshot() {
        ChatTurnOperation operation = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 1,
                Instant.now().plusSeconds(60), null);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(operation, false);
        ChatRequest request = new ChatRequest();
        request.setMode(ChatMode.KNOWLEDGE);

        RagException error = assertThrows(RagException.class,
                () -> service.complete(claim,
                        response("x".repeat(600_000), "req-turn"), request));

        assertEquals(ErrorCode.IDEMPOTENCY_RESPONSE_TOO_LARGE,
                error.getErrorCodeEnum());
        verify(repository, never()).completeSuccess(
                any(), anyString(), anyString());
    }

    @Test
    void completeMarksFailedWhenLeaseLostBeforeCompletion() {
        ChatTurnOperation operation = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 1,
                Instant.now().plusSeconds(60), null);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(operation, false);
        when(repository.completeSuccess(same(operation), anyString(),
                anyString())).thenReturn(false);
        ChatRequest request = new ChatRequest();
        request.setMode(ChatMode.KNOWLEDGE);

        RagException error = assertThrows(RagException.class,
                () -> service.complete(claim,
                        response("final", "req-turn"), request));

        assertEquals(ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                error.getErrorCodeEnum());
    }

    @Test
    void completeSkipsUnkeyedClaimUntouched() {
        ChatTurnOperationService.Claim unkeyed =
                new ChatTurnOperationService.Claim(null, false);
        ChatResponse response = response("answer", "req-turn");

        ChatResponse stable = service.complete(
                unkeyed, response, new ChatRequest());

        assertSame(response, stable);
        verify(repository, never()).completeSuccess(
                any(), anyString(), anyString());
    }
}
