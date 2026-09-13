package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatTurnOperationService 生命周期残余（Batch 378）：replay 校
 * 验与快照恢复、commandForClaim 非键控透传、complete 非键控原样、
 * fail 的跳过/协调器/仓储分支、release 的租约释放。
 */
class ChatTurnOperationServiceLifecycleTest {

    private static final String PRINCIPAL_ID = "local:auth-disabled";
    private static final String KEY_HASH =
            "b".repeat(64);

    private ChatTurnOperationRepository repository;
    private ChatSessionCoordinator sessionCoordinator;
    private ChatSessionCoordinator.LeaseHandle lease;
    private ChatTurnOperationService service;
    private ChatTurnOperationService bareService;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        var executionService = mock(ChatExecutionService.class);
        Mockito.lenient().when(executionService.resolveCandidateRefs(
                any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(List.of("vendor/model"));
        service = new ChatTurnOperationService(
                repository,
                new ObjectMapper(),
                new RagChatProperties(),
                new RagProperties(),
                mock(ChatAuthorizationService.class),
                mock(ChatObservabilityService.class),
                executionService);
        sessionCoordinator = mock(ChatSessionCoordinator.class);
        lease = ChatSessionCoordinator.LeaseHandle.stateless(Instant.now().plusSeconds(60));
        service.setSessionCoordinator(sessionCoordinator);
        bareService = new ChatTurnOperationService(
                repository,
                new ObjectMapper(),
                new RagChatProperties(),
                new RagProperties(),
                mock(ChatAuthorizationService.class),
                mock(ChatObservabilityService.class),
                executionService);
    }

    private ChatTurnOperation operation(
            ChatTurnOperation.Status status, String responsePayload) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, PRINCIPAL_ID, KEY_HASH, "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                status, UUID.randomUUID(), now.plusSeconds(60),
                1, 0L, 1,
                null, responsePayload, null, null, null,
                now, now, null);
    }

    private ChatTurnOperationService.Claim keyedClaim(
            ChatTurnOperation operation) {
        return new ChatTurnOperationService.Claim(operation, false);
    }

    private ChatCommand command() {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "hello", "session-1", principal, null,
                ChatMode.KNOWLEDGE, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void replayRejectsNullAndNonReplayClaims() {
        assertThrows(IllegalArgumentException.class,
                () -> service.replay(null));
        assertThrows(IllegalArgumentException.class,
                () -> service.replay(keyedClaim(
                        operation(ChatTurnOperation.Status.SUCCEEDED, "{}"))));
    }

    @Test
    void replayRestoresStoredResponseWithTurnId() throws Exception {
        com.springairag.api.dto.ChatResponse stored =
                new com.springairag.api.dto.ChatResponse();
        stored.setAnswer("cached");
        String payload = new ObjectMapper().writeValueAsString(stored);
        ChatTurnOperation operation =
                operation(ChatTurnOperation.Status.SUCCEEDED, payload);
        UUID turnId = operation.turnId();
        var claim = new ChatTurnOperationService.Claim(operation, true);

        com.springairag.api.dto.ChatResponse response = service.replay(claim);

        assertNotNull(response);
        assertEquals(turnId.toString(), response.getTurnId());
    }

    @Test
    void replayThrowsInternalErrorOnInvalidSnapshot() {
        ChatTurnOperation operation =
                operation(ChatTurnOperation.Status.SUCCEEDED, "{broken");
        var claim = new ChatTurnOperationService.Claim(operation, true);

        RagException error = assertThrows(RagException.class,
                () -> service.replay(claim));
        assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCodeEnum());
    }

    @Test
    void commandForClaimPassesThroughWhenNotKeyed() {
        ChatCommand original = command();

        assertSame(original, service.commandForClaim(original, null));
        assertSame(original, service.commandForClaim(
                original, new ChatTurnOperationService.Claim(null, false)));
    }

    @Test
    void completeNonKeyedClaimReturnsResponseAsIs() {
        com.springairag.api.dto.ChatResponse response =
                new com.springairag.api.dto.ChatResponse();
        response.setAnswer("ok");

        assertSame(response, service.completeOpenAi(
                null, response, "CHAT", "SERVER", null, null));
    }

    @Test
    void failSkipsNullNonKeyedOrTerminalClaims() {
        service.fail(null, new RuntimeException("x"));
        service.fail(new ChatTurnOperationService.Claim(
                operation(ChatTurnOperation.Status.SUCCEEDED, "{}"), false),
                new RuntimeException("x"));
        var terminal = new ChatTurnOperationService.Claim(
                operation(ChatTurnOperation.Status.SUCCEEDED, "{}"), true);
        service.fail(terminal, new RuntimeException("x"));

        verify(repository, never()).completeFailure(any(), anyString(), anyString());
        verify(sessionCoordinator, never()).failOperation(
                any(), any(), anyString(), anyString());
    }

    private ChatTurnOperationService.Claim claimWithLease(
            ChatTurnOperation operation) throws Exception {
        var ctor = ChatTurnOperationService.Claim.class
                .getDeclaredConstructor(
                        ChatTurnOperation.class, boolean.class,
                        ChatSessionCoordinator.LeaseHandle.class);
        ctor.setAccessible(true);
        return (ChatTurnOperationService.Claim)
                ctor.newInstance(operation, true, lease);
    }

    @Test
    void failInProgressWithLeaseReportsToCoordinator() throws Exception {
        ChatTurnOperation operation =
                operation(ChatTurnOperation.Status.IN_PROGRESS, "{}");
        var claim = claimWithLease(operation);

        service.fail(claim, new RagException(
                ErrorCode.INTERNAL_ERROR, "boom"));

        verify(sessionCoordinator).failOperation(
                eq(lease), eq(operation), anyString(), anyString());
        verify(repository, never()).completeFailure(any(), anyString(), anyString());
    }

    @Test
    void failInProgressWithoutCoordinatorWritesFailureToRepository() {
        ChatTurnOperation operation =
                operation(ChatTurnOperation.Status.IN_PROGRESS, "{}");
        var claim = new ChatTurnOperationService.Claim(operation, true);

        bareService.fail(claim, new RuntimeException("boom"));

        verify(repository).completeFailure(
                eq(operation), eq("INTERNAL_ERROR"), anyString());
    }

    @Test
    void releaseToleratesNullAndReleasesLease() throws Exception {
        bareService.release(null);

        var claim = claimWithLease(
                operation(ChatTurnOperation.Status.IN_PROGRESS, "{}"));
        service.release(claim);
        verify(sessionCoordinator).release(lease);
    }
}
