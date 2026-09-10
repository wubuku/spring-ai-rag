package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.ChatTurnInProgressException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * claim(prepared, sessionId, transport) 原生适配器路径：同键重放、
 * 失败复现、进行中拒绝、尝试次数耗尽 fail-closed、过期回收与回收
 * 竞速回落、新键插入（会话规范化 + 授权快照）与插入竞速回落。
 */
class ChatTurnOperationNativeClaimTest {

    private static final String PRINCIPAL_ID = "local:auth-disabled";
    private static final String KEY_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private ChatTurnOperationRepository repository;
    private ChatTurnOperationService service;
    private ChatPrincipal principal;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        service = new ChatTurnOperationService(
                repository,
                new ObjectMapper(),
                new RagChatProperties(),
                new com.springairag.core.config.RagProperties(),
                mock(ChatAuthorizationService.class),
                mock(ChatObservabilityService.class),
                mock(ChatExecutionService.class));
        principal = ChatPrincipal.local();
    }

    private ChatTurnOperation operation(
            ChatTurnOperation.Status status,
            int attemptCount,
            Instant leaseExpiresAt,
            String errorCode) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, PRINCIPAL_ID, KEY_HASH, "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                status, UUID.randomUUID(), leaseExpiresAt,
                attemptCount, 0L, 1,
                null, null, errorCode, null, "{}",
                now, now, null);
    }

    private ChatTurnOperationService.Prepared prepared(
            ChatTurnOperation operation) {
        return new ChatTurnOperationService.Prepared(
                principal, KEY_HASH, "fp-hash", null, operation, true);
    }

    private ChatTurnOperationService.Claim claimNative(
            ChatTurnOperationService.Prepared prepared) {
        return service.claim(prepared, "session-1",
                ChatTurnOperation.Transport.NATIVE_JSON);
    }

    @Test
    void succeededOperationReplaysForSameNativeKey() {
        ChatTurnOperation succeeded = operation(
                ChatTurnOperation.Status.SUCCEEDED, 1,
                Instant.now().plusSeconds(60), null);
        when(repository.find(PRINCIPAL_ID, KEY_HASH)).thenReturn(succeeded);

        ChatTurnOperationService.Claim claim = claimNative(prepared(succeeded));

        assertTrue(claim.replay());
        assertSame(succeeded, claim.operation());
    }

    @Test
    void failedOperationSurfacesPriorErrorCode() {
        ChatTurnOperation failed = operation(
                ChatTurnOperation.Status.FAILED, 3,
                Instant.now().minusSeconds(60), "BAD_REQUEST");

        RagException error = assertThrows(RagException.class,
                () -> claimNative(prepared(failed)));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCodeEnum());
    }

    @Test
    void inProgressLeaseRejectsWithRetryAfterSeconds() {
        ChatTurnOperation running = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 1,
                Instant.now().plusSeconds(45), null);

        ChatTurnInProgressException error = assertThrows(
                ChatTurnInProgressException.class,
                () -> claimNative(prepared(running)));

        assertEquals(ErrorCode.IDEMPOTENCY_OPERATION_IN_PROGRESS,
                error.getErrorCodeEnum());
        assertTrue(error.retryAfterSeconds() >= 1
                && error.retryAfterSeconds() <= 60);
    }

    @Test
    void exhaustedAttemptsFailClosedThroughRepositoryMarking() {
        ChatTurnOperation exhausted = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 3,
                Instant.now().minusSeconds(60), null);
        when(repository.find(PRINCIPAL_ID, KEY_HASH)).thenReturn(operation(
                ChatTurnOperation.Status.FAILED, 3,
                Instant.now().minusSeconds(60),
                "IDEMPOTENCY_ATTEMPTS_EXHAUSTED"));
        when(repository.exhaustAttempts(
                same(exhausted), anyString(), anyString())).thenReturn(true);

        RagException error = assertThrows(RagException.class,
                () -> claimNative(prepared(exhausted)));

        assertEquals(ErrorCode.IDEMPOTENCY_ATTEMPTS_EXHAUSTED,
                error.getErrorCodeEnum());
        verify(repository).exhaustAttempts(
                same(exhausted), anyString(), anyString());
    }

    @Test
    void exhaustedAttemptsMarkingFailurePersistsAsUnavailable() {
        ChatTurnOperation exhausted = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 3,
                Instant.now().minusSeconds(60), null);
        when(repository.exhaustAttempts(
                same(exhausted), anyString(), anyString())).thenReturn(false);

        RagException error = assertThrows(RagException.class,
                () -> claimNative(prepared(exhausted)));

        assertEquals(ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                error.getErrorCodeEnum());
    }

    @Test
    void expiredOperationIsReclaimedForAnotherAttempt() {
        ChatTurnOperation expired = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 1,
                Instant.now().minusSeconds(60), null);
        ChatTurnOperation reclaimed = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 2,
                Instant.now().plusSeconds(60), null);
        when(repository.reclaim(
                same(expired), any(UUID.class), anyInt(), anyInt()))
                .thenReturn(reclaimed);

        ChatTurnOperationService.Claim claim = claimNative(prepared(expired));

        assertFalse(claim.replay());
        assertSame(reclaimed, claim.operation());
    }

    @Test
    void reclaimRaceRecursesToRefreshedReplay() {
        ChatTurnOperation expired = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 1,
                Instant.now().minusSeconds(60), null);
        ChatTurnOperation refreshed = operation(
                ChatTurnOperation.Status.SUCCEEDED, 2,
                Instant.now().plusSeconds(60), null);
        when(repository.find(PRINCIPAL_ID, KEY_HASH))
                .thenReturn(expired, refreshed);
        when(repository.reclaim(
                same(expired), any(UUID.class), anyInt(), anyInt()))
                .thenReturn(null);

        ChatTurnOperationService.Claim claim = claimNative(prepared(expired));

        assertTrue(claim.replay());
        assertSame(refreshed, claim.operation());
    }

    @Test
    void newKeyInsertsAuthorizationSnapshotAndNormalizesSession() {
        ChatTurnOperationService.Prepared prepared =
                new ChatTurnOperationService.Prepared(
                        principal, KEY_HASH, "fp-hash", null, null, true);
        ChatTurnOperation inserted = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 1,
                Instant.now().plusSeconds(60), null);
        when(repository.insert(
                anyString(), anyString(), anyString(), anyString(),
                any(UUID.class), any(), any(UUID.class), anyInt(), anyString()))
                .thenReturn(true);
        when(repository.find(PRINCIPAL_ID, KEY_HASH)).thenReturn(inserted);

        // 非法会话 id 在插入前被规范化为合法 UUID。
        ChatTurnOperationService.Claim claim = service.claim(
                prepared, "bad session!!",
                ChatTurnOperation.Transport.NATIVE_JSON);

        assertFalse(claim.replay());
        assertSame(inserted, claim.operation());
        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> snapshotCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).insert(
                eqOrNull(PRINCIPAL_ID), eqOrNull(KEY_HASH), eqOrNull("fp-hash"),
                sessionCaptor.capture(), any(UUID.class), any(),
                any(UUID.class), anyInt(), snapshotCaptor.capture());
        UUID normalized = UUID.fromString(sessionCaptor.getValue());
        assertNotEquals("bad session!!", sessionCaptor.getValue());
        assertEquals(normalized.toString(), sessionCaptor.getValue());
        assertTrue(snapshotCaptor.getValue().contains("\"callerAllowList\":[]"));
    }

    private static <T> T eqOrNull(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    @Test
    void insertRaceRecursesToExistingReplay() {
        ChatTurnOperationService.Prepared prepared =
                new ChatTurnOperationService.Prepared(
                        principal, KEY_HASH, "fp-hash", null, null, true);
        ChatTurnOperation existing = operation(
                ChatTurnOperation.Status.SUCCEEDED, 1,
                Instant.now().plusSeconds(60), null);
        when(repository.insert(
                anyString(), anyString(), anyString(), anyString(),
                any(UUID.class), any(), any(UUID.class), anyInt(), anyString()))
                .thenReturn(false);
        when(repository.find(PRINCIPAL_ID, KEY_HASH))
                .thenReturn(existing, existing);

        ChatTurnOperationService.Claim claim = claimNative(prepared);

        assertTrue(claim.replay());
        assertSame(existing, claim.operation());
        // 回落仅查询一次：withOperation 携带现有 operation 直接重放。
        verify(repository, times(1)).find(PRINCIPAL_ID, KEY_HASH);
    }
}
