package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 轮次操作快照解析与重放主体长尾（Batch 604，JaCoCo 驱动）：
 * commandForClaim 对缺失/非法候选链快照的拒绝、resolvedCandidateRefs
 * 的空解析拒绝、failedReplay 对未知错误码回退 INTERNAL_ERROR、
 * replay 对 db/root/legacy 三类主体身份的构造。
 */
class ChatTurnOperationSnapshotCandidateChainTailTest {

    private static final String PRINCIPAL_ID = "local:auth-disabled";
    private static final String KEY_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private ChatTurnOperationRepository repository;
    private ChatExecutionService executionService;
    private ChatTurnOperationService service;
    private ChatPrincipal principal;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        executionService = mock(ChatExecutionService.class);
        service = new ChatTurnOperationService(
                repository,
                new ObjectMapper(),
                new RagChatProperties(),
                new RagProperties(),
                mock(ChatAuthorizationService.class),
                mock(ChatObservabilityService.class),
                executionService);
        principal = ChatPrincipal.local();
    }

    private ChatTurnOperation operation(ChatTurnOperation.Status status,
                                        String executionSnapshot,
                                        String errorCode,
                                        String owner) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, owner, KEY_HASH, "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                status,
                UUID.randomUUID(), now.plusSeconds(600),
                1, 0L, 1,
                executionSnapshot, "{}", errorCode, null, null,
                now, now, null);
    }

    private ChatTurnOperationService.Prepared prepared(
            ChatTurnOperation operation) {
        return new ChatTurnOperationService.Prepared(
                principal, KEY_HASH, "fp-hash",
                (com.fasterxml.jackson.databind.JsonNode) null,
                operation, true);
    }

    private ChatCommand command(String sessionId) {
        return new ChatCommand(
                "hello", sessionId, principal, null,
                ChatMode.PLAIN, MemoryMode.SERVER, null, null,
                null, null, java.util.Map.of());
    }

    private ChatTurnOperationService.Claim keyedClaim(
            ChatTurnOperation operation) {
        return new ChatTurnOperationService.Claim(operation, false);
    }

    @Test
    void commandForClaimRejectsSnapshotWithoutCandidateChain() {
        ChatTurnOperation base = operation(
                ChatTurnOperation.Status.IN_PROGRESS, null, null,
                PRINCIPAL_ID);
        String snapshot = "{\"executionSnapshotVersion\":1}";
        ChatTurnOperation withSnapshot = withExecutionSnapshot(base, snapshot);

        RagException error = assertThrows(RagException.class,
                () -> service.commandForClaim(
                        command("session-1"), keyedClaim(withSnapshot)));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
        assertEquals("Chat execution candidate chain is missing",
                error.getMessage());
    }

    @Test
    void commandForClaimRejectsNonTextualAndBlankCandidates() {
        ChatTurnOperation base = operation(
                ChatTurnOperation.Status.IN_PROGRESS, null, null,
                PRINCIPAL_ID);
        for (String candidates : new String[] {"[42]", "[\"   \"]"}) {
            String snapshot = ("{\"executionSnapshotVersion\":1,"
                    + "\"resolvedCandidates\":%s}").formatted(candidates);
            ChatTurnOperation withSnapshot =
                    withExecutionSnapshot(base, snapshot);
            RagException error = assertThrows(RagException.class,
                    () -> service.commandForClaim(
                            command("session-1"), keyedClaim(withSnapshot)),
                    "candidates=" + candidates);
            assertEquals("Chat execution candidate chain is invalid",
                    error.getMessage(), "candidates=" + candidates);
        }
    }

    @Test
    void claimNewRejectsEmptyResolvedCandidateChain() {
        when(executionService.resolveCandidateRefs(any(), anyBoolean()))
                .thenReturn(List.of());

        RagException error = assertThrows(RagException.class,
                () -> service.claim(
                        prepared(null),
                        command("session-1"),
                        ChatTurnOperation.Transport.NATIVE_JSON,
                        false));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
        assertEquals("Chat execution candidate chain is empty",
                error.getMessage());
    }

    @Test
    void failedReplayFallsBackToInternalErrorForUnknownErrorCode() {
        ChatTurnOperation failed = operation(
                ChatTurnOperation.Status.FAILED, null, "NOT_A_REAL_CODE",
                PRINCIPAL_ID);

        RagException error = assertThrows(RagException.class,
                () -> service.claim(
                        prepared(failed), "session-1",
                        ChatTurnOperation.Transport.NATIVE_JSON));
        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("previously failed"));
    }

    @Test
    void replayBuildsPrincipalForOwnerVariants() {
        for (String owner : new String[] {"db:42", "root:environment-root",
                "legacy:static", PRINCIPAL_ID}) {
            ChatTurnOperation succeeded = operation(
                    ChatTurnOperation.Status.SUCCEEDED, null, null, owner);
            when(repository.find(PRINCIPAL_ID, KEY_HASH))
                    .thenReturn(succeeded);

            // SUCCEEDED → 重放认领；后续 replay 对各主体身份构造成功。
            ChatTurnOperationService.Claim claim = service.claim(
                    prepared(null), "session-1",
                    ChatTurnOperation.Transport.NATIVE_JSON);
            assertTrue(claim.replay(), "owner=" + owner);

            ChatResponse replayed = service.replay(claim);
            org.junit.jupiter.api.Assertions.assertNotNull(replayed);
        }
    }

    private ChatTurnOperation withExecutionSnapshot(
            ChatTurnOperation base, String snapshot) {
        return new ChatTurnOperation(
                base.id(), base.ownerPrincipalId(),
                base.idempotencyKeySha256(),
                base.requestFingerprintSha256(), base.fingerprintVersion(),
                base.sessionId(), base.turnId(), base.transport(),
                base.status(), base.operationToken(), base.leaseExpiresAt(),
                base.attemptCount(), base.rowVersion(),
                base.responseVersion(), snapshot, base.responsePayload(),
                base.errorCode(), base.errorPayload(),
                base.authorizationScopeSnapshot(), base.createdAt(),
                base.updatedAt(), base.completedAt());
    }
}
