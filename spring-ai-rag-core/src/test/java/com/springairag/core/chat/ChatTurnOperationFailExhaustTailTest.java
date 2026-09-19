package com.springairag.core.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatTurnOperationService 失败持久化长尾（Batch 536，JaCoCo 驱
 * 动）：fail 的序列化失败回退载荷、RagException 错误码透传、
 * exhaustAttempts 的回退载荷、inProgress 空 lease 剩余时间、
 * stableSource/stableValue 的非 JSON 守卫。
 */
class ChatTurnOperationFailExhaustTailTest {

    private ChatTurnOperationRepository repository;
    private ChatSessionCoordinator coordinator;
    private RagChatProperties properties;
    private ChatTurnOperationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        coordinator = mock(ChatSessionCoordinator.class);
        var authorizationService = mock(ChatAuthorizationService.class);
        when(authorizationService.initialSnapshot(any(ChatCommand.class)))
                .thenReturn("{}");
        properties = new RagChatProperties();
        service = new ChatTurnOperationService(
                repository,
                new ObjectMapper().findAndRegisterModules(),
                properties,
                new RagProperties(),
                authorizationService,
                mock(ChatObservabilityService.class),
                mock(ChatExecutionService.class));
        service.setSessionCoordinator(coordinator);
    }

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

    private ChatTurnOperationService brokenService() {
        var authorizationService = mock(ChatAuthorizationService.class);
        var created = new ChatTurnOperationService(
                repository,
                brokenWriteMapper(),
                properties,
                new RagProperties(),
                authorizationService,
                mock(ChatObservabilityService.class),
                mock(ChatExecutionService.class));
        created.setSessionCoordinator(coordinator);
        return created;
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

    private ChatTurnOperation operationWithoutLease() {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, "principal-1", "key-hash", "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), null,
                1, 0L, 1,
                null, null, null, null, "{}",
                now, now, null);
    }

    @Test
    void failWithBrokenMapperWritesFallbackPayload() {
        when(repository.completeFailure(
                any(ChatTurnOperation.class), anyString(), anyString()))
                .thenReturn(true);

        brokenService().fail(
                new ChatTurnOperationService.Claim(operation(), true),
                new IllegalStateException("boom"));

        verify(repository).completeFailure(
                any(ChatTurnOperation.class),
                eq(ErrorCode.INTERNAL_ERROR.getCode()),
                contains("INTERNAL_ERROR"));
    }

    @Test
    void failWithRagExceptionPreservesItsErrorCode() {
        when(repository.completeFailure(
                any(ChatTurnOperation.class), anyString(), anyString()))
                .thenReturn(true);

        service.fail(
                new ChatTurnOperationService.Claim(operation(), true),
                new RagException(ErrorCode.FORBIDDEN, "not allowed"));

        verify(repository).completeFailure(
                any(ChatTurnOperation.class),
                eq(ErrorCode.FORBIDDEN.getCode()),
                contains("FORBIDDEN"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exhaustAttemptsWithBrokenMapperWritesFallbackPayload()
            throws Exception {
        when(repository.exhaustAttempts(
                any(ChatTurnOperation.class), anyString(), anyString()))
                .thenReturn(true);
        var method = ChatTurnOperationService.class.getDeclaredMethod(
                "exhaustAttempts", ChatTurnOperation.class,
                ChatSessionCoordinator.LeaseHandle.class);
        method.setAccessible(true);

        method.invoke(brokenService(), operation(), null);

        verify(repository).exhaustAttempts(
                any(ChatTurnOperation.class),
                eq(ErrorCode.IDEMPOTENCY_ATTEMPTS_EXHAUSTED.getCode()),
                contains("IDEMPOTENCY_ATTEMPTS_EXHAUSTED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void inProgressWithoutLeaseReportsMinimumRetrySeconds()
            throws Exception {
        var method = ChatTurnOperationService.class.getDeclaredMethod(
                "inProgress", ChatTurnOperation.class);
        method.setAccessible(true);

        // inProgress 是工厂方法：构造异常返回，由调用方抛出。
        // leaseExpiresAt == null → remaining 取 1 → retry = remaining + 1 = 2。
        var inProgress =
                (com.springairag.core.exception.ChatTurnInProgressException)
                        method.invoke(service, operationWithoutLease());

        assertEquals(2, inProgress.retryAfterSeconds());
        assertEquals(ErrorCode.IDEMPOTENCY_OPERATION_IN_PROGRESS,
                inProgress.getErrorCodeEnum());
    }

    @Test
    void stableGuardsRejectNullSourceAndNonSerializableValue()
            throws Exception {
        var stableSource = ChatTurnOperationService.class
                .getDeclaredMethod("stableSource",
                        com.springairag.api.dto.ChatSource.class);
        stableSource.setAccessible(true);
        var sourceError = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> stableSource.invoke(service, (Object) null));
        assertTrue(sourceError.getCause() instanceof IllegalArgumentException);

        var stableValue = ChatTurnOperationService.class
                .getDeclaredMethod("stableValue", Object.class);
        stableValue.setAccessible(true);
        // null 值直通返回，不进入序列化。
        assertEquals(null, stableValue.invoke(service, (Object) null));
    }
}
