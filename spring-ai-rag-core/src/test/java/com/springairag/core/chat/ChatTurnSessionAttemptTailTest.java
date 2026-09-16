package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;

import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.repository.ChatTurnOperationRepository;
import com.springairag.core.service.ApiKeyManagementService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * ChatTurnOperationService 会话与尝试标记长尾（Batch 456）：
 * withEffectiveSession 对合法会话原样返回同一实例（非法会话被
 * ChatCommand 构造器拒绝，分支为纯防御）、markAttempt 的空值守
 * 卫与尝试标记委托。
 */
class ChatTurnSessionAttemptTailTest {

    private final ChatTurnOperationService service =
            new ChatTurnOperationService(
                    mock(ChatTurnOperationRepository.class),
                    new ObjectMapper(),
                    new RagChatProperties(),
                    new com.springairag.core.config.RagProperties(),
                    mock(ChatAuthorizationService.class),
                    mock(ChatObservabilityService.class),
                    mock(ChatExecutionService.class));

    private ChatCommand command(String sessionId,
                                RetrievalTraceSession traceSession) {
        return new ChatCommand(
                "问题", sessionId, ChatPrincipal.local(),
                ChatPrincipal.local().memoryConversationId("session-1"),
                ChatMode.PLAIN, MemoryMode.STATELESS, null, null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, false, 0.5, 0.5),
                java.util.Map.of(),
                List.of(), List.of(),
                traceSession, null, null);
    }

    private ChatCommand withEffectiveSession(ChatCommand command)
            throws Exception {
        Method method = ChatTurnOperationService.class.getDeclaredMethod(
                "withEffectiveSession", ChatCommand.class);
        method.setAccessible(true);
        return (ChatCommand) method.invoke(service, command);
    }

    private void markAttempt(ChatCommand command,
                             ModeAwareChatClientFactory.Attempt attempt,
                             boolean succeeded) throws Exception {
        Method method = ChatExecutionService.class.getDeclaredMethod(
                "markAttempt", ChatCommand.class,
                ModeAwareChatClientFactory.Attempt.class, boolean.class);
        method.setAccessible(true);
        method.invoke(mock(ChatExecutionService.class), command, attempt, succeeded);
    }

    @Test
    void validSessionReturnsSameCommandInstance() throws Exception {
        ChatCommand command = command("session-1", null);
        assertSame(command, withEffectiveSession(command));
    }

    @Test
    void markAttemptIgnoresNullSessionAndNullAttempt() throws Exception {
        ChatCommand noTrace = command("session-1", null);
        ModeAwareChatClientFactory.Attempt attempt =
                mock(ModeAwareChatClientFactory.Attempt.class);

        // 无 trace session 或 null attempt → 直接跳过不抛。
        markAttempt(noTrace, attempt);
        markAttempt(noTrace, null);
    }

    @Test
    void markAttemptDelegatesToTraceSessionWithCandidateRef()
            throws Exception {
        RetrievalTraceSession traceSession = mock(RetrievalTraceSession.class);
        AuthorizedRetrievalContext context =
                mock(AuthorizedRetrievalContext.class);
        RetrievalTraceCollector collector = mock(RetrievalTraceCollector.class);
        ModeAwareChatClientFactory.Attempt attempt =
                mock(ModeAwareChatClientFactory.Attempt.class);
        com.springairag.core.config.ChatModelRouter.ChatModelCandidate candidate =
                new com.springairag.core.config.ChatModelRouter.ChatModelCandidate(
                        "acme/m1", mock(org.springframework.ai.chat.model.ChatModel.class),
                        new com.springairag.core.config.MultiModelProperties
                                .ModelCapabilities(true, true));
        when(attempt.retrievalContext()).thenReturn(context);
        when(context.trace()).thenReturn(collector);
        when(collector.attemptKey()).thenReturn("attempt-1");
        when(attempt.candidate()).thenReturn(candidate);

        ChatCommand command = command("session-1", traceSession);
        markAttempt(command, attempt, true);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ref = ArgumentCaptor.forClass(String.class);
        verify(traceSession).markAttemptFinished(
                key.capture(), eq(true), ref.capture());
        assertEquals("attempt-1", key.getValue());
        assertEquals("acme/m1", ref.getValue());
    }

    private void markAttempt(ChatCommand command,
                             ModeAwareChatClientFactory.Attempt attempt) {
        try {
            markAttempt(command, attempt, true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
