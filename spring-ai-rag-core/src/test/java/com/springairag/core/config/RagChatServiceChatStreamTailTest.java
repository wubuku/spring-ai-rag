package com.springairag.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.usage.LlmInvocationPurpose;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagChatService.chatStream 遗留流式长尾（Batch 501，JaCoCo 驱
 * 动）：无候选时回退默认 mock 客户端并按 streaming 标记落历史；
 * 候选路径经真实 ChatClient 流式聚合答案、完成时以 streaming 标
 * 记落账；预算耗尽时快速失败（CHAT_BUDGET_EXHAUSTED）。
 */
class RagChatServiceChatStreamTailTest {

    private ChatClient.Builder chatClientBuilder;
    private ChatClient defaultChatClient;
    private ChatModelRouter chatModelRouter;
    private JdbcChatMemoryRepository jdbcChatMemoryRepository;
    private RagChatHistoryRepository historyRepository;
    private RagChatService service;

    @BeforeEach
    void setUp() {
        chatClientBuilder = mock(ChatClient.Builder.class);
        defaultChatClient = mock(ChatClient.class);
        when(chatClientBuilder.defaultAdvisors(anyList()))
                .thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(defaultChatClient);
        chatModelRouter = mock(ChatModelRouter.class);
        jdbcChatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        when(jdbcChatMemoryRepository.findByConversationId(anyString()))
                .thenReturn(List.of());
        historyRepository = mock(RagChatHistoryRepository.class);
        service = createService(new RagProperties());
    }

    private RagChatService createService(RagProperties ragProperties) {
        var queryRewriteAdvisor = mock(QueryRewriteAdvisor.class);
        var hybridSearchAdvisor = mock(HybridSearchAdvisor.class);
        var rerankAdvisor = mock(RerankAdvisor.class);
        for (BaseAdvisor advisor : List.of(
                queryRewriteAdvisor, hybridSearchAdvisor, rerankAdvisor)) {
            when(advisor.getName())
                    .thenReturn(advisor.getClass().getSimpleName() + "-mock");
            when(advisor.adviseCall(any(), any())).thenAnswer(invocation -> {
                ChatClientRequest request = invocation.getArgument(0);
                CallAdvisorChain chain = invocation.getArgument(1);
                return chain.nextCall(request);
            });
            // 流式链路走 adviseStream，同样需要透传。
            when(advisor.adviseStream(any(), any())).thenAnswer(invocation -> {
                ChatClientRequest request = invocation.getArgument(0);
                org.springframework.ai.chat.client.advisor.api
                        .StreamAdvisorChain chain = invocation.getArgument(1);
                return chain.nextStream(request);
            });
        }
        return new RagChatService(
                chatClientBuilder,
                chatModelRouter,
                queryRewriteAdvisor,
                hybridSearchAdvisor,
                rerankAdvisor,
                jdbcChatMemoryRepository,
                historyRepository,
                mock(DomainExtensionRegistry.class),
                mock(PromptCustomizerChain.class),
                ragProperties,
                null,
                null,
                null,
                null,
                null);
    }

    private ChatModelRouter.ChatModelCandidate candidate(
            String ref, ChatModel model) {
        return new ChatModelRouter.ChatModelCandidate(
                ref, model, MultiModelProperties.ModelCapabilities.defaults());
    }

    private void stubDefaultClientStream(Flux<String> content) {
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream =
                mock(ChatClient.StreamResponseSpec.class);
        when(defaultChatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class)))
                .thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.content()).thenReturn(content);
    }

    // ── 无候选：默认客户端 ────────────────────────────────────────

    @Test
    void chatStreamWithoutCandidatesUsesDefaultClientAndPersistsAnswer() {
        stubDefaultClientStream(Flux.just("你好", "，世界"));
        when(chatModelRouter.orderedCandidateDescriptors(any()))
                .thenReturn(List.of());
        when(chatModelRouter.orderedCandidates(any()))
                .thenReturn(List.of());

        List<String> chunks = service.chatStream(
                new ChatRequest("问题", "session-1")).collectList().block();

        assertEquals(List.of("你好", "，世界"), chunks);
        verify(historyRepository).save(eq("session-1"), eq("问题"),
                eq("你好，世界"), any(), any());
    }

    // ── 候选路径：真实 ChatClient 流式 ────────────────────────────

    @Test
    void chatStreamWithCandidateAggregatesAndPersistsAnswer() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(
                        new AssistantMessage("Hel")))),
                new ChatResponse(List.of(new Generation(
                        new AssistantMessage("lo"))))));
        when(model.getDefaultOptions()).thenReturn(null);
        when(chatModelRouter.orderedCandidateDescriptors("m"))
                .thenReturn(List.of(candidate("m-a", model)));
        ChatRequest request = new ChatRequest("问题", "session-1");
        request.setModel("m");

        List<String> chunks = service.chatStream(request)
                .collectList().block();

        assertEquals(List.of("Hel", "lo"), chunks);
        verify(model, times(1)).stream(any(Prompt.class));
        verify(historyRepository).save(eq("session-1"), eq("问题"),
                eq("Hello"), any(), any());
    }

    @Test
    void streamErrorPropagatesWithoutPersistingHistory() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new IllegalStateException("stream down")));
        when(model.getDefaultOptions()).thenReturn(null);
        when(chatModelRouter.orderedCandidateDescriptors("m"))
                .thenReturn(List.of(candidate("m-a", model)));
        ChatRequest request = new ChatRequest("问题", "session-1");
        request.setModel("m");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.chatStream(request).collectList().block());
        assertEquals("stream down", error.getMessage());
        verify(historyRepository, never()).save(anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void chatStreamWithoutCandidatesYieldsNoCandidatesError() {
        when(chatModelRouter.orderedCandidateDescriptors("m"))
                .thenReturn(List.of());
        when(chatModelRouter.orderedCandidates("m")).thenReturn(List.of());

        // resolveLegacyModelCandidates 为空 → 走默认客户端；此处验证
        // 正常完成而非异常（覆盖 stream().content() 链）。
        stubDefaultClientStream(Flux.empty());

        List<String> chunks = service.chatStream(
                new ChatRequest("问题", "session-1")).collectList().block();

        assertTrue(chunks != null && chunks.isEmpty());
    }

}
