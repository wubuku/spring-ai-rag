package com.springairag.core.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.ChatTurnInProgressException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTurnOperationServiceTest {

    private final ChatTurnOperationRepository repository =
            mock(ChatTurnOperationRepository.class);
    private final ChatAuthorizationService authorizationService =
            mock(ChatAuthorizationService.class);
    private final ChatObservabilityService observability =
            mock(ChatObservabilityService.class);
    private final ChatExecutionService executionService =
            mock(ChatExecutionService.class);
    private final ChatSessionCoordinator sessionCoordinator =
            mock(ChatSessionCoordinator.class);
    private final ChatTurnOperationService service;

    ChatTurnOperationServiceTest() {
        service = new ChatTurnOperationService(
                repository,
                new ObjectMapper(),
                new RagChatProperties(),
                new RagProperties(),
                authorizationService,
                observability,
                executionService);
        service.setSessionCoordinator(sessionCoordinator);
    }

    @AfterEach
    void stopRenewalExecutor() {
        service.shutdown();
    }

    @Test
    void missingIdempotencyKeyDoesNotRequireFingerprint() {
        ChatTurnOperationService.Prepared prepared = service.prepare(
                ChatPrincipal.local(), List.of(), null);

        org.junit.jupiter.api.Assertions.assertFalse(prepared.keyed());
        assertEquals(null, prepared.fingerprintHash());
    }

    @Test
    void inspectExistingCountsInProgressOperation() {
        ChatPrincipal principal = ChatPrincipal.local();
        ChatTurnOperationService.Prepared prepared = prepared(principal);
        ChatTurnOperation operation = operation(
                principal.id(),
                prepared.keyHash(),
                prepared.fingerprintHash(),
                "session-1",
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(),
                Instant.now().plusSeconds(30),
                "{\"executionSnapshotVersion\":1,\"resolvedCandidates\":[\"provider/model\"]}");

        ChatTurnInProgressException error = assertThrows(
                ChatTurnInProgressException.class,
                () -> service.inspectExisting(prepared.withOperation(operation)));

        assertEquals(
                ErrorCode.IDEMPOTENCY_OPERATION_IN_PROGRESS,
                error.getErrorCodeEnum());
        verify(observability).inProgress();
    }

    @Test
    void commandForClaimRejectsEmptyImmutableCandidateSnapshot() {
        ChatPrincipal principal = ChatPrincipal.local();
        ChatCommand command = command(principal);
        ChatTurnOperation operation = operation(
                principal.id(),
                "key-hash",
                "fingerprint-hash",
                command.sessionId(),
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(),
                Instant.now().plusSeconds(60),
                "{\"executionSnapshotVersion\":1,\"resolvedCandidates\":[]}");

        RagException error = assertThrows(
                RagException.class,
                () -> service.commandForClaim(
                        command,
                        new ChatTurnOperationService.Claim(operation, false)));

        assertEquals(
                ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void exhaustedStaleOperationUsesExpiredFailureCas() {
        ChatPrincipal principal = ChatPrincipal.local();
        ChatCommand command = command(principal);
        ChatTurnOperationService.Prepared prepared = prepared(principal);
        ChatTurnOperation exhausted = operation(
                principal.id(),
                prepared.keyHash(),
                prepared.fingerprintHash(),
                command.sessionId(),
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(),
                Instant.now().minusSeconds(1),
                "{\"executionSnapshotVersion\":1}",
                3);
        ChatTurnOperation failed = failedOperation(exhausted);
        when(repository.find(principal.id(), prepared.keyHash()))
                .thenReturn(exhausted, failed);
        when(sessionCoordinator.acquire(command, false))
                .thenReturn(ChatSessionCoordinator.LeaseHandle.stateless(
                        Instant.now().plusSeconds(60)));

        RagException error = assertThrows(
                RagException.class,
                () -> service.claim(
                        prepared,
                        command,
                        ChatTurnOperation.Transport.NATIVE_JSON,
                        false));

        assertEquals(
                ErrorCode.IDEMPOTENCY_ATTEMPTS_EXHAUSTED,
                error.getErrorCodeEnum());
        verify(sessionCoordinator).failExpiredOperation(
                any(),
                same(exhausted),
                eq(ErrorCode.IDEMPOTENCY_ATTEMPTS_EXHAUSTED.getCode()),
                anyString());
    }

    @Test
    void firstClaimPersistsResolvedCandidateChainInsteadOfClientCandidates()
            throws Exception {
        ChatPrincipal principal = ChatPrincipal.local();
        ChatCommand command = command(principal);
        ChatTurnOperationService.Prepared prepared = prepared(principal);
        when(executionService.resolveCandidateRefs(command, false))
                .thenReturn(List.of("provider/primary", "provider/fallback"));
        when(authorizationService.initialSnapshot(any()))
                .thenReturn("{}");
        when(sessionCoordinator.acquire(command, false))
                .thenReturn(ChatSessionCoordinator.LeaseHandle.stateless(
                        Instant.now().plusSeconds(60)));
        when(repository.insert(
                any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any()))
                .thenReturn(true);
        ChatTurnOperation inserted = operation(
                principal.id(),
                prepared.keyHash(),
                prepared.fingerprintHash(),
                command.sessionId(),
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(),
                Instant.now().plusSeconds(60),
                "{\"executionSnapshotVersion\":1}");
        when(repository.find(principal.id(), prepared.keyHash()))
                .thenReturn(null, inserted);

        service.claim(
                prepared,
                command,
                ChatTurnOperation.Transport.NATIVE_JSON,
                false);

        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> lease = ArgumentCaptor.forClass(Integer.class);
        verify(repository).insert(
                eq(principal.id()),
                eq(prepared.keyHash()),
                eq(prepared.fingerprintHash()),
                eq(command.sessionId()),
                any(),
                eq(ChatTurnOperation.Transport.NATIVE_JSON),
                any(),
                lease.capture(),
                snapshot.capture(),
                any());
        assertEquals(130_000, lease.getValue());
        JsonNode resolved = new ObjectMapper().readTree(snapshot.getValue())
                .path("resolvedCandidates");
        assertEquals(
                List.of("provider/primary", "provider/fallback"),
                new ObjectMapper().convertValue(resolved, List.class));
    }

    @Test
    void streamingClaimUsesStreamingDeadlineForInitialOperationLease()
            throws Exception {
        ChatPrincipal principal = ChatPrincipal.local();
        ChatCommand command = command(principal);
        ChatTurnOperationService.Prepared prepared = prepared(principal);
        when(executionService.resolveCandidateRefs(command, true))
                .thenReturn(List.of("provider/primary"));
        when(authorizationService.initialSnapshot(any()))
                .thenReturn("{}");
        when(sessionCoordinator.acquire(command, true))
                .thenReturn(ChatSessionCoordinator.LeaseHandle.stateless(
                        Instant.now().plusSeconds(300)));
        when(repository.insert(
                any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any()))
                .thenReturn(true);
        when(repository.find(principal.id(), prepared.keyHash()))
                .thenReturn(null, operation(
                        principal.id(),
                        prepared.keyHash(),
                        prepared.fingerprintHash(),
                        command.sessionId(),
                        ChatTurnOperation.Status.IN_PROGRESS,
                        UUID.randomUUID(),
                        Instant.now().plusSeconds(300),
                        "{\"executionSnapshotVersion\":1,"
                                + "\"resolvedCandidates\":[\"provider/primary\"]}"));

        service.claim(
                prepared,
                command,
                ChatTurnOperation.Transport.NATIVE_SSE,
                true);

        ArgumentCaptor<Integer> lease = ArgumentCaptor.forClass(Integer.class);
        verify(repository).insert(
                eq(principal.id()),
                eq(prepared.keyHash()),
                eq(prepared.fingerprintHash()),
                eq(command.sessionId()),
                any(),
                eq(ChatTurnOperation.Transport.NATIVE_SSE),
                any(),
                lease.capture(),
                any(),
                any());
        assertEquals(190_000, lease.getValue());
    }

    @Test
    void executionCommandUsesCandidateChainFromImmutableOperationSnapshot()
            throws Exception {
        ChatPrincipal principal = ChatPrincipal.local();
        ChatCommand command = command(principal);
        ChatTurnOperation operation = operation(
                principal.id(),
                "key-hash",
                "fingerprint-hash",
                command.sessionId(),
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(),
                Instant.now().plusSeconds(60),
                """
                {
                  "executionSnapshotVersion": 1,
                  "resolvedCandidates": ["provider/first", "provider/fallback"]
                }
                """);

        ChatCommand effective = service.commandForClaim(
                command,
                new ChatTurnOperationService.Claim(operation, false));

        assertEquals(
                List.of("provider/first", "provider/fallback"),
                effective.modelCandidates());
    }

    @Test
    void completePreparedReturnsTheSameControlledSnapshotThatIsPersisted()
            throws Exception {
        ChatPrincipal principal = ChatPrincipal.local();
        ChatCommand command = command(principal);
        ChatTurnOperation operation = operation(
                principal.id(),
                "key-hash",
                "fingerprint-hash",
                command.sessionId(),
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(),
                Instant.now().plusSeconds(60),
                "{\"executionSnapshotVersion\":1,\"resolvedCandidates\":[\"provider/first\"]}");
        ChatTurnOperationService.Prepared claimPrepared = prepared(principal);
        when(executionService.resolveCandidateRefs(eq(command), eq(false)))
                .thenReturn(List.of("provider/first"));
        when(sessionCoordinator.acquire(eq(command), eq(false)))
                .thenReturn(ChatSessionCoordinator.LeaseHandle.stateless(
                        Instant.now().plusSeconds(60)));
        when(authorizationService.initialSnapshot(eq(command)))
                .thenReturn("{}");
        when(repository.find(principal.id(), "key-hash"))
                .thenReturn(null, operation);
        when(repository.insert(
                any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any()))
                .thenReturn(true);
        ChatTurnOperationService.Claim claim = service.claim(
                claimPrepared,
                command,
                ChatTurnOperation.Transport.NATIVE_JSON,
                false);
        ChatExecutionResult result = new ChatExecutionResult(
                "answer",
                command.sessionId(),
                "request-trace",
                "public-model",
                "provider/first",
                ChatMode.PLAIN,
                List.of(),
                Map.of("totalTokens", 3),
                "STOP",
                List.of(),
                Map.of(
                        "safe", "value",
                        "traceId", "must-not-persist",
                        "requestSecret", "must-not-persist"));
        ChatExecutionService.PreparedExecution prepared =
                new ChatExecutionService.PreparedExecution(
                        command,
                        result,
                        List.of(),
                        "",
                        null,
                        null);
        when(authorizationService.snapshot(eq(command), any()))
                .thenReturn("""
                        {"authorizationSnapshotVersion":1,
                         "scopeMode":"NOT_APPLICABLE",
                         "callerAccessMode":"NOT_APPLICABLE",
                         "effectiveSelectedCollectionIds":[],
                         "callerAllowList":[],
                         "unassignedDocumentsAllowed":false,
                         "sourceDocumentCollectionSnapshot":[],
                         "sourceCollectionIdsObserved":[]}
                        """.replaceAll("\\s+", ""));

        ChatResponse returned = service.completePrepared(claim, prepared);

        assertEquals(null, returned.getTraceId());
        assertTrue(!returned.getMetadata().containsKey("safe"));
        assertEquals(operation.turnId().toString(),
                returned.getMetadata().get("turnId"));
        org.mockito.ArgumentCaptor<String> payload =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sessionCoordinator).commitOperation(
                any(),
                same(operation),
                same(command),
                same(result),
                any(),
                anyString(),
                anyString(),
                payload.capture(),
                anyString());
        JsonNode persisted = new ObjectMapper().readTree(payload.getValue());
        assertTrue(!persisted.has("traceId"));
        assertTrue(!persisted.path("metadata").has("safe"));
        assertTrue(!persisted.path("metadata").has("requestSecret"));
    }

    @Test
    void sessionBusyRechecksSameKeyBeforeReturningOrdinarySessionBusy() {
        ChatPrincipal principal = ChatPrincipal.local();
        ChatCommand command = command(principal);
        ChatTurnOperationService.Prepared prepared = prepared(principal);
        ChatTurnOperation raced = operation(
                principal.id(),
                prepared.keyHash(),
                prepared.fingerprintHash(),
                command.sessionId(),
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(),
                Instant.now().plusSeconds(30),
                "{\"executionSnapshotVersion\":1}");
        when(executionService.resolveCandidateRefs(command, false))
                .thenReturn(List.of("provider/primary"));
        when(repository.find(principal.id(), prepared.keyHash()))
                .thenReturn(null, raced);
        doThrow(new RagException(
                ErrorCode.SESSION_BUSY,
                "Chat session already has an active request"))
                .when(sessionCoordinator).acquire(command, false);

        ChatTurnInProgressException error = assertThrows(
                ChatTurnInProgressException.class,
                () -> service.claim(
                        prepared,
                        command,
                        ChatTurnOperation.Transport.NATIVE_JSON,
                        false));

        assertEquals(
                ErrorCode.IDEMPOTENCY_OPERATION_IN_PROGRESS,
                error.getErrorCodeEnum());
        verify(repository, times(2)).find(principal.id(), prepared.keyHash());
    }

    private ChatTurnOperationService.Prepared prepared(
            ChatPrincipal principal) {
        try {
            JsonNode canonical = new ObjectMapper().readTree("""
                    {"declaredModelIdentifier":"DEFAULT"}
                    """);
            return new ChatTurnOperationService.Prepared(
                    principal,
                    "key-hash",
                    "fingerprint-hash",
                    canonical,
                    null,
                    true);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private ChatCommand command(ChatPrincipal principal) {
        return new ChatCommand(
                "question",
                "session-1",
                principal,
                principal.memoryConversationId("session-1"),
                ChatMode.PLAIN,
                MemoryMode.SERVER,
                null,
                null,
                com.springairag.core.retrieval.RetrievalScope.noMatches(),
                new RetrievalOptions(1, 0, false, false, 0, 0),
                Map.of());
    }

    private ChatTurnOperation operation(
            String owner,
            String keyHash,
            String fingerprintHash,
            String sessionId,
            ChatTurnOperation.Status status,
            UUID token,
            Instant leaseExpiresAt,
            String snapshot) {
        return operation(
                owner,
                keyHash,
                fingerprintHash,
                sessionId,
                status,
                token,
                leaseExpiresAt,
                snapshot,
                1);
    }

    private ChatTurnOperation operation(
            String owner,
            String keyHash,
            String fingerprintHash,
            String sessionId,
            ChatTurnOperation.Status status,
            UUID token,
            Instant leaseExpiresAt,
            String snapshot,
            int attemptCount) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L,
                owner,
                keyHash,
                fingerprintHash,
                1,
                sessionId,
                UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                status,
                token,
                leaseExpiresAt,
                attemptCount,
                0L,
                1,
                snapshot,
                null,
                null,
                null,
                "{}",
                now,
                now,
                null);
    }

    private ChatTurnOperation failedOperation(ChatTurnOperation source) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                source.id(),
                source.ownerPrincipalId(),
                source.idempotencyKeySha256(),
                source.requestFingerprintSha256(),
                source.fingerprintVersion(),
                source.sessionId(),
                source.turnId(),
                source.transport(),
                ChatTurnOperation.Status.FAILED,
                null,
                null,
                source.attemptCount(),
                source.rowVersion() + 1,
                source.responseVersion(),
                source.executionSnapshot(),
                null,
                ErrorCode.IDEMPOTENCY_ATTEMPTS_EXHAUSTED.getCode(),
                "{\"errorSnapshotVersion\":1}",
                source.authorizationScopeSnapshot(),
                source.createdAt(),
                now,
                now);
    }
}
