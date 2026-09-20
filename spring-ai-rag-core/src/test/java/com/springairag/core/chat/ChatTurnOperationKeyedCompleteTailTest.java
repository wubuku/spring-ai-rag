package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatTurnOperationService keyed 完成长尾（Batch 553，JaCoCo 驱
 * 动）：completeOpenAi keyed 成功路径（domainId null → 快照记录空
 * 串）、completeSuccess 未命中 → 租约丢失异常。
 */
class ChatTurnOperationKeyedCompleteTailTest {

    private ChatTurnOperationRepository repository;
    private ChatSessionCoordinator coordinator;
    private RagChatProperties properties;
    private ChatTurnOperationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
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

    private ChatTurnOperation operation() {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, "principal-1", "key-hash", "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.OPENAI_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), now.plusSeconds(60),
                1, 0L, 1,
                null, null, null, null, "{}",
                now, now, null);
    }

    private ChatResponse response(String answer) {
        return ChatResponse.builder().answer(answer).build();
    }

    @Test
    void keyedCompletePersistsSnapshotWithNullDomainId() {
        when(repository.completeSuccess(
                any(ChatTurnOperation.class), anyString(), anyString()))
                .thenReturn(true);

        ChatResponse result = service.completeOpenAi(
                new ChatTurnOperationService.Claim(operation(), true),
                response("final answer"), "PLAIN", "SERVER", "gpt-x",
                null);

        assertEquals("final answer", result.getAnswer());
        verify(repository).completeSuccess(
                any(ChatTurnOperation.class), anyString(), anyString());
    }

    @Test
    void keyedCompleteLeaseLostSurfacesPersistFailed() {
        when(repository.completeSuccess(
                any(ChatTurnOperation.class), anyString(), anyString()))
                .thenReturn(false);

        var error = assertThrows(
                com.springairag.core.exception.RagException.class,
                () -> service.completeOpenAi(
                        new ChatTurnOperationService.Claim(operation(), true),
                        response("final"), "PLAIN", "SERVER", "gpt-x",
                        "domain-1"));

        assertEquals(
                com.springairag.api.enums.ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                error.getErrorCodeEnum());
    }

    @Test
    void releaseOfNullClaimIsNoOp() {
        service.release(null);
        service.release(new ChatTurnOperationService.Claim(
                operation(), true));
    }
}
