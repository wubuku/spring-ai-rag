package com.springairag.core.config;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.exception.LlmCircuitOpenException;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.resilience.LlmCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;

import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
 * RagChatService 遗留多模型 failover 长尾（Batch 472，JaCoCo 驱
 * 动）：executeChat 候选循环的失败切换、全候选失败传播最后一个错
 * 误、candidate-attempt 预算耗尽（CHAT_BUDGET_EXHAUSTED）、熔断
 * 打开后直接拒绝 + invokeChatClient 失败记账，以及 14 参构造器委
 * 托。
 *
 * <p>候选路径走 buildLegacyClient 构造的<strong>真实 ChatClient</strong>：
 * mock 的 RAG advisor 以 passthrough adviseCall 参与 advisor 链，
 * 真实 MessageChatMemoryAdvisor 由空会话 stub 支撑，ChatModel
 * mock 直接决定成功/失败。
 */
class RagChatServiceLegacyFailoverTest {

    private ChatClient.Builder chatClientBuilder;
    private ChatModelRouter chatModelRouter;
    private JdbcChatMemoryRepository jdbcChatMemoryRepository;
    private RagChatHistoryRepository historyRepository;
    private QueryRewriteAdvisor queryRewriteAdvisor;
    private HybridSearchAdvisor hybridSearchAdvisor;
    private RerankAdvisor rerankAdvisor;

    @BeforeEach
    void setUp() {
        chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.defaultAdvisors(anyList()))
                .thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(mock(ChatClient.class));
        chatModelRouter = mock(ChatModelRouter.class);
        jdbcChatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        when(jdbcChatMemoryRepository.findByConversationId(anyString()))
                .thenReturn(List.of());
        historyRepository = mock(RagChatHistoryRepository.class);
        queryRewriteAdvisor = mock(QueryRewriteAdvisor.class);
        hybridSearchAdvisor = mock(HybridSearchAdvisor.class);
        rerankAdvisor = mock(RerankAdvisor.class);
    }

    /** mock advisor 在真实 advisor 链中的透传（不执行 before/after）。 */
    private void passThrough(Object advisor) {
        org.springframework.ai.chat.client.advisor.api.BaseAdvisor base =
                (org.springframework.ai.chat.client.advisor.api.BaseAdvisor)
                        advisor;
        // 真实 ChatClient 要求 advisor 名字非空（构建链时校验）。
        when(base.getName()).thenReturn(
                advisor.getClass().getSimpleName() + "-mock");
        when(base.adviseCall(any(), any())).thenAnswer(inv -> {
            ChatClientRequest request = inv.getArgument(0);
            CallAdvisorChain chain = inv.getArgument(1);
            return chain.nextCall(request);
        });
    }

    private RagChatService createService(RagProperties ragProperties) {
        passThrough(queryRewriteAdvisor);
        passThrough(hybridSearchAdvisor);
        passThrough(rerankAdvisor);
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
                null);
    }

    /** 14 参构造器委托（usageClientFactory 缺省）。 */
    private RagChatService createService14Args(RagProperties ragProperties) {
        passThrough(queryRewriteAdvisor);
        passThrough(hybridSearchAdvisor);
        passThrough(rerankAdvisor);
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

    private ChatModel failingModel(String message) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenThrow(new RuntimeException(message));
        return model;
    }

    private ChatModel answeringModel(String answer) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage(answer)))));
        return model;
    }

    private ChatRequest chatRequest() {
        ChatRequest request = new ChatRequest("hi", "session-1");
        request.setModel("m");
        return request;
    }

    @Test
    void firstCandidateFailureFallsBackToSecondCandidate() {
        ChatModel failing = failingModel("boom-A");
        ChatModel answering = answeringModel("fallback");
        when(chatModelRouter.orderedCandidateDescriptors("m"))
                .thenReturn(List.of(candidate("m-a", failing),
                        candidate("m-b", answering)));
        RagChatService service = createService(new RagProperties());

        ChatResponse response = service.chat(chatRequest());

        assertEquals("fallback", response.getAnswer());
        assertTrue(response.getSources() == null
                || response.getSources().isEmpty());
        verify(failing, times(1)).call(any(Prompt.class));
        verify(answering, times(1)).call(any(Prompt.class));
        verify(historyRepository).save(eq("session-1"), eq("hi"),
                eq("fallback"), any(), any());
    }

    @Test
    void allCandidatesFailPropagatesLastFailure() {
        ChatModel first = failingModel("boom-A");
        ChatModel last = failingModel("boom-B");
        when(chatModelRouter.orderedCandidateDescriptors("m"))
                .thenReturn(List.of(candidate("m-a", first),
                        candidate("m-b", last)));
        RagChatService service = createService(new RagProperties());

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.chat(chatRequest()));

        assertEquals("boom-B", error.getMessage());
        verify(first, times(1)).call(any(Prompt.class));
        verify(last, times(1)).call(any(Prompt.class));
        verify(historyRepository, never()).save(anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void candidateAttemptBudgetExhaustedAbortsRemainingCandidates() {
        RagProperties ragProperties = new RagProperties();
        ragProperties.getChat().getExecution().setMaxCandidateAttempts(1);
        ChatModel first = failingModel("boom-A");
        ChatModel second = answeringModel("should-not-run");
        when(chatModelRouter.orderedCandidateDescriptors("m"))
                .thenReturn(List.of(candidate("m-a", first),
                        candidate("m-b", second)));
        RagChatService service = createService(ragProperties);

        RagException error = assertThrows(RagException.class,
                () -> service.chat(chatRequest()));

        assertEquals(ErrorCode.CHAT_BUDGET_EXHAUSTED,
                error.getErrorCodeEnum());
        verify(first, times(1)).call(any(Prompt.class));
        verify(second, never()).call(any(Prompt.class));
    }

    @Test
    void openCircuitBreakerRejectsCallBeforeExecution() {
        RagProperties ragProperties = new RagProperties();
        ragProperties.getCircuitBreaker().setEnabled(true);
        ragProperties.getCircuitBreaker().setMinimumNumberOfCalls(1);
        ragProperties.getCircuitBreaker().setFailureRateThreshold(1);
        ChatModel failing = failingModel("boom-A");
        when(chatModelRouter.orderedCandidateDescriptors("m"))
                .thenReturn(List.of(candidate("m-a", failing)));
        // 14 参构造器委托路径。
        RagChatService service = createService14Args(ragProperties);

        // 第 1 次：唯一候选失败 → invokeChatClient 记账熔断 → OPEN。
        assertThrows(RuntimeException.class, () -> service.chat(chatRequest()));
        // 第 2 次：熔断已打开，进入候选循环前即拒绝（不再触达模型）。
        assertInstanceOf(LlmCircuitOpenException.class,
                assertThrows(RuntimeException.class,
                        () -> service.chat(chatRequest())));
        verify(failing, times(1)).call(any(Prompt.class));
    }
}
