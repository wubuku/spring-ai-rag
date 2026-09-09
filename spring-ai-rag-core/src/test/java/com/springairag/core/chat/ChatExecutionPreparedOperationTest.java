package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * prepareForOperation（键控回合的预执行通道）：成功路径构建
 * PreparedExecution（结果/空持久化消息/候选一致）、候选失败降级、
 * RagException 快速失败不消耗后续候选。
 */
class ChatExecutionPreparedOperationTest {

    private ChatModelRouter modelRouter;
    private ModeAwareChatClientFactory clientFactory;
    private RagChatHistoryRepository historyRepository;
    private ChatExecutionService service;

    @BeforeEach
    void setUp() {
        modelRouter = mock(ChatModelRouter.class);
        clientFactory = mock(ModeAwareChatClientFactory.class);
        KnowledgeSearchTool knowledgeSearchTool = mock(KnowledgeSearchTool.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        when(historyRepository.findBySessionId(anyString(), any(Integer.class)))
                .thenReturn(List.of());
        PromptCustomizerChain promptCustomizers =
                mock(PromptCustomizerChain.class);
        when(promptCustomizers.hasCustomizers()).thenReturn(false);
        service = new ChatExecutionService(
                modelRouter,
                clientFactory,
                knowledgeSearchTool,
                historyRepository,
                mock(DomainExtensionRegistry.class),
                promptCustomizers,
                mock(RetrievalDocumentMapper.class),
                new ObjectMapper(),
                new RagProperties(),
                null,
                null);
    }

    private ChatCommand command() {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题",
                "session-1",
                principal,
                principal.memoryConversationId("session-1"),
                ChatMode.PLAIN,
                MemoryMode.SERVER,
                null,
                null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of());
    }

    private ChatModelRouter.ChatModelCandidate candidate(String ref) {
        ChatModel model = mock(ChatModel.class);
        return new ChatModelRouter.ChatModelCandidate(
                ref,
                model,
                new com.springairag.core.config.MultiModelProperties
                        .ModelCapabilities(true, false));
    }

    /** 为指定候选桩一个成功回话的 ChatClient，并注册到工厂。 */
    @SuppressWarnings("unchecked")
    private ModeAwareChatClientFactory.Attempt stubAttempt(
            ChatModelRouter.ChatModelCandidate candidate, String answer) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call =
                mock(ChatClient.CallResponseSpec.class);
        ChatClientResponse response = mock(ChatClientResponse.class);
        ChatResponse springResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(answer))),
                ChatResponseMetadata.builder().build());

        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.options(any(ToolCallingChatOptions.class))).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.chatClientResponse()).thenReturn(response);
        when(response.chatResponse()).thenReturn(springResponse);
        when(response.context()).thenReturn(Map.of());
        com.springairag.core.chat.AuthorizedRetrievalContext context =
                new com.springairag.core.chat.AuthorizedRetrievalContext(
                        RetrievalScope.unscoped(),
                        new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                        new RetrievalTraceCollector(),
                        "session-1",
                        ChatPrincipal.local());
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, candidate, context, null));
        return new ModeAwareChatClientFactory.Attempt(
                client, candidate, context, null);
    }

    @Test
    void prepareForOperationBuildsPreparedExecution() {
        ChatModelRouter.ChatModelCandidate candidate = candidate("primary");
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        ModeAwareChatClientFactory.Attempt attempt =
                stubAttempt(candidate, "prepared answer");

        ChatExecutionService.PreparedExecution prepared =
                service.prepareForOperation(command(), null, false);

        assertEquals("prepared answer", prepared.result().answer());
        assertEquals("session-1", prepared.result().sessionId());
        // 无服务端记忆注入时持久化消息为空。
        assertEquals(0, prepared.committedMessages().size());
        assertSame(attempt.candidate(), prepared.candidate());
    }

    @Test
    void prepareForOperationFallsBackToNextCandidateOnFailure() {
        ChatModelRouter.ChatModelCandidate primary = candidate("primary");
        ChatModelRouter.ChatModelCandidate fallback = candidate("fallback");
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(primary, fallback));
        when(clientFactory.create(any(), same(primary), anyList()))
                .thenThrow(new IllegalStateException("primary unavailable"));
        stubAttempt(fallback, "fallback answer");

        ChatExecutionService.PreparedExecution prepared =
                service.prepareForOperation(command(), null, false);

        assertEquals("fallback answer", prepared.result().answer());
        verify(clientFactory, times(2)).create(any(), any(), anyList());
    }

    @Test
    void prepareForOperationRethrowsRagErrorWithoutFallback() {
        ChatModelRouter.ChatModelCandidate primary = candidate("primary");
        ChatModelRouter.ChatModelCandidate fallback = candidate("fallback");
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(primary, fallback));
        RagException ragError = new RagException(
                ErrorCode.CHAT_BUDGET_EXHAUSTED, "budget gone");
        when(clientFactory.create(any(), same(primary), anyList()))
                .thenThrow(ragError);

        RagException error = assertThrows(RagException.class,
                () -> service.prepareForOperation(command(), null, false));

        assertSame(ragError, error);
        verify(clientFactory, times(1)).create(any(), any(), anyList());
    }
}
