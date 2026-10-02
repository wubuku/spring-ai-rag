package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatExecutionService 对**残缺模型响应**的处理（Batch 777，JaCoCo 行级驱动）。
 *
 * <p>三处空响应守卫此前各自只走通了 {@code ||} 链的第一个条件。本批用诊断
 * （先写一个只打印不断言的临时用例，观察真实行为再落断言）逐条确认了可达性，
 * 结论与最初假设**并不一致**，因此这里同时钉住"做了什么"和"没做什么"。
 *
 * <h2>真正可达、已覆盖</h2>
 * <ul>
 *   <li>{@code invokeCall}（非流式）三条残缺分支 → 抛 "no usable <em>chat</em> response"。
 *       该文案此前在测试目录中 0 处断言。</li>
 *   <li>候选全部失败时重抛最后一个失败原因，而不是退化成"无可用模型"。</li>
 *   <li>{@code responseEvents}（流式）三条残缺分支 → 静默丢弃，不抛错。</li>
 * </ul>
 *
 * <h2>确认不可达，刻意不测</h2>
 * <ul>
 *   <li>{@code completeStreamAttempt} 的空响应守卫：Spring AI 的
 *       {@code ChatClientMessageAggregator} 总会以**非 null** 的聚合响应回调，
 *       所以流式路径下该守卫拿到的永远是归一化后的响应。</li>
 *   <li>"候选没有产出任何事件 → 回退下一个"：{@code completeStreamAttempt} 在
 *       聚合结果上总会发出 {@code SourcesAvailable} + {@code Completed}，
 *       候选"空"不了，{@code switchOnFirst} 永远拿不到无值的完成信号。</li>
 *   <li>{@code responseEvents} 的 {@code response == null} 条件：其唯一调用点
 *       是 {@code doOnNext}，而 Reactor 禁止在 Flux 中发射 null 元素。</li>
 * </ul>
 *
 * <p>把这三条跑出来只能靠给生产代码加测试钩子。本批不这么做：钩子会改变被测代码的
 * 结构，从而让"覆盖了"这件事失去意义。宁可如实记下"这些分支在公开路径上不存在"。
 *
 * <p><strong>Batch 784 已修复其中第 1 条</strong>：
 * {@code output} 为 null 的分片不再抛裸 NullPointerException 逃出聊天接口。
 * 聚合器（Spring AI 的 {@code MessageAggregator}）在 {@code getResult() != null}
 * 分支里对 {@code getOutput()} 做了三次裸解引用；现在这类分片在**进入聚合器之前**
 * 被滤掉，因此其余分片的内容照常送达，整轮正常以 {@code Completed} 收尾。
 * 对应用例 {@code nullOutputChunkNoLongerEscapesAsNpe} 由"断言当前缺陷"
 * 翻转为回归防线。
 *
 * <p><strong>第 2 条仍是已登记的设计取舍（未改）</strong>：
 * <ol>
 * <li>其余残缺分片会让整轮流以"成功但没有内容"的 {@code Completed} 收尾——用户看到
 *     的是空回答而不是错误。逐分片静默丢弃在长流里是对的，但"所有分片都残缺"与
 *     "内容为空"目前无法区分。这属于设计取舍。</li>
 * </ol>
 * 它与"provider 一个分片都没给"的既有行为是同一条路径：候选回退依赖"空流不算错误"，
 * 否则第一个候选返回空就永远不会轮到第二个候选
 * （见 {@code ChatExecutionStreamBudgetTest#allEmptyCandidateStreamsCompleteWithoutContentOrError}）。
 */
class ChatExecutionServiceDegenerateResponseTest {

    private ChatModelRouter modelRouter;
    private ModeAwareChatClientFactory clientFactory;
    private ChatExecutionService service;

    @BeforeEach
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

    // ── 命令与候选脚手架 ────────────────────────────────────────────────

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

    private ChatModelRouter.ChatModelCandidate candidate(String ref, boolean streaming) {
        return new ChatModelRouter.ChatModelCandidate(
                ref, mock(ChatModel.class),
                new MultiModelProperties.ModelCapabilities(streaming, false));
    }

    private AuthorizedRetrievalContext context() {
        return new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                new RetrievalTraceCollector(),
                "session-1",
                ChatPrincipal.local());
    }

    // ── 三种残缺响应 ────────────────────────────────────────────────────

    /** {@code chatResponse()} 为 null：只有 record 本身还能构造出来。 */
    private ChatClientResponse nullChatResponse() {
        return new ChatClientResponse(null, Map.of());
    }

    /** {@code getResult()} 为 null：模型的响应体存在但没有结果。 */
    private ChatClientResponse nullResultResponse() {
        ChatResponse chatResponse = mock(ChatResponse.class);
        when(chatResponse.getResult()).thenReturn(null);
        return new ChatClientResponse(chatResponse, Map.of());
    }

    /** {@code getOutput()} 为 null：有 generation，但 generation 没有消息。 */
    private ChatClientResponse nullOutputResponse() {
        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(null);
        return new ChatClientResponse(
                new ChatResponse(List.of(generation), ChatResponseMetadata.builder().build()),
                Map.of());
    }

    private ChatClientResponse healthyResponse(String content) {
        return new ChatClientResponse(
                new ChatResponse(
                        List.of(new Generation(new AssistantMessage(content))),
                        ChatResponseMetadata.builder().build()),
                Map.of());
    }

    // ── 客户端脚手架 ────────────────────────────────────────────────────

    private record CallFixture(ChatClient client) {
    }

    @SuppressWarnings("unchecked")
    private CallFixture callFixture(ChatClientResponse response) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.toolCallbacks(any(KnowledgeSearchTool.class))).thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.options(any(ToolCallingChatOptions.class))).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.chatClientResponse()).thenReturn(response);
        return new CallFixture(client);
    }

    @SuppressWarnings("unchecked")
    private CallFixture streamFixture(Flux<ChatClientResponse> responses) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
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
        return new CallFixture(client);
    }

    private void useCandidate(ChatModelRouter.ChatModelCandidate candidate, ChatClient client) {
        when(clientFactory.create(any(), org.mockito.ArgumentMatchers.same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, candidate, context(), null));
    }

    // ── 非流式：invokeCall 的残缺响应 ───────────────────────────────────

    @Nested
    @DisplayName("execute()：模型返回残缺响应")
    class ExecuteDegenerate {

        @Test
        @DisplayName("chatResponse 为 null 时拒绝该响应并向上抛错")
        void nullChatResponseIsRejected() {
            ChatModelRouter.ChatModelCandidate only = candidate("solo", false);
            when(modelRouter.orderedCandidateDescriptors(isNull())).thenReturn(List.of(only));
            useCandidate(only, callFixture(nullChatResponse()).client());

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.execute(command(ChatMode.PLAIN)));

            // 消息刻意区别于流式路径：两者是不同方法，不是同一份实现。
            assertEquals("LLM returned no usable chat response", error.getMessage());
        }

        @Test
        @DisplayName("result 为 null 时同样拒绝")
        void nullResultIsRejected() {
            ChatModelRouter.ChatModelCandidate only = candidate("solo", false);
            when(modelRouter.orderedCandidateDescriptors(isNull())).thenReturn(List.of(only));
            useCandidate(only, callFixture(nullResultResponse()).client());

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.execute(command(ChatMode.PLAIN)));
            assertEquals("LLM returned no usable chat response", error.getMessage());
        }

        @Test
        @DisplayName("output 为 null 时同样拒绝")
        void nullOutputIsRejected() {
            ChatModelRouter.ChatModelCandidate only = candidate("solo", false);
            when(modelRouter.orderedCandidateDescriptors(isNull())).thenReturn(List.of(only));
            useCandidate(only, callFixture(nullOutputResponse()).client());

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.execute(command(ChatMode.PLAIN)));
            assertEquals("LLM returned no usable chat response", error.getMessage());
        }

        @Test
        @DisplayName("全部候选都失败时重抛最后一个失败原因")
        void allCandidatesFailingRethrowsLastFailure() {
            ChatModelRouter.ChatModelCandidate first = candidate("a", false);
            ChatModelRouter.ChatModelCandidate second = candidate("b", false);
            when(modelRouter.orderedCandidateDescriptors(isNull()))
                    .thenReturn(List.of(first, second));
            useCandidate(first, callFixture(nullChatResponse()).client());
            useCandidate(second, callFixture(nullOutputResponse()).client());

            // 候选链耗尽后不是退化成"无可用模型"，而是把最后一次失败原样抛回去，
            // 否则调用方看到的是一个与真实原因无关的 LLM_UNAVAILABLE。
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.execute(command(ChatMode.PLAIN)));
            assertEquals("LLM returned no usable chat response", error.getMessage());
        }
    }

    // ── 流式：responseEvents 的静默丢弃 + completeStreamAttempt 的抛错 ────

    @Nested
    @DisplayName("stream()：残缺分片被静默丢弃，流照常收尾")
    class StreamDegenerate {

        @Test
        @DisplayName("chatResponse 为 null 的分片不产生内容增量，流仍正常完成")
        void nullChatResponseChunkEmitsNoContentDelta() {
            // 实测行为（Batch 777 诊断）：Spring AI 的 ChatClientMessageAggregator
            // 总会用**非 null** 的聚合响应回调，于是 completeStreamAttempt 拿到的是
            // 归一化后的响应，它自己的空响应守卫根本不会被触达。结果是残缺分片被
            // 静默丢弃，流以一个"成功但没有内容"的 Completed 收尾。
            List<ChatEvent> events = streamOnce(
                    Flux.just(nullChatResponse()));

            assertNoContentDelta(events);
            assertTrue(events.stream().anyMatch(e -> e instanceof ChatEvent.Completed),
                    "残缺分片不应让整轮流式失败: " + events);
        }

        @Test
        @DisplayName("result 为 null 的分片同样被静默丢弃")
        void nullResultChunkEmitsNoContentDelta() {
            assertNoContentDelta(streamOnce(Flux.just(nullResultResponse())));
        }

        @Test
        @DisplayName("output 为 null 的分片不再让 NPE 逃出整轮流（Batch 784 修复）")
        void nullOutputChunkNoLongerEscapesAsNpe() {
            // Batch 777 刻意断言"当前会抛裸 NPE"并留下一个显式失败点，
            // 注明"修好之后，它应当改为断言不抛 NPE"。Batch 784 修好了，
            // 本用例随之翻转为回归防线。
            //
            // `responseEvents` 的守卫当初只挡住了"事件发射"这条路径；聚合器
            // （Spring AI 的 ChatClientMessageAggregator → MessageAggregator）
            // 在守卫之外裸解引用 `Generation.getOutput().getText()`，于是 output
            // 为 null 的分片抛出裸 NPE。修复方式是在进入聚合器之前滤掉该形状。
            //
            // 期望行为与同文件另外两种残缺分片一致——静默丢弃、整轮以 Completed
            // 收尾（与"provider 一个分片都没给"同路径，属既有契约）。
            ChatModelRouter.ChatModelCandidate only = candidate("solo", true);
            when(modelRouter.orderedCandidateDescriptors(isNull())).thenReturn(List.of(only));
            useCandidate(only, streamFixture(Flux.just(nullOutputResponse())).client());

            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<ChatEvent> events = service.stream(command(ChatMode.PLAIN))
                    .collectList()
                    .doOnError(failure::set)
                    .onErrorResume(e -> reactor.core.publisher.Mono.just(List.<ChatEvent>of()))
                    .block();

            assertNull(failure.get(),
                    "畸形分片不得再让裸 NPE 逃出聊天接口，实际逃出: " + failure.get());
            assertNotNull(events);
            assertNoContentDelta(events);
            assertTrue(events.stream().anyMatch(e -> e instanceof ChatEvent.Completed),
                    "畸形分片不应让整轮流式失败: " + events);
        }

        @Test
        @DisplayName("残缺分片不会污染后续正常分片的内容")
        void degenerateChunkDoesNotSuppressLaterHealthyChunk() {
            // 静默丢弃若实现错了，最可能错成"整条流都不产出内容"——那才是真的 bug。
            // 这里把残缺分片放在健康分片**前面**，验证丢弃是逐分片的。
            List<ChatEvent> events = streamOnce(Flux.just(
                    nullChatResponse(),
                    healthyResponse("真实答案")));

            assertTrue(events.stream().anyMatch(e ->
                            e instanceof ChatEvent.ContentDelta delta
                                    && "真实答案".equals(delta.content())),
                    "健康分片的内容必须照常透出，实际: " + events);
        }
    }

    @Nested
    @DisplayName("候选回退：首个候选一个事件都没产生")
    class EmptyStreamFallback {

        @Test
        @DisplayName("客户端空流不会触发回退——聚合器仍会合成一个 Completed 事件")
        void emptyUpstreamStreamDoesNotTriggerFallback() {
            // 这条用例的作用是**钉住实测行为**，并让"空流回退"那段代码保持诚实。
            //
            // 曾经的假设是"客户端返回空流 → 该候选没有产出任何事件 → 回退下一个"。
            // 诊断证明假设是错的：completeStreamAttempt 在聚合结果上总会发出
            // SourcesAvailable + Completed，所以候选"空"不了，
            // switchOnFirst 永远拿不到"无值的完成信号"。
            // 换句话说 ChatExecutionService:592-593 那两个分支在公开路径上不可达。
            //
            // 本用例不试图把那条分支跑出来——那需要给生产代码加测试钩子。
            // 它记录的是：空流**不会**被当成失败候选而白白消耗一次回退机会。
            ChatModelRouter.ChatModelCandidate first = candidate("a", true);
            ChatModelRouter.ChatModelCandidate second = candidate("b", true);
            when(modelRouter.orderedCandidateDescriptors(isNull()))
                    .thenReturn(List.of(first, second));
            AtomicInteger created = new AtomicInteger();

            when(clientFactory.create(any(),
                    org.mockito.ArgumentMatchers.argThat(c ->
                            c != null && "a".equals(c.ref())), anyList()))
                    .thenAnswer(inv -> {
                        created.incrementAndGet();
                        return new ModeAwareChatClientFactory.Attempt(
                                streamFixture(Flux.empty()).client(), first, context(), null);
                    });
            when(clientFactory.create(any(),
                    org.mockito.ArgumentMatchers.argThat(c ->
                            c != null && "b".equals(c.ref())), anyList()))
                    .thenAnswer(inv -> {
                        created.incrementAndGet();
                        return new ModeAwareChatClientFactory.Attempt(
                                streamFixture(Flux.just(
                                        healthyResponse("来自回退"))).client(),
                                second, context(), null);
                    });

            List<ChatEvent> events = service.stream(command(ChatMode.PLAIN))
                    .collectList().block();

            assertEquals(1, created.get(),
                    "聚合器会为首个候选合成 Completed 事件，因此不发生回退");
            assertTrue(events != null && events.stream().noneMatch(e ->
                            e instanceof ChatEvent.ContentDelta delta
                                    && "来自回退".equals(delta.content())),
                    "第二个候选不应被启用: " + events);
        }
    }

    // ── 辅助断言 ────────────────────────────────────────────────────────

    /**
     * 跑一次单候选流式调用，并断言它**以正常完成收尾**。
     *
     * <p>刻意不吞掉错误：如果这里用 {@code onErrorResume} 把异常换成空列表，
     * "残缺分片被静默丢弃"就会退化成"什么都没产出也算通过"——把守卫删掉之后
     * 残缺分片会 NPE，而空列表同样满足"没有 ContentDelta"这条断言。
     * 变异测试实测过这个假绿：删掉 4 个守卫条件中的 3 个，4 个用例只红 2 个。
     * 显式断言"没有错误"之后，NPE 无法冒充通过。
     */
    private List<ChatEvent> streamOnce(Flux<ChatClientResponse> responses) {
        ChatModelRouter.ChatModelCandidate only = candidate("solo", true);
        when(modelRouter.orderedCandidateDescriptors(isNull())).thenReturn(List.of(only));
        useCandidate(only, streamFixture(responses).client());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<ChatEvent> events = service.stream(command(ChatMode.PLAIN))
                .collectList()
                .doOnError(failure::set)
                .onErrorResume(e -> reactor.core.publisher.Mono.just(List.<ChatEvent>of()))
                .block();

        assertTrue(events != null, "流必须收尾，不得挂起");
        assertNull(failure.get(),
                "残缺分片必须被静默丢弃而不是让整轮流式失败，实际抛出: " + failure.get());
        return events;
    }

    private void assertNoContentDelta(List<ChatEvent> events) {
        assertFalse(events.stream().anyMatch(e -> e instanceof ChatEvent.ContentDelta),
                "残缺响应不得产生内容增量，实际: " + events);
    }
}
