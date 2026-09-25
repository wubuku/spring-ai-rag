package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatExecutionService 流式记忆与回退长尾（Batch 639，JaCoCo 驱
 * 动）：首候选无消息异常的 unknown-error 回退、流式完成路径的
 * Attempt.memory 投影（committedMessages）、执行路径 withPersist
 * enceMetadata 的记忆投影、双候选回退成功。
 */
class ChatExecutionServiceStreamMemoryTailTest {

    private ChatModelRouter modelRouter;
    private ModeAwareChatClientFactory clientFactory;
    private ChatSessionCoordinator sessionCoordinator;
    private ConversationSummaryService summaryService;
    private RagProperties ragProperties;
    private ChatExecutionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelRouter = mock(ChatModelRouter.class);
        clientFactory = mock(ModeAwareChatClientFactory.class);
        sessionCoordinator = mock(ChatSessionCoordinator.class);
        when(sessionCoordinator.invokeWithinDeadline(
                any(ChatSessionCoordinator.LeaseHandle.class), any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>)
                        invocation.getArgument(1)).get());
        when(sessionCoordinator.acquire(any(ChatCommand.class), anyBoolean()))
                .thenReturn(ChatSessionCoordinator.LeaseHandle.stateless(
                        Instant.now().plusSeconds(60)));
        summaryService = mock(ConversationSummaryService.class);
        ragProperties = new RagProperties();

        var historyRepository = mock(RagChatHistoryRepository.class);
        when(historyRepository.findBySessionId(anyString(), any(Integer.class)))
                .thenReturn(List.of());
        when(historyRepository.findOwnedBaseline(any(), anyString(), anyInt()))
                .thenReturn(List.of());

        service = new ChatExecutionService(
                modelRouter,
                clientFactory,
                mock(KnowledgeSearchTool.class),
                historyRepository,
                null,
                mock(DomainExtensionRegistry.class),
                mock(PromptCustomizerChain.class),
                mock(RetrievalDocumentMapper.class),
                new ObjectMapper(),
                ragProperties,
                null,
                null,
                sessionCoordinator,
                null);
        service.setSummaryService(summaryService);
    }

    private ChatCommand baseCommand(ChatMode mode) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                mode, MemoryMode.SERVER, null, null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of());
    }

    private ChatModelRouter.ChatModelCandidate candidate(
            String ref, boolean streaming) {
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(null);
        return new ChatModelRouter.ChatModelCandidate(
                ref, model,
                new com.springairag.core.config.MultiModelProperties
                        .ModelCapabilities(streaming, false));
    }

    private AuthorizedRetrievalContext context() {
        return new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                new RetrievalTraceCollector(),
                "session-1",
                ChatPrincipal.local());
    }

    private ChatClientResponse response(String content) {
        return new ChatClientResponse(
                new ChatResponse(
                        List.of(new Generation(new AssistantMessage(content))),
                        ChatResponseMetadata.builder().build()),
                Map.of());
    }

    private void stubCandidates(
            List<ChatModelRouter.ChatModelCandidate> candidates) {
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(candidates);
        for (ChatModelRouter.ChatModelCandidate candidate : candidates) {
            when(modelRouter.resolve(eq(candidate.ref()))).thenReturn(candidate.model());
        }
    }

    @SuppressWarnings("unchecked")
    private ChatClient streamingClient(Flux<ChatClientResponse> responses) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream =
                mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class)))
                .thenReturn(spec);
        when(spec.toolCallbacks(any(KnowledgeSearchTool.class)))
                .thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.chatClientResponse()).thenReturn(responses);
        return client;
    }

    @SuppressWarnings("unchecked")
    private ChatClient blockingClient(String content) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call =
                mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class)))
                .thenReturn(spec);
        when(spec.toolCallbacks(any(KnowledgeSearchTool.class)))
                .thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.chatClientResponse()).thenReturn(response(content));
        return client;
    }

    private void stubStreamCandidate(
            ChatModelRouter.ChatModelCandidate candidate, ChatClient client,
            ChatMemory memory) {
        stubCandidates(List.of(candidate));
        when(clientFactory.create(any(), any(), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, candidate, context(), memory));
    }

    @Test
    void streamFallbackRecoversFromMessagelessError() {
        ChatModelRouter.ChatModelCandidate broken = candidate("broken", true);
        ChatModelRouter.ChatModelCandidate healthy = candidate("healthy", true);
        ModeAwareChatClientFactory.Attempt brokenAttempt =
                new ModeAwareChatClientFactory.Attempt(
                        streamingClient(Flux.error(
                                new IllegalStateException())),
                        broken, context(), null);
        ModeAwareChatClientFactory.Attempt healthyAttempt =
                new ModeAwareChatClientFactory.Attempt(
                        streamingClient(Flux.just(response("无消息回退回答"))),
                        healthy, context(), null);
        stubCandidates(List.of(broken, healthy));
        when(clientFactory.create(any(), any(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1) == broken
                        ? brokenAttempt : healthyAttempt);

        List<ChatEvent> events = service.stream(baseCommand(ChatMode.PLAIN))
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        assertTrue(events.stream().anyMatch(event ->
                event instanceof ChatEvent.ContentDelta delta
                        && delta.content().contains("无消息回退回答")));
    }

    @Test
    void streamWithMemoryProjectsCommittedMessages() {
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get(anyString()))
                .thenReturn(List.of(new UserMessage("流式历史消息")));
        stubStreamCandidate(candidate("solo", true),
                streamingClient(Flux.just(response("流式带记忆回答"))), memory);

        List<ChatEvent> events = service.stream(baseCommand(ChatMode.PLAIN))
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        assertTrue(events.stream().anyMatch(
                event -> event instanceof ChatEvent.Completed));
        verify(memory, org.mockito.Mockito.atLeastOnce()).get(anyString());
    }

    @Test
    void executeWithMemoryProjectsPersistenceMetadata() {
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get(anyString()))
                .thenReturn(List.of(new UserMessage("执行历史消息")));
        stubStreamCandidate(candidate("solo", true),
                blockingClient("执行带记忆回答"), memory);

        ChatExecutionResult result = service.execute(baseCommand(ChatMode.PLAIN));

        assertEquals("执行带记忆回答", result.answer());
        verify(memory, org.mockito.Mockito.atLeastOnce()).get(anyString());
    }

}
