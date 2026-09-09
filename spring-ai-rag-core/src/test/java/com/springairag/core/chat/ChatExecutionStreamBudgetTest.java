package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.when;

class ChatExecutionStreamBudgetTest {

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
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new RagProperties(),
                null,
                null);
    }

    private ChatCommand commandWithBudget(
            ChatExecutionBudget budget) {
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
                Map.of()).withExecutionBudget(budget);
    }

    private ChatModelRouter.ChatModelCandidate candidate(String ref) {
        ChatModel model = mock(ChatModel.class);
        return new ChatModelRouter.ChatModelCandidate(
                ref,
                model,
                new com.springairag.core.config.MultiModelProperties
                        .ModelCapabilities(true, false));
    }

    @SuppressWarnings("unchecked")
    private ModeAwareChatClientFactory.Attempt streamAttempt(
            ChatModelRouter.ChatModelCandidate candidate,
            Flux<ChatClientResponse> flux) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream =
                mock(ChatClient.StreamResponseSpec.class);

        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.options(any(ToolCallingChatOptions.class))).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.chatClientResponse()).thenReturn(flux);
        com.springairag.core.chat.AuthorizedRetrievalContext context =
                new com.springairag.core.chat.AuthorizedRetrievalContext(
                        RetrievalScope.unscoped(),
                        new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                        new com.springairag.core.chat.RetrievalTraceCollector(),
                        "session-1",
                        ChatPrincipal.local());
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, candidate, context, null));
        return new ModeAwareChatClientFactory.Attempt(
                client, candidate, context, null);
    }

    @Test
    void emptyCandidateStreamFallsBackToNextCandidate() {
        ChatModelRouter.ChatModelCandidate primary =
                candidate("primary");
        ChatModelRouter.ChatModelCandidate fallback =
                candidate("fallback");
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(primary, fallback));
        streamAttempt(primary, Flux.empty());
        ChatClient fallbackClient = null;
        ChatClientResponse fallbackResponse = new ChatClientResponse(
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("fallback content"))),
                        org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().build()),
                Map.of());
        streamAttempt(fallback, Flux.just(fallbackResponse));

        List<ChatEvent> events = service.stream(commandWithBudget(
                        new ChatExecutionBudget(
                                Instant.now().plusSeconds(30),
                                4, 8, 2, 4, 2, 20_000)))
                .collectList()
                .block();
        // 空完成的候选流触发回退后正常完成。
        assertNotNull(events);
    }

    @Test
    void allEmptyCandidateStreamsCompleteWithoutContentOrError() {
        ChatModelRouter.ChatModelCandidate first = candidate("first");
        ChatModelRouter.ChatModelCandidate second = candidate("second");
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(first, second));
        streamAttempt(first, Flux.empty());
        streamAttempt(second, Flux.empty());

        List<ChatEvent> events = service.stream(commandWithBudget(
                new ChatExecutionBudget(
                        Instant.now().plusSeconds(30),
                        2, 8, 2, 4, 2, 20_000)))
                .collectList()
                .block();

        assertNotNull(events);
        assertTrue(events.stream().noneMatch(event ->
                event.getClass().getSimpleName().contains("ContentDelta")));
        // 空完成不触发候选回退：第二个候选从未被创建。
        verify(clientFactory).create(any(), same(first), anyList());
        verify(clientFactory, never()).create(any(), same(second), anyList());
    }
}