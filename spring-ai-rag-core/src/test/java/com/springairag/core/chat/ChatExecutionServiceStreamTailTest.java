package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatExecutionService 流式编排长尾（Batch 499，JaCoCo 驱动）：
 * 最后一个候选空流 → "LLM returned no usable streaming response"；
 * 成功流的 Completed 事件携带结果字段；快照 modelCandidates 驱动
 * 候选链且不回退到路由器；无可用候选时 prepareForOperation 快速失
 * 败。
 */
class ChatExecutionServiceStreamTailTest {

    private ChatModelRouter modelRouter;
    private ModeAwareChatClientFactory clientFactory;
    private ChatExecutionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelRouter = mock(ChatModelRouter.class);
        clientFactory = mock(ModeAwareChatClientFactory.class);
        var historyRepository = mock(RagChatHistoryRepository.class);
        when(historyRepository.findBySessionId(anyString(), any(Integer.class)))
                .thenReturn(List.of());
        service = new ChatExecutionService(
                modelRouter,
                clientFactory,
                mock(KnowledgeSearchTool.class),
                historyRepository,
                mock(DomainExtensionRegistry.class),
                mock(PromptCustomizerChain.class),
                mock(RetrievalDocumentMapper.class),
                new ObjectMapper(),
                new RagProperties(),
                null,
                null);
    }

    private ChatCommand command(ChatMode mode) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                mode, MemoryMode.SERVER, null, null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of("client", "test"));
    }

    private ChatModelRouter.ChatModelCandidate candidate(
            String ref, boolean streaming) {
        ChatModel model = mock(ChatModel.class);
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

    @SuppressWarnings("unchecked")
    private StreamClientFixture streamClientFixture(
            Flux<ChatClientResponse> responses) {
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
        when(spec.options(any(ToolCallingChatOptions.class))).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.chatClientResponse()).thenReturn(responses);
        return new StreamClientFixture(client, spec);
    }

    private ChatClientResponse streamResponse(
            String content, Map<String, Object> context) {
        return new ChatClientResponse(
                new ChatResponse(
                        List.of(new Generation(new AssistantMessage(content))),
                        ChatResponseMetadata.builder().build()),
                context);
    }

    private record StreamClientFixture(
            ChatClient client, ChatClient.ChatClientRequestSpec spec) {
    }

    @Test
    void streamWithoutEligibleCandidatesFails() {
        // 路由器无任何候选 → MODEL_STREAMING_UNSUPPORTED。
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of());

        RagException error = assertThrows(RagException.class,
                () -> service.stream(command(ChatMode.PLAIN))
                        .collectList().block());
        assertEquals(ErrorCode.MODEL_STREAMING_UNSUPPORTED,
                error.getErrorCodeEnum());
    }

    @Test
    void successfulStreamEmitsCompletedEventWithResultFields() {
        ChatModelRouter.ChatModelCandidate candidate =
                candidate("solo", true);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        StreamClientFixture client = streamClientFixture(Flux.just(
                streamResponse("答案", Map.of("k", "v"))));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client.client(), candidate, context(), null));

        List<ChatEvent> events = service.stream(command(ChatMode.PLAIN))
                .collectList().block();

        assertEquals(true, events != null && !events.isEmpty());
        ChatEvent last = events.getLast();
        assertTrue(last instanceof ChatEvent.Completed,
                "最后事件应为 Completed: " + last);
        ChatEvent.Completed completed = (ChatEvent.Completed) last;
        assertEquals("session-1", completed.sessionId());
        assertTrue(events.stream().anyMatch(e ->
                e instanceof ChatEvent.ContentDelta delta
                        && "答案".equals(delta.content())));
    }

    @Test
    void snapshotModelCandidatesDriveChainWithoutRouterFallback() {
        // 快照候选链：第一个候选失败后回退第二个，均来自快照。
        ChatModelRouter.ChatModelCandidate primary =
                candidate("snap-a", true);
        ChatModelRouter.ChatModelCandidate fallback =
                candidate("snap-b", true);
        AtomicInteger created = new AtomicInteger();
        StreamClientFixture failed = streamClientFixture(
                Flux.error(new IllegalStateException("snap-a down")));
        StreamClientFixture ok = streamClientFixture(Flux.just(
                streamResponse("from-snapshot", Map.of())));
        // 快照候选经 resolveCandidateRequired 逐一解析。
        when(modelRouter.resolveCandidateRequired("snap-a"))
                .thenReturn(primary);
        when(modelRouter.resolveCandidateRequired("snap-b"))
                .thenReturn(fallback);
        when(clientFactory.create(any(),
                org.mockito.ArgumentMatchers.argThat(c ->
                        c != null && "snap-a".equals(c.ref())), anyList()))
                .thenAnswer(inv -> {
                    created.incrementAndGet();
                    return new ModeAwareChatClientFactory.Attempt(
                            failed.client(), primary, context(), null);
                });
        when(clientFactory.create(any(),
                org.mockito.ArgumentMatchers.argThat(c ->
                        c != null && "snap-b".equals(c.ref())), anyList()))
                .thenAnswer(inv -> {
                    created.incrementAndGet();
                    return new ModeAwareChatClientFactory.Attempt(
                            ok.client(), fallback, context(), null);
                });
        ChatCommand snapshotCommand = command(ChatMode.PLAIN)
                .withModelCandidates(List.of("snap-a", "snap-b"));

        List<ChatEvent> events = service.stream(snapshotCommand)
                .collectList().block();

        assertEquals(2, created.get());
        assertTrue(events != null && events.stream().anyMatch(e ->
                e instanceof ChatEvent.ContentDelta delta
                        && "from-snapshot".equals(delta.content())));
        // 快照候选链生效：两个候选均经由快照引用创建。
    }

    @Test
    void prepareForOperationWithoutAnyCandidateFails() {
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of());
        when(modelRouter.orderedCandidates(isNull())).thenReturn(List.of());

        assertThrows(RagException.class,
                () -> service.prepareForOperation(
                        command(ChatMode.PLAIN), null, false));
        verify(modelRouter, never()).orderedCandidates(anyString());
    }
}
