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
import org.springframework.ai.chat.client.ChatClientResponse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTurnOperationClaimMatrixTest {

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

    private ChatTurnOperationService.Prepared prepared(
            ChatTurnOperation operation) {
        return new ChatTurnOperationService.Prepared(
                principal, KEY_HASH, "fp-hash", null, operation, true);
    }

    private ChatTurnOperation operation(
            ChatTurnOperation.Status status,
            int attemptCount,
            Instant leaseExpiresAt,
            String errorCode) {
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
                null,
                null,
                errorCode,
                null,
                "{}",
                now,
                now,
                null);
    }

    private ChatTurnOperationService.Prepared keyedPrepared() {
        return new ChatTurnOperationService.Prepared(
                principal, KEY_HASH, "fp-hash", null, null, true);
    }

    @Test
    void unkeyedPreparedReturnsUnkeyedClaim() {
        ChatTurnOperationService.Prepared prepared =
                new ChatTurnOperationService.Prepared(
                        principal, null, null, null, null, false);

        ChatTurnOperationService.Claim claim = service.claim(
                prepared, "session-1", ChatTurnOperation.Transport.NATIVE_JSON);

        assertFalse(claim.keyed());
        verify(repository, never()).find(anyString(), anyString());
    }

    @Test
    void succeededOperationYieldsReplayClaim() {
        ChatTurnOperation succeeded = operation(
                ChatTurnOperation.Status.SUCCEEDED, 1,
                Instant.now().plusSeconds(60), null);
        ChatTurnOperationService.Prepared prepared = prepared(succeeded);
        when(repository.find(PRINCIPAL_ID, KEY_HASH))
                .thenReturn(succeeded);

        ChatTurnOperationService.Claim claim = service.claim(
                prepared, "session-1", ChatTurnOperation.Transport.NATIVE_JSON);

        assertTrue(claim.replay());
        assertSame(succeeded, claim.operation());
    }

    @Test
    void failedOperationThrowsReplayErrorWithOriginalCode() {
        ChatTurnOperation failed = operation(
                ChatTurnOperation.Status.FAILED, 1,
                Instant.now().plusSeconds(60), "INTERNAL_ERROR");
        ChatTurnOperationService.Prepared prepared = prepared(failed);
        when(repository.find(PRINCIPAL_ID, KEY_HASH))
                .thenReturn(failed);

        RagException error = assertThrows(RagException.class,
                () -> service.claim(
                        prepared, "session-1",
                        ChatTurnOperation.Transport.NATIVE_JSON));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("previously failed"));
    }

    @Test
    void liveInProgressOperationThrowsInProgressWithRetryHint() {
        ChatTurnOperation running = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 1,
                Instant.now().plusSeconds(45), null);
        ChatTurnOperationService.Prepared prepared = prepared(running);
        when(repository.find(PRINCIPAL_ID, KEY_HASH))
                .thenReturn(running);

        ChatTurnInProgressException error = assertThrows(
                ChatTurnInProgressException.class,
                () -> service.claim(
                        prepared, "session-1",
                        ChatTurnOperation.Transport.NATIVE_JSON));

        // retryAfterSeconds 记录在异常上，供 Retry-After 头使用。
        assertTrue(error.retryAfterSeconds() >= 1);
        verify(repository, never()).reclaim(
                any(), any(), anyInt(), anyInt());
    }

    @Test
    void expiredInProgressOperationIsReclaimedWithFreshToken() {
        ChatTurnOperation expired = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 1,
                Instant.now().minusSeconds(30), null);
        ChatTurnOperationService.Prepared prepared = prepared(expired);
        when(repository.find(PRINCIPAL_ID, KEY_HASH))
                .thenReturn(expired);
        ChatTurnOperation reclaimed = operation(
                ChatTurnOperation.Status.IN_PROGRESS, 2,
                Instant.now().plusSeconds(60), null);
        when(repository.reclaim(
                eq(expired), any(UUID.class), anyInt(), anyInt()))
                .thenReturn(reclaimed);

        ChatTurnOperationService.Claim claim = service.claim(
                prepared, "session-1", ChatTurnOperation.Transport.NATIVE_JSON);

        assertFalse(claim.replay());
        assertEquals(reclaimed, claim.operation());
        // 回收的键控声明会启动周期续期。
        assertNotNull(claim.renewal());
        claim.stopRenewal();
    }
}
