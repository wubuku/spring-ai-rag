package com.springairag.core.chat;

import com.springairag.api.dto.ChatTurnStatusResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * status 查询的分支矩阵：includeResponse 与轮次状态组合——成功轮
 * 携带反序列化响应、不带响应时仅标记 replayAvailable、FORBIDDEN 审
 * 计失败被吞、其他审计错误重抛、进行中轮次不做审计。
 */
class ChatTurnOperationStatusTest {

    private ChatTurnOperationRepository repository;
    private ChatAuthorizationService authorizationService;
    private ChatTurnOperationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        authorizationService = mock(ChatAuthorizationService.class);
        service = new ChatTurnOperationService(
                repository,
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules(),
                new RagChatProperties(),
                new RagProperties(),
                authorizationService,
                mock(ChatObservabilityService.class),
                mock(ChatExecutionService.class));
    }

    private ChatTurnOperation operation(
            ChatTurnOperation.Status status,
            String responsePayload) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L,
                ChatPrincipal.local().id(),
                "key-hash",
                "fingerprint-hash",
                1,
                "session-1",
                UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                status,
                UUID.randomUUID(),
                null,
                1,
                0L,
                1,
                null,
                responsePayload,
                null,
                null,
                "{}",
                now,
                now,
                now);
    }

    @Test
    void succeededTurnWithoutResponseFlagSkipsDeserialization() {
        ChatTurnOperation succeeded = operation(
                ChatTurnOperation.Status.SUCCEEDED,
                "{\"answer\":\"cached\"}");
        when(repository.findByTurn(anyString(), any(UUID.class)))
                .thenReturn(succeeded);

        ChatTurnStatusResponse response = service.status(
                ChatPrincipal.local(), succeeded.turnId(), false);

        verify(authorizationService).verifyReplay(succeeded, ChatPrincipal.local());
        assertTrue(response.replayAvailable());
        assertNull(response.response());
    }

    @Test
    void forbiddenReplayAuditIsSwallowedForStatusOnly() {
        ChatTurnOperation succeeded = operation(
                ChatTurnOperation.Status.SUCCEEDED,
                "{\"answer\":\"cached\"}");
        when(repository.findByTurn(anyString(), any(UUID.class)))
                .thenReturn(succeeded);
        doThrow(new RagException(
                ErrorCode.FORBIDDEN, "not your turn"))
                .when(authorizationService)
                .verifyReplay(any(), any());

        ChatTurnStatusResponse response = service.status(
                ChatPrincipal.local(), succeeded.turnId(), false);

        // FORBIDDEN 只影响 replayAvailable 标志，不阻断状态查询。
        assertFalse(response.replayAvailable());
        assertEquals("SUCCEEDED", response.status());
    }

    @Test
    void nonForbiddenAuditFailureIsRethrown() {
        ChatTurnOperation succeeded = operation(
                ChatTurnOperation.Status.SUCCEEDED,
                "{\"answer\":\"cached\"}");
        when(repository.findByTurn(anyString(), any(UUID.class)))
                .thenReturn(succeeded);
        doThrow(new RagException(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                "snapshot corrupted"))
                .when(authorizationService)
                .verifyReplay(any(), any());

        RagException error = assertThrows(RagException.class,
                () -> service.status(
                        ChatPrincipal.local(), succeeded.turnId(), false));
        assertEquals(
                ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void inProgressTurnSkipsAuthorizationAndReplay() {
        ChatTurnOperation running = operation(
                ChatTurnOperation.Status.IN_PROGRESS, null);
        when(repository.findByTurn(anyString(), any(UUID.class)))
                .thenReturn(running);

        ChatTurnStatusResponse response = service.status(
                ChatPrincipal.local(), running.turnId(), true);

        assertEquals("IN_PROGRESS", response.status());
        assertEquals(false, response.replayAvailable());
        assertNull(response.response());
        verify(authorizationService, never()).verifyReplay(any(), any());
    }

    @Test
    void corruptStoredSnapshotFailsWithInternalError() {
        ChatTurnOperation succeeded = operation(
                ChatTurnOperation.Status.SUCCEEDED,
                "{not-valid-json");
        when(repository.findByTurn(anyString(), any(UUID.class)))
                .thenReturn(succeeded);

        RagException error = assertThrows(RagException.class,
                () -> service.status(
                        ChatPrincipal.local(), succeeded.turnId(), true));
        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCodeEnum());
    }

    @Test
    void inProgressTurnReportsErrorCodeField() {
        ChatTurnOperation failed = operation(
                ChatTurnOperation.Status.FAILED, null);
        failed = new ChatTurnOperation(
                failed.id(),
                failed.ownerPrincipalId(),
                failed.idempotencyKeySha256(),
                failed.requestFingerprintSha256(),
                failed.fingerprintVersion(),
                failed.sessionId(),
                failed.turnId(),
                failed.transport(),
                failed.status(),
                failed.operationToken(),
                failed.leaseExpiresAt(),
                failed.attemptCount(),
                failed.rowVersion(),
                failed.responseVersion(),
                failed.executionSnapshot(),
                failed.responsePayload(),
                "MODEL_FAILED",
                "{\"error\":\"boom\"}",
                failed.authorizationScopeSnapshot(),
                failed.createdAt(),
                failed.updatedAt(),
                failed.completedAt());
        when(repository.findByTurn(anyString(), any(UUID.class)))
                .thenReturn(failed);

        ChatTurnStatusResponse response = service.status(
                ChatPrincipal.local(), failed.turnId(), false);

        assertEquals("FAILED", response.status());
        assertEquals("MODEL_FAILED", response.errorCode());
        verify(authorizationService, never()).verifyReplay(any(), any());
    }
}
