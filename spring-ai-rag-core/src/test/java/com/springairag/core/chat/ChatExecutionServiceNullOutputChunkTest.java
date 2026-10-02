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
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式分片中 {@code output} 为 null 的畸形形状（Batch 784）。
 *
 * <p>Spring AI 的 {@code MessageAggregator.aggregate} 只挡了 {@code getResult() == null}，
 * 进入该分支后对 {@code getResult().getOutput()} 做了三次裸解引用。provider 只要吐出
 * 一个 {@code output} 为 null 的分片（内容过滤后的空块、只带 tool-call 的占位块、
 * 部分网关的心跳块），聚合器就在 {@code doOnNext} 里抛**裸 NullPointerException**，
 * 经 {@code then(...)} 直接跳过收尾，最终以一个没有错误码的 NPE 结束整条 SSE 流。
 *
 * <p>本类钉住三件事：
 * <ol>
 *   <li>畸形分片**不得毁掉整条流**——三取一畸形时另外两段内容必须完整送达；</li>
 *   <li>全部分片畸形时**不得抛裸 NPE**；其收尾行为与"provider 一个分片都没给"
 *       一致（空 {@code Completed}）——这是候选回退依赖的既有契约，
 *       "所有分片都残缺"与"内容为空"无法区分是已登记的设计取舍；</li>
 *   <li>过滤器必须**只针对那一个形状**——{@code result == null} 的分片携带的
 *       usage 元数据不能被提前丢掉。</li>
 * </ol>
 */
class ChatExecutionServiceNullOutputChunkTest {

    private ChatModelRouter modelRouter;
    private ModeAwareChatClientFactory clientFactory;
    private RagChatHistoryRepository historyRepository;
    private ChatExecutionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelRouter = mock(ChatModelRouter.class);
        clientFactory = mock(ModeAwareChatClientFactory.class);
        historyRepository = mock(RagChatHistoryRepository.class);
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

    private ChatModelRouter.ChatModelCandidate candidate(String ref) {
        ChatModel model = mock(ChatModel.class);
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

    @SuppressWarnings("unchecked")
    private ChatClient streamClient(Flux<ChatClientResponse> responses) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream =
                mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.toolCallbacks(any(KnowledgeSearchTool.class))).thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.options(any(ToolCallingChatOptions.class))).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.chatClientResponse()).thenReturn(responses);
        return client;
    }

    private List<ChatEvent> stream(Flux<ChatClientResponse> responses) {
        ChatModelRouter.ChatModelCandidate candidate = candidate("solo");
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        ChatClient client = streamClient(responses);
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, candidate, context(), null));
        return service.stream(command(ChatMode.PLAIN)).collectList().block();
    }

    private static ChatClientResponse chunk(String content) {
        return new ChatClientResponse(
                new ChatResponse(
                        List.of(new Generation(new AssistantMessage(content))),
                        ChatResponseMetadata.builder().build()),
                Map.of());
    }

    /** {@code result} 非空但 {@code output} 为 null：聚合器 NPE 的触发形状。 */
    private static ChatClientResponse nullOutputChunk() {
        return new ChatClientResponse(
                new ChatResponse(
                        List.of(new Generation((AssistantMessage) null)),
                        ChatResponseMetadata.builder().build()),
                Map.of());
    }

    /** {@code result} 为 null 但携带 usage：聚合器会采纳其元数据，不能提前丢掉。 */
    private static ChatClientResponse nullResultChunk(int totalTokens) {
        return new ChatClientResponse(
                new ChatResponse(
                        List.of(),
                        ChatResponseMetadata.builder()
                                .usage(new DefaultUsage(0, 0, totalTokens))
                                .build()),
                Map.of());
    }

    private static List<String> contents(List<ChatEvent> events) {
        return events.stream()
                .filter(ChatEvent.ContentDelta.class::isInstance)
                .map(e -> ((ChatEvent.ContentDelta) e).content())
                .toList();
    }

    private static ChatEvent.Completed completed(List<ChatEvent> events) {
        assertInstanceOf(ChatEvent.Completed.class, events.getLast(),
                "最后一个事件应为 Completed");
        return (ChatEvent.Completed) events.getLast();
    }

    /** 被持久化的聚合答案——这才是"畸形分片有没有毁掉聚合结果"的直接证据。 */
    private String persistedAnswer() {
        ArgumentCaptor<String> answer = ArgumentCaptor.forClass(String.class);
        verify(historyRepository).save(
                anyString(), anyString(), answer.capture(),
                any(), any());
        return answer.getValue();
    }

    @Test
    void malformedChunkDoesNotDestroyTheStream() {
        // 三取一畸形：前后两段内容都必须完整送达，聚合答案也必须只由可用分片拼成。
        // 去掉 survivesMessageAggregation 过滤，本例会以裸 NullPointerException 失败。
        List<ChatEvent> events = stream(Flux.just(
                chunk("前半"), nullOutputChunk(), chunk("后半")));

        assertEquals(List.of("前半", "后半"), contents(events),
                "畸形分片不应影响其余分片的内容");
        assertEquals("前半后半", persistedAnswer(),
                "聚合答案应只由可用分片拼成");
    }

    @Test
    void allChunksMalformedCompletesEmptyTurnWithoutRawNpe() {
        // 全部分片畸形 → 行为与"provider 一个分片都没给"一致：空 Completed 收尾。
        // 这是**既有契约**而不是缺陷：候选回退依赖"空流不算错误"，
        // 见 ChatExecutionStreamBudgetTest#allEmptyCandidateStreamsCompleteWithoutContentOrError。
        // 本用例要钉住的是"不得抛裸 NPE"，不是"必须报错"。
        List<ChatEvent> events = stream(Flux.just(
                nullOutputChunk(), nullOutputChunk()));

        assertTrue(contents(events).isEmpty(),
                "畸形分片本就不该产生内容");
        assertInstanceOf(ChatEvent.Completed.class, events.getLast());
    }

    @Test
    void emptyStreamStillCompletesTurnWithoutError() {
        // 回归：provider 一个分片都没给时行为不变（候选回退依赖这一点）。
        List<ChatEvent> events = stream(Flux.empty());

        assertTrue(contents(events).isEmpty());
        assertInstanceOf(ChatEvent.Completed.class, events.getLast());
    }

    @Test
    void nullResultChunkKeepsItsUsageMetadata() {
        // 过滤器只针对 output==null 那一个形状：result==null 的分片必须放行，
        // 否则它携带的 usage 会被丢掉，少计 token。
        List<ChatEvent> events = stream(Flux.just(
                chunk("正文"), nullResultChunk(4321)));

        assertEquals(List.of("正文"), contents(events));
        assertEquals(4321, completed(events).usage().get("totalTokens"),
                "result==null 分片携带的 usage 应仍被聚合");
    }

    @Test
    void wellFormedStreamIsUnaffected() {
        // 回归：正常流的事件序列与聚合答案不变。
        List<ChatEvent> events = stream(Flux.just(chunk("甲"), chunk("乙")));

        assertEquals(List.of("甲", "乙"), contents(events));
        assertEquals("甲乙", persistedAnswer());
        assertTrue(completed(events).sessionId().equals("session-1"));
    }
}
