package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.ChatTurnInProgressException;
import com.springairag.core.exception.RagException;
import com.springairag.core.chat.ChatObservabilityService;
import com.springairag.core.repository.ChatTurnOperationRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.ApiKeyManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatTurnOperationService.inspectExisting 长尾（Batch 441）：
 * 未键控/空 prepared 返回 null、SUCCEEDED 即重放、FAILED 复现原
 * 错误、活跃租约内拒绝并附带剩余秒数。
 */
class ChatTurnOperationInspectTailTest {

    private ChatTurnOperationRepository repository;
    private ChatObservabilityService observability;
    private ApiKeyManagementService apiKeyManagementService;
    private ChatTurnOperationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        observability = mock(ChatObservabilityService.class);
        var objectMapper = new ObjectMapper();
        var chatProperties = new RagChatProperties();
        var ragProperties = new RagProperties();
        var authorizationService = new ChatAuthorizationService(
                objectMapper,
                mock(RagDocumentRepository.class),
                mock(ApiKeyManagementService.class));
        service = new ChatTurnOperationService(
                repository,
                objectMapper,
                chatProperties,
                ragProperties,
                authorizationService,
                observability,
                mock(ChatExecutionService.class));
    }

    private ChatTurnOperation operation(ChatTurnOperation.Status status,
                                        Instant leaseExpiresAt,
                                        String errorCode) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, "db:principal-1", "key-hash", "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                status, UUID.randomUUID(), leaseExpiresAt,
                1, 0L, 1,
                null, null, errorCode, "provider exploded",
                "{\"authorizationSnapshotVersion\":1,\"scopeMode\":\"NOT_APPLICABLE\"}",
                now, now, null);
    }

    private ChatTurnOperationService.Prepared preparedWith(
            ChatTurnOperation operation) {
        return new ChatTurnOperationService.Prepared(
                ChatPrincipal.local(), "key-hash", "fp-hash",
                null, operation, true);
    }

    @Test
    void unkeyedOrNullPreparedReturnsNull() {
        assertNull(service.inspectExisting(null));
        assertNull(service.inspectExisting(
                new ChatTurnOperationService.Prepared(
                        ChatPrincipal.local(), null, null, null, null, false)));
    }

    @Test
    void succeededOperationIsReplayed() {
        ChatTurnOperation operation =
                operation(ChatTurnOperation.Status.SUCCEEDED, null, null);
        when(repository.find(anyString(), anyString()))
                .thenReturn(operation);

        ChatTurnOperationService.Claim claim =
                service.inspectExisting(preparedWith(operation));

        assertNotNull(claim);
        assertTrue(claim.replay());
        assertEquals(operation, claim.operation());
    }

    @Test
    void failedOperationReplaysOriginalError() {
        ChatTurnOperation operation = operation(
                ChatTurnOperation.Status.FAILED,
                null, "INTERNAL_ERROR");

        RagException error = assertThrows(RagException.class,
                () -> service.inspectExisting(preparedWith(operation)));
        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCodeEnum());
    }

    @Test
    void activeLeaseRejectsWithInProgressException() {
        ChatTurnOperation operation = operation(
                ChatTurnOperation.Status.IN_PROGRESS,
                Instant.now().plusSeconds(30), null);

        ChatTurnInProgressException error = assertThrows(
                ChatTurnInProgressException.class,
                () -> service.inspectExisting(preparedWith(operation)));
        assertTrue(error.retryAfterSeconds() > 0);
    }

    @Test
    void expiredLeaseWithoutActiveClaimReturnsNull() {
        ChatTurnOperation operation = operation(
                ChatTurnOperation.Status.IN_PROGRESS,
                Instant.now().minusSeconds(30), null);

        assertNull(service.inspectExisting(preparedWith(operation)));
    }
}
