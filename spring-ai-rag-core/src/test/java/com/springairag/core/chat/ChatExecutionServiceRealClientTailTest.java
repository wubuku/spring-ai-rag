package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatExecutionService 真实客户端链长尾（Batch 640，JaCoCo 驱
 * 动）：真实 ChatClient 包装 mock 模型时 advisor 消费者真实生效
 * （invoke/invokeStream 的上下文与记忆参数装配）、带工具调用的记
 * 忆投影挂载工具转写元数据。
 */
class ChatExecutionServiceRealClientTailTest {

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

    private ChatModelRouter.ChatModelCandidate candidate(String ref) {
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(null);
        return new ChatModelRouter.ChatModelCandidate(
                ref, model,
                new com.springairag.core.config.MultiModelProperties
                        .ModelCapabilities(true, false));
    }

    private AuthorizedRetrievalContext context() {
        return new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                new RetrievalTraceCollector(),
                "session-1",
                ChatPrincipal.local());
    }

    private void stubCandidate(
            ChatModelRouter.ChatModelCandidate candidate, ChatClient client) {
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        when(modelRouter.resolve(candidate.ref())).thenReturn(candidate.model());
        when(clientFactory.create(any(), any(), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, candidate, context(), null));
    }

    @Test
    void realClientExecuteAnswersThroughAdvisorChain() {
        ChatModelRouter.ChatModelCandidate candidate = candidate("real-1");
        ChatModel model = candidate.model();
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("真实阻塞回答"))),
                        ChatResponseMetadata.builder().build()));
        stubCandidate(candidate, ChatClient.builder(model).build());

        ChatExecutionResult result = service.execute(baseCommand(ChatMode.PLAIN));

        assertEquals("真实阻塞回答", result.answer());
    }

    @Test
    void realClientStreamAppliesAdvisorsAndEmitsContent() {
        ChatModelRouter.ChatModelCandidate candidate = candidate("real-2");
        ChatModel model = candidate.model();
        when(model.stream(any(Prompt.class)))
                .thenReturn(Flux.just(
                        new ChatResponse(List.of(new Generation(
                                new AssistantMessage("真实")))),
                        new ChatResponse(List.of(new Generation(
                                new AssistantMessage("流式回答")))),
                        new ChatResponse(List.of(),
                                ChatResponseMetadata.builder().build())));
        stubCandidate(candidate, ChatClient.builder(model).build());

        List<ChatEvent> events = service.stream(baseCommand(ChatMode.PLAIN))
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        String content = events.stream()
                .filter(event -> event instanceof ChatEvent.ContentDelta)
                .map(event -> ((ChatEvent.ContentDelta) event).content())
                .reduce("", String::concat);
        assertTrue(content.contains("真实流式回答"), () -> "实际内容: " + content);
        assertTrue(events.stream().anyMatch(
                event -> event instanceof ChatEvent.Completed));
    }

    @Test
    void executeWithToolCallMemoryAttachesToolTranscript() {
        ChatModelRouter.ChatModelCandidate candidate = candidate("real-3");
        ChatModel model = candidate.model();
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("工具回合回答"))),
                        ChatResponseMetadata.builder().build()));

        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", "knowledge_search", "{\"q\":\"问题\"}");
        AssistantMessage withToolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        ToolResponseMessage toolResult = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "knowledge_search", "工具结果内容")))
                .build();
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get(anyString()))
                .thenReturn(List.of(withToolCall, toolResult));

        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        when(modelRouter.resolve(candidate.ref())).thenReturn(candidate.model());
        ChatClient realClient = ChatClient.builder(model).build();
        when(clientFactory.create(any(), any(), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        realClient, candidate, context(), memory));

        ChatExecutionResult result = service.execute(baseCommand(ChatMode.PLAIN));

        assertEquals("工具回合回答", result.answer());
        assertNotNull(result.metadata().get(
                ChatMemoryMessageProjector.TOOL_TRANSCRIPT_METADATA_KEY));
    }
}
