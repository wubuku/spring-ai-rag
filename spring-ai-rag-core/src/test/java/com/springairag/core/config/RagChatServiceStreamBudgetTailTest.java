package com.springairag.core.config;

import com.springairag.api.dto.ChatRequest;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.repository.RagChatHistoryRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式预算耗尽长尾（Batch 716，JaCoCo 驱动）：candidate-attempt
 * 预算为 1 时，同一 Flux 的第二次订阅触发
 * CHAT_BUDGET_EXHAUSTED（Flux.defer 每次订阅重新求值）（886-892）。
 */
class RagChatServiceStreamBudgetTailTest {

    private ChatClient.Builder chatClientBuilder;
    private ChatModelRouter chatModelRouter;
    private JdbcChatMemoryRepository jdbcChatMemoryRepository;
    private RagChatHistoryRepository historyRepository;

    @BeforeEach
    void setUp() {
        chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.defaultAdvisors(anyList()))
                .thenReturn(chatClientBuilder);
        when(chatClientBuilder.build())
                .thenReturn(mock(ChatClient.class));
        chatModelRouter = mock(ChatModelRouter.class);
        jdbcChatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        when(jdbcChatMemoryRepository.findByConversationId(anyString()))
                .thenReturn(List.of());
        historyRepository = mock(RagChatHistoryRepository.class);
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
                mock(com.springairag.core.extension.DomainExtensionRegistry.class),
                mock(com.springairag.core.extension.PromptCustomizerChain.class),
                ragProperties,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void secondSubscriptionOfSameStreamExhaustsCandidateBudget() {
        RagProperties properties = new RagProperties();
        properties.getChat().getExecution().setMaxCandidateAttempts(1);
        RagChatService service = createService(properties);

        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(
                        new AssistantMessage("Hel"))))));
        when(model.getDefaultOptions()).thenReturn(null);
        when(chatModelRouter.orderedCandidateDescriptors("m"))
                .thenReturn(List.of(new ChatModelRouter.ChatModelCandidate(
                        "m-a", model,
                        MultiModelProperties.ModelCapabilities.defaults())));
        when(chatModelRouter.orderedCandidates(any()))
                .thenReturn(List.of(model));

        ChatRequest request = new ChatRequest("问题", "session-budget");
        request.setModel("m");
        Flux<String> flux = service.chatStream(request);

        // 第一次订阅：候选尝试预算 1 被消耗，正常流出。
        assertEquals(List.of("Hel"), flux.collectList().block());

        // 第二次订阅（defer 重新求值）：预算耗尽 → CHAT_BUDGET_EXHAUSTED。
        RagException error = assertThrows(RagException.class,
                () -> flux.collectList().block());
        assertEquals(com.springairag.api.enums.ErrorCode.CHAT_BUDGET_EXHAUSTED,
                error.getErrorCodeEnum());
        verify(model, times(1)).stream(any(Prompt.class));
    }
}
