package com.springairag.core.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatTurnOperationService 4 参 ChatCommand claim 长尾与 complete
 * 长尾（Batch 473，JaCoCo 驱动）：insertNewOperation 三分支（成
 * 功持租约 / 插入竞争重派 / 异常释放租约）、claimNew 租约错误传
 * 播与 SESSION_BUSY 竞争、claimExisting 指纹冲突与 FAILED 拒绝、
 * completePrepared 的未键控/缺快照/缺协调器/响应超限/正常提交，
 * 以及 stableSnapshot 对 null 响应、null source、null metadata 值
 * 的容错矩阵与 commandForClaim 的坏快照拒绝。
 */
class ChatTurnOperationClaimCompleteTailTest {

    private static final String PRINCIPAL_ID = "local:auth-disabled";
    private static final String KEY_HASH =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

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
        // anyString() 不匹配 null：initialSnapshot 必须返回真实值，
        // 否则 10 参 insert stub 永远不命中。
        when(authorizationService.initialSnapshot(any(ChatCommand.class)))
                .thenReturn("auth-snapshot");
        ChatExecutionService executionService = mock(ChatExecutionService.class);
        when(executionService.resolveCandidateRefs(any(), anyBoolean()))
                .thenReturn(List.of("vendor/model-a"));
        coordinator = mock(ChatSessionCoordinator.class);
        lease = mock(ChatSessionCoordinator.LeaseHandle.class);
        service = new ChatTurnOperationService(
                repository,
                new ObjectMapper(),
                new RagChatProperties(),
                new RagProperties(),
                authorizationService,
                mock(ChatObservabilityService.class),
                executionService);
        service.setSessionCoordinator(coordinator);
        principal = ChatPrincipal.local();
    }

    /** 响应快照上限压缩到 1 字节的独立 service（超限分支专用）。 */
    private ChatTurnOperationService tinySnapshotService() {
        ChatTurnOperationRepository repo2 =
                mock(ChatTurnOperationRepository.class);
        RagChatProperties properties = new RagChatProperties();
        properties.getIdempotency().setResponseSnapshotMaxBytes(1);
        ChatTurnOperationService tiny = new ChatTurnOperationService(
                repo2,
                new ObjectMapper(),
                properties,
                new RagProperties(),
                mock(ChatAuthorizationService.class),
                mock(ChatObservabilityService.class),
                null);
        tiny.setSessionCoordinator(coordinator);
        return tiny;
    }

    private ChatCommand command(String sessionId) {
        return new ChatCommand(
                "hello", sessionId, principal, null,
                ChatMode.PLAIN, MemoryMode.SERVER, null, null,
                null, null, Map.of());
    }

    private ChatTurnOperation operation(ChatTurnOperation.Status status) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, PRINCIPAL_ID, KEY_HASH, "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                status,
                UUID.randomUUID(), now.plusSeconds(60),
                1, 0L, 1,
                null, null, null, null, "{}",
                now, now, null);
    }

    private ChatTurnOperationService.Prepared prepared(
            ChatTurnOperation operation) {
        return new ChatTurnOperationService.Prepared(
                principal, KEY_HASH, "fp-hash", (JsonNode) null,
                operation, true);
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

    private com.springairag.api.dto.ChatResponse chatResponse(
            String answer, Map<String, Object> metadata,
            List<com.springairag.api.dto.ChatSource> sources) {
        return com.springairag.api.dto.ChatResponse.builder()
                .answer(answer)
                .metadata(metadata)
                .sources(sources)
                .build();
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

    // ── 4 参 claim：claimNew / insertNewOperation ─────────────────

    @Test
    void claimByChatCommandInsertsOperationAndHoldsLease() throws Exception {
        ChatTurnOperation op = operation(ChatTurnOperation.Status.IN_PROGRESS);
        when(repository.find(PRINCIPAL_ID, KEY_HASH))
                .thenReturn(null)
                .thenReturn(op);
        when(repository.insert(
                anyString(), anyString(), anyString(), anyString(),
                any(UUID.class), any(), any(UUID.class), anyInt(),
                anyString(), anyString()))
                .thenReturn(true);
        when(coordinator.acquire(any(ChatCommand.class), anyBoolean()))
                .thenReturn(lease);

        ChatTurnOperationService.Claim claim = service.claim(
                prepared(null), command("session-1"),
                ChatTurnOperation.Transport.NATIVE_JSON, false);

        assertFalse(claim.replay());
        assertSame(op, claim.operation());
        assertSame(lease, claim.sessionLease());
        ArgumentCaptor<String> snapshot =
                ArgumentCaptor.forClass(String.class);
        verify(repository).insert(
                eq(PRINCIPAL_ID), eq(KEY_HASH), eq("fp-hash"),
                eq("session-1"), any(UUID.class),
                eq(ChatTurnOperation.Transport.NATIVE_JSON),
                any(UUID.class), anyInt(),
                snapshot.capture(), anyString());
        JsonNode parsed = new ObjectMapper().readTree(snapshot.getValue());
        assertEquals("PLAIN", parsed.path("mode").asText());
        assertEquals("vendor/model-a",
                parsed.path("resolvedCandidates").get(0).asText());
        assertEquals("DEFAULT",
                parsed.path("declaredModelIdentifier").asText());
        verify(coordinator).acquire(any(ChatCommand.class), eq(false));
    }

    @Test
    void insertFailureReleasesLeaseAndRethrows() {
        when(repository.find(PRINCIPAL_ID, KEY_HASH)).thenReturn(null);
        when(repository.insert(
                anyString(), anyString(), anyString(), anyString(),
                any(UUID.class), any(), any(UUID.class), anyInt(),
                anyString(), anyString()))
                .thenThrow(new IllegalStateException("db down"));
        when(coordinator.acquire(any(ChatCommand.class), anyBoolean()))
                .thenReturn(lease);

        assertThrows(IllegalStateException.class,
                () -> service.claim(prepared(null), command("session-1"),
                        ChatTurnOperation.Transport.NATIVE_JSON, false));

        verify(coordinator).release(lease);
    }

    @Test
    void lostInsertRaceRedispachesToNewestOperation() {
        ChatTurnOperation winner = operation(ChatTurnOperation.Status.SUCCEEDED);
        when(repository.find(PRINCIPAL_ID, KEY_HASH))
                .thenReturn(null)
                .thenReturn(winner);
        when(repository.insert(
                anyString(), anyString(), anyString(), anyString(),
                any(UUID.class), any(), any(UUID.class), anyInt(),
                anyString(), anyString()))
                .thenReturn(false);
        when(coordinator.acquire(any(ChatCommand.class), anyBoolean()))
                .thenReturn(lease);

        ChatTurnOperationService.Claim claim = service.claim(
                prepared(null), command("session-1"),
                ChatTurnOperation.Transport.NATIVE_JSON, false);

        assertTrue(claim.replay());
        assertSame(winner, claim.operation());
        verify(coordinator).release(lease);
        assertNull(claim.sessionLease());
    }

    @Test
    void nonBusyLeaseErrorPropagates() {
        when(repository.find(PRINCIPAL_ID, KEY_HASH)).thenReturn(null);
        when(coordinator.acquire(any(ChatCommand.class), anyBoolean()))
                .thenThrow(new RagException(
                        ErrorCode.INTERNAL_ERROR, "lease store down"));

        RagException error = assertThrows(RagException.class,
                () -> service.claim(prepared(null), command("session-1"),
                        ChatTurnOperation.Transport.NATIVE_JSON, false));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCodeEnum());
    }

    @Test
    void busyRaceWithoutOperationPropagatesSessionBusy() {
        when(repository.find(PRINCIPAL_ID, KEY_HASH)).thenReturn(null);
        when(coordinator.acquire(any(ChatCommand.class), anyBoolean()))
                .thenThrow(new RagException(
                        ErrorCode.SESSION_BUSY, "session busy"));

        RagException error = assertThrows(RagException.class,
                () -> service.claim(prepared(null), command("session-1"),
                        ChatTurnOperation.Transport.NATIVE_JSON, false));

        assertEquals(ErrorCode.SESSION_BUSY, error.getErrorCodeEnum());
    }

    // ── 4 参 claim：claimExisting 拒绝矩阵 ────────────────────────

    @Test
    void fingerprintMismatchIsRejected() {
        ChatTurnOperation other = operation(ChatTurnOperation.Status.IN_PROGRESS);
        ChatTurnOperationService.Prepared prepared = new ChatTurnOperationService.Prepared(
                principal, KEY_HASH, "other-fingerprint", (JsonNode) null,
                other, true);

        RagException error = assertThrows(RagException.class,
                () -> service.claim(prepared, command("session-1"),
                        ChatTurnOperation.Transport.NATIVE_JSON, false));

        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                error.getErrorCodeEnum());
    }

    @Test
    void failedOperationIsRejectedOnClaim() {
        ChatTurnOperation failed = operation(ChatTurnOperation.Status.FAILED);
        RagException error = assertThrows(RagException.class,
                () -> service.claim(prepared(failed), command("session-1"),
                        ChatTurnOperation.Transport.NATIVE_JSON, false));

        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCodeEnum());
    }

    // ── completePrepared ──────────────────────────────────────────

    @Test
    void completePreparedUnkeyedReturnsMappedResponse() {
        ChatTurnOperationService.Claim unkeyed =
                new ChatTurnOperationService.Claim(null, false);

        var response = service.completePrepared(
                unkeyed, preparedExecution(command("session-1")));

        assertEquals("answer", response.getAnswer());
        verify(repository, never()).completeSuccess(any(), anyString(),
                anyString());
    }

    @Test
    void completePreparedMissingExecutionSnapshotIsRejected() {
        ChatTurnOperation op = operation(ChatTurnOperation.Status.IN_PROGRESS);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(op, false);

        RagException error = assertThrows(RagException.class,
                () -> service.completePrepared(
                        claim, preparedExecution(command("session-1"))));

        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void completePreparedWithoutCoordinatorLeaseIsRejected() {
        ChatTurnOperation op = operation(ChatTurnOperation.Status.IN_PROGRESS);
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
                () -> service.completePrepared(
                        claim, preparedExecution(command("session-1"))));

        assertEquals(ErrorCode.IDEMPOTENCY_DISABLED,
                error.getErrorCodeEnum());
    }

    @Test
    void completePreparedHappyPathCommitsOperation() throws Exception {
        ChatTurnOperation op = operation(ChatTurnOperation.Status.IN_PROGRESS);
        ChatTurnOperation opWithSnapshot = new ChatTurnOperation(
                op.id(), op.ownerPrincipalId(), op.idempotencyKeySha256(),
                op.requestFingerprintSha256(), op.fingerprintVersion(),
                op.sessionId(), op.turnId(), op.transport(), op.status(),
                op.operationToken(), op.leaseExpiresAt(), op.attemptCount(),
                op.rowVersion(), op.responseVersion(), "{}",
                op.responsePayload(), op.errorCode(), op.errorPayload(),
                op.authorizationScopeSnapshot(), op.createdAt(),
                op.updatedAt(), op.completedAt());
        when(authorizationService.snapshot(any(ChatCommand.class), any()))
                .thenReturn("auth-snapshot");
        ChatTurnOperationService.Claim claim = claimWithLease(opWithSnapshot);

        var response = service.completePrepared(
                claim, preparedExecution(command("session-1")));

        assertEquals("answer", response.getAnswer());
        assertEquals(opWithSnapshot.turnId().toString(),
                response.getTurnId());
        verify(coordinator).commitOperation(
                same(lease), same(opWithSnapshot), any(ChatCommand.class),
                any(ChatExecutionResult.class), anyList(), any(),
                eq("{}"), anyString(), eq("auth-snapshot"));
    }

    @Test
    void completePreparedResponseTooLargeIsRejected() {
        ChatTurnOperationService tiny = tinySnapshotService();
        ChatTurnOperation op = operation(ChatTurnOperation.Status.IN_PROGRESS);
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
                () -> tiny.completePrepared(
                        claim, preparedExecution(command("session-1"))));

        assertEquals(ErrorCode.IDEMPOTENCY_RESPONSE_TOO_LARGE,
                error.getErrorCodeEnum());
    }

    // ── completeOpenAi / stableSnapshot 容错矩阵 ──────────────────

    @Test
    void completeOpenAiResponseTooLargeIsRejected() {
        ChatTurnOperationService tiny = tinySnapshotService();
        ChatTurnOperation op = operation(ChatTurnOperation.Status.SUCCEEDED);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(op, false);

        RagException error = assertThrows(RagException.class,
                () -> tiny.completeOpenAi(claim,
                        chatResponse("final", Map.of(), List.of()),
                        "PLAIN", "SERVER", "model-x", null));

        assertEquals(ErrorCode.IDEMPOTENCY_RESPONSE_TOO_LARGE,
                error.getErrorCodeEnum());
    }

    @Test
    void completeOpenAiRejectsNullResponse() {
        ChatTurnOperation op = operation(ChatTurnOperation.Status.SUCCEEDED);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(op, false);

        assertThrows(RuntimeException.class,
                () -> service.completeOpenAi(claim, null,
                        "PLAIN", "SERVER", "model-x", null));
    }

    @Test
    void completeOpenAiRejectsNullSourceElement() {
        ChatTurnOperation op = operation(ChatTurnOperation.Status.SUCCEEDED);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(op, false);

        assertThrows(RuntimeException.class,
                () -> service.completeOpenAi(claim,
                        chatResponse("final", Map.of(),
                                Arrays.asList((com.springairag.api.dto.ChatSource) null)),
                        "PLAIN", "SERVER", "model-x", null));
    }

    @Test
    void completeOpenAiToleratesNullMetadataValues() {
        ChatTurnOperation op = operation(ChatTurnOperation.Status.SUCCEEDED);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(op, false);
        when(repository.completeSuccess(same(op), anyString(), anyString()))
                .thenReturn(true);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("mode", null);

        var stable = service.completeOpenAi(claim,
                chatResponse("final", metadata, List.of()),
                "PLAIN", "SERVER", "model-x", null);

        assertEquals("final", stable.getAnswer());
        assertNotNull(stable.getMetadata());
        assertTrue(stable.getMetadata().containsKey("mode"));
    }

    // ── commandForClaim 坏快照 ────────────────────────────────────

    @Test
    void commandForClaimRejectsCorruptExecutionSnapshot() {
        ChatTurnOperation op = operation(ChatTurnOperation.Status.IN_PROGRESS);
        ChatTurnOperation corrupt = new ChatTurnOperation(
                op.id(), op.ownerPrincipalId(), op.idempotencyKeySha256(),
                op.requestFingerprintSha256(), op.fingerprintVersion(),
                op.sessionId(), op.turnId(), op.transport(), op.status(),
                op.operationToken(), op.leaseExpiresAt(), op.attemptCount(),
                op.rowVersion(), op.responseVersion(), "not-json",
                op.responsePayload(), op.errorCode(), op.errorPayload(),
                op.authorizationScopeSnapshot(), op.createdAt(),
                op.updatedAt(), op.completedAt());
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(corrupt, false);

        RagException error = assertThrows(RagException.class,
                () -> service.commandForClaim(
                        command("session-1"), claim));

        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }
}
