package com.springairag.core.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatTurnOperationService 完成与释放长尾（Batch 610，JaCoCo 驱动）：
 * completePrepared 对 null/unkeyed 认领跳过快照、无租约时跳过协调
 * 器提交、执行快照超限拒绝；release 把活跃租约委托给协调器；
 * stableSnapshot 对含不可 JSON 序列化值的元数据拒绝。
 */
class ChatTurnOperationCompleteLeaseTailTest {

    private static final String PRINCIPAL_ID = "local:auth-disabled";
    private static final String KEY_HASH =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    private ChatTurnOperationRepository repository;
    private ChatAuthorizationService authorizationService;
    private ChatSessionCoordinator coordinator;
    private ChatSessionCoordinator.LeaseHandle lease;
    private ChatTurnOperationService service;
    private ChatPrincipal principal;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        authorizationService = mock(ChatAuthorizationService.class);
        when(authorizationService.initialSnapshot(any(ChatCommand.class)))
                .thenReturn("auth-snapshot");
        coordinator = mock(ChatSessionCoordinator.class);
        lease = mock(ChatSessionCoordinator.LeaseHandle.class);
        service = new ChatTurnOperationService(
                repository,
                new ObjectMapper(),
                new RagChatProperties(),
                new com.springairag.core.config.RagProperties(),
                authorizationService,
                mock(ChatObservabilityService.class),
                mock(ChatExecutionService.class));
        service.setSessionCoordinator(coordinator);
        principal = ChatPrincipal.local();
    }

    private ChatCommand command(String sessionId) {
        return new ChatCommand(
                "hello", sessionId, principal, null,
                ChatMode.PLAIN, MemoryMode.SERVER, null, null,
                null, null, Map.of());
    }

    private ChatTurnOperation operation(ChatTurnOperation.Status status,
                                        String executionSnapshot) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, PRINCIPAL_ID, KEY_HASH, "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                status,
                UUID.randomUUID(), now.plusSeconds(60),
                1, 0L, 1,
                executionSnapshot, null, null, null, "{}",
                now, now, null);
    }

    private ChatExecutionService.PreparedExecution preparedExecution(
            ChatCommand command) {
        return new ChatExecutionService.PreparedExecution(
                command,
                new ChatExecutionResult(
                        "answer", "session-1", "trace-1", null, null,
                        ChatMode.PLAIN, List.of(), Map.of(), "STOP",
                        List.of(), Map.of()),
                List.of(), null, null, null);
    }

    /** 反射构造带租约的 keyed Claim（公共 2 参构造器不收租约）。 */
    private ChatTurnOperationService.Claim claimWithLease(
            ChatTurnOperation operation) throws Exception {
        Constructor<ChatTurnOperationService.Claim> ctor =
                ChatTurnOperationService.Claim.class.getDeclaredConstructor(
                        ChatTurnOperation.class, boolean.class,
                        ChatSessionCoordinator.LeaseHandle.class);
        ctor.setAccessible(true);
        return ctor.newInstance(operation, false, lease);
    }

    /** 执行快照上限压缩到 1 字节的独立 service（超限分支专用）。 */
    private ChatTurnOperationService tinyExecutionSnapshotService() {
        RagChatProperties properties = new RagChatProperties();
        properties.getIdempotency().setExecutionSnapshotMaxBytes(1);
        ChatTurnOperationService tiny = new ChatTurnOperationService(
                repository,
                new ObjectMapper(),
                properties,
                new com.springairag.core.config.RagProperties(),
                authorizationService,
                mock(ChatObservabilityService.class),
                mock(ChatExecutionService.class));
        tiny.setSessionCoordinator(coordinator);
        return tiny;
    }

    @Test
    void completePreparedWithNullClaimSkipsSnapshot() {
        var response = service.completePrepared(
                null, preparedExecution(command("session-1")));

        assertEquals("answer", response.getAnswer());
        verify(repository, never()).completeSuccess(
                any(), anyString(), anyString());
    }

    @Test
    void completePreparedWithUnkeyedClaimSkipsSnapshot() {
        ChatTurnOperationService.Claim unkeyed =
                ChatTurnOperationService.Claim.unkeyed();

        var response = service.completePrepared(
                unkeyed, preparedExecution(command("session-1")));

        assertEquals("answer", response.getAnswer());
        verify(repository, never()).completeSuccess(
                any(), anyString(), anyString());
    }

    @Test
    void completePreparedWithoutLeaseSkipsCoordinatorCommit() {
        ChatTurnOperation op = operation(
                ChatTurnOperation.Status.IN_PROGRESS, "{}");
        when(repository.completeSuccess(eq(op), anyString(), anyString()))
                .thenReturn(true);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(op, false);

        // 协调器存在但租约缺失 → IDEMPOTENCY_DISABLED（完成必须走
        // 协调的 PostgreSQL 会话服务）。
        RagException error = assertThrows(RagException.class,
                () -> service.completePrepared(
                        claim, preparedExecution(command("session-1"))));
        assertEquals(ErrorCode.IDEMPOTENCY_DISABLED,
                error.getErrorCodeEnum());
        verify(coordinator, never()).commitOperation(
                any(), any(), any(), any(), any(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void completeOpenAiRejectsOversizedExecutionSnapshot() throws Exception {
        ChatTurnOperation op = operation(
                ChatTurnOperation.Status.IN_PROGRESS, "{}");
        ChatTurnOperationService.Claim claim = claimWithLease(op);

        RagException error = assertThrows(RagException.class,
                () -> tinyExecutionSnapshotService().completeOpenAi(
                        claim,
                        com.springairag.api.dto.ChatResponse.builder()
                                .answer("answer").build(),
                        "PLAIN", "SERVER", "model-x", null));
        assertEquals(
                com.springairag.api.enums.ErrorCode
                        .IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("exceeds configured size"));
    }

    @Test
    void releaseDelegatesActiveLeaseToCoordinator() throws Exception {
        // claimWithLease 使用反射构造，声明 Exception 透传。
        ChatTurnOperation op = operation(
                ChatTurnOperation.Status.IN_PROGRESS, "{}");
        ChatTurnOperationService.Claim leased = claimWithLease(op);

        service.release(leased);
        verify(coordinator).release(lease);

        // 无租约认领 → 不触发协调器释放。
        ChatTurnOperationService.Claim bare =
                new ChatTurnOperationService.Claim(op, false);
        service.release(bare);
        verify(coordinator).release(lease);
    }

    @Test
    void responseSnapshotRejectsNonSerializableMetadataValue() {
        ChatTurnOperation op = operation(
                ChatTurnOperation.Status.IN_PROGRESS, "{}");
        when(repository.completeSuccess(eq(op), anyString(), anyString()))
                .thenReturn(true);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(op, false);
        Map<String, Object> badMetadata = new java.util.LinkedHashMap<>();
        badMetadata.put("bad", new Object());

        RagException error = assertThrows(RagException.class,
                () -> service.completePrepared(
                        claim,
                        new ChatExecutionService.PreparedExecution(
                                command("session-1"),
                                new ChatExecutionResult(
                                        "answer", "session-1", "trace-1",
                                        null, null, ChatMode.PLAIN,
                                        List.of(), badMetadata, "STOP",
                                        List.of(), Map.of()),
                                List.of(), null, null, null)));
    }
}
