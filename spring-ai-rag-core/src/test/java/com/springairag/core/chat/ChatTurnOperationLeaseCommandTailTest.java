package com.springairag.core.chat;

import com.springairag.core.exception.ChatTurnInProgressException;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.repository.ChatTurnOperationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轮次操作租约与命令快照长尾（Batch 603，JaCoCo 驱动）：三条认领
 * 路径（prepare 前置、inspectExisting、claimExisting）对活跃租约
 * 的 in-progress 报告，以及 commandForClaim 将快照会话与候选链
 * 应用到适配器命令。
 */
class ChatTurnOperationLeaseCommandTailTest {

    private static final String PRINCIPAL_ID = "local:auth-disabled";
    private static final String KEY_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private ChatTurnOperationRepository repository;
    private ChatObservabilityService observability;
    private ChatTurnOperationService service;
    private ChatPrincipal principal;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        observability = mock(ChatObservabilityService.class);
        service = new ChatTurnOperationService(
                repository,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new com.springairag.core.config.RagChatProperties(),
                new com.springairag.core.config.RagProperties(),
                mock(ChatAuthorizationService.class),
                observability,
                mock(ChatExecutionService.class));
        principal = ChatPrincipal.local();
    }

    private ChatTurnOperation operation(ChatTurnOperation.Status status) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, PRINCIPAL_ID, KEY_HASH, "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                status,
                UUID.randomUUID(), now.plusSeconds(600),
                1, 0L, 1,
                null, null, null, null, "{}",
                now, now, null);
    }

    private ChatTurnOperationService.Prepared prepared(
            ChatTurnOperation operation) {
        return new ChatTurnOperationService.Prepared(
                principal, KEY_HASH, "fp-hash", (com.fasterxml.jackson.databind.JsonNode) null,
                operation, true);
    }

    private ChatCommand command(String sessionId) {
        return new ChatCommand(
                "hello", sessionId, principal, null,
                ChatMode.PLAIN, MemoryMode.SERVER, null, null,
                null, null, java.util.Map.of());
    }

    @Test
    void claimReportsInProgressWhenLeaseIsActive() {
        assertThrows(
                ChatTurnInProgressException.class,
                () -> service.claim(
                        prepared(operation(ChatTurnOperation.Status.IN_PROGRESS)),
                        "session-1",
                        ChatTurnOperation.Transport.NATIVE_JSON));
        verify(observability).inProgress();
    }

    @Test
    void inspectExistingReportsInProgressWhenLeaseIsActive() {
        assertThrows(ChatTurnInProgressException.class,
                () -> service.inspectExisting(
                        prepared(operation(ChatTurnOperation.Status.IN_PROGRESS))));
        verify(observability).inProgress();
    }

    @Test
    void keyedClaimReportsInProgressWhenLeaseIsActive() {
        ChatTurnOperation running = operation(ChatTurnOperation.Status.IN_PROGRESS);
        when(repository.find(PRINCIPAL_ID, KEY_HASH))
                .thenReturn(running);

        assertThrows(ChatTurnInProgressException.class,
                () -> service.claim(
                        prepared(null),
                        command("session-1"),
                        ChatTurnOperation.Transport.NATIVE_JSON,
                        false));
        verify(observability).inProgress();
    }

    @Test
    void commandForClaimAppliesSnapshotSessionAndCandidates() {
        ChatTurnOperation claimed = operation(ChatTurnOperation.Status.IN_PROGRESS);
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(claimed, false);

        ChatCommand effective = service.commandForClaim(
                command("session-1"), claim);

        // 快照未提供候选链 → 保留原命令的空候选。
        assertEquals(List.of(), effective.modelCandidates());
    }

    @Test
    void commandForClaimAppliesCandidatesFromExecutionSnapshot() {
        ChatTurnOperation base = operation(ChatTurnOperation.Status.IN_PROGRESS);
        String snapshot = """
                {"executionSnapshotVersion":1,
                 "resolvedCandidates":["provider/a","provider/b"]}"""
                .replaceAll("\\s+", "");
        ChatTurnOperation withSnapshot = new ChatTurnOperation(
                base.id(), base.ownerPrincipalId(), base.idempotencyKeySha256(),
                base.requestFingerprintSha256(), base.fingerprintVersion(),
                base.sessionId(), base.turnId(), base.transport(), base.status(),
                base.operationToken(), base.leaseExpiresAt(), base.attemptCount(),
                base.rowVersion(), base.responseVersion(), snapshot,
                base.responsePayload(), base.errorCode(), base.errorPayload(),
                base.authorizationScopeSnapshot(), base.createdAt(),
                base.updatedAt(), base.completedAt());
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(withSnapshot, false);

        ChatCommand effective = service.commandForClaim(
                command("session-1"), claim);

        // 候选链必须来自持久化快照而非当前注册表。
        assertEquals(List.of("provider/a", "provider/b"),
                effective.modelCandidates());
        assertEquals("session-1", effective.sessionId());
    }
}
