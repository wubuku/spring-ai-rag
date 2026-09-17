package com.springairag.core.config;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.BudgetedChatModel;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.usage.LlmInvocationPurpose;
import com.springairag.core.chat.ModeAwareChatClientFactory;
import com.springairag.core.exception.RagException;
import com.springairag.core.metrics.RagMetricsService;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagChatService 韧性长尾（Batch 490，JaCoCo 驱动）：模型返回空
 * 结果的显式报错、RetryTemplate 二次重试成功、metrics 失败记账、
 * usageClientFactory 包装候选模型，以及 chatEvents 未配置
 * mode-aware 的错误流与已配置时的完成/错误传播。
 */
class RagChatServiceResilienceTailTest {

    private ChatClient.Builder chatClientBuilder;
    private ChatModelRouter chatModelRouter;
    private JdbcChatMemoryRepository jdbcChatMemoryRepository;
    private RagMetricsService metricsService;
    private ModeAwareChatClientFactory usageClientFactory;
    private ChatExecutionService modeAwareExecutionService;
    private ChatCommandMapper commandMapper;

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
        metricsService = mock(RagMetricsService.class);
        usageClientFactory = mock(ModeAwareChatClientFactory.class);
        modeAwareExecutionService = mock(ChatExecutionService.class);
        commandMapper = mock(ChatCommandMapper.class);
    }

    private ChatModelRouter.ChatModelCandidate candidate(
            String ref, ChatModel model) {
        return new ChatModelRouter.ChatModelCandidate(
                ref, model, MultiModelProperties.ModelCapabilities.defaults());
    }

    private RagChatService createService(RagProperties ragProperties,
                                         RetryTemplate retryTemplate) {
        return createService(ragProperties, retryTemplate, false);
    }

    private RagChatService createService(RagProperties ragProperties,
                                         RetryTemplate retryTemplate,
                                         boolean useUsageFactory) {
        var queryRewriteAdvisor = mock(QueryRewriteAdvisor.class);
        var hybridSearchAdvisor = mock(HybridSearchAdvisor.class);
        var rerankAdvisor = mock(RerankAdvisor.class);
        for (org.springframework.ai.chat.client.advisor.api.BaseAdvisor advisor :
                List.of(queryRewriteAdvisor, hybridSearchAdvisor, rerankAdvisor)) {
            when(advisor.getName())
                    .thenReturn(advisor.getClass().getSimpleName() + "-mock");
            when(advisor.adviseCall(any(), any())).thenAnswer(invocation -> {
                ChatClientRequest request = invocation.getArgument(0);
                CallAdvisorChain chain = invocation.getArgument(1);
                return chain.nextCall(request);
            });
        }
        return new RagChatService(
                chatClientBuilder,
                chatModelRouter,
                queryRewriteAdvisor,
                hybridSearchAdvisor,
                rerankAdvisor,
                jdbcChatMemoryRepository,
                mock(RagChatHistoryRepository.class),
                mock(com.springairag.core.extension.DomainExtensionRegistry.class),
                mock(com.springairag.core.extension.PromptCustomizerChain.class),
                ragProperties,
                metricsService,
                null,
                retryTemplate,
                null,
                useUsageFactory ? usageClientFactory : null);
    }

    private ChatRequest chatRequest() {
        ChatRequest request = new ChatRequest("hi", "session-1");
        request.setModel("m");
        return request;
    }

    @Test
    void nullModelResultSurfacesIllegalState() {
        ChatModel emptyModel = mock(ChatModel.class);
        when(emptyModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of()));
        when(chatModelRouter.orderedCandidateDescriptors("m"))
                .thenReturn(List.of(candidate("m-a", emptyModel)));
        RagChatService service = createService(new RagProperties(), null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.chat(chatRequest()));

        assertTrue(error.getMessage().contains("LLM returned null result"));
    }

    @Test
    void retryTemplateRetriesThenSucceeds() {
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(2));
        ChatModel flaky = mock(ChatModel.class);
        AtomicInteger calls = new AtomicInteger();
        when(flaky.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new RuntimeException("transient");
            }
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("recovered"))));
        });
        when(chatModelRouter.orderedCandidateDescriptors("m"))
                .thenReturn(List.of(candidate("m-a", flaky)));
        RagChatService service = createService(new RagProperties(), retryTemplate);

        String answer = service.chat(chatRequest()).getAnswer();

        assertEquals("recovered", answer);
        verify(flaky, times(2)).call(any(Prompt.class));
    }

    @Test
    void metricsServiceRecordsFailureOnModelFailure() {
        ChatModel failing = mock(ChatModel.class);
        when(failing.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("boom-A"));
        when(chatModelRouter.orderedCandidateDescriptors("m"))
                .thenReturn(List.of(candidate("m-a", failing)));
        RagChatService service = createService(new RagProperties(), null);

        assertThrows(RuntimeException.class,
                () -> service.chat(chatRequest()));

        verify(metricsService).recordFailure(anyLong());
    }

    @Test
    void chatEventsWithoutModeAwareErrors() {
        RagChatService service = createService(new RagProperties(), null);

        assertThrows(IllegalStateException.class,
                () -> service.chatEvents(
                                chatRequest(), RetrievalScope.unscoped())
                        .blockLast());
    }

    private ChatCommand command() {
        return new ChatCommand(
                "hi", "session-1", ChatPrincipal.local(), null,
                ChatMode.PLAIN, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void chatEventsModeAwareCompletes() {
        RagChatService service = createService(new RagProperties(), null);
        service.configureModeAwareExecution(
                modeAwareExecutionService, commandMapper);
        when(commandMapper.map(any(ChatRequest.class),
                any(RetrievalScope.class), any(ChatPrincipal.class)))
                .thenReturn(command());
        when(modeAwareExecutionService.stream(any(ChatCommand.class)))
                .thenReturn(Flux.empty());

        service.chatEvents(chatRequest(), RetrievalScope.unscoped())
                .blockLast();

        verify(modeAwareExecutionService).stream(any(ChatCommand.class));
    }

    @Test
    void chatEventsModeAwareErrorPropagates() {
        RagChatService service = createService(new RagProperties(), null);
        service.configureModeAwareExecution(
                modeAwareExecutionService, commandMapper);
        when(commandMapper.map(any(ChatRequest.class),
                any(RetrievalScope.class), any(ChatPrincipal.class)))
                .thenReturn(command());
        when(modeAwareExecutionService.stream(any(ChatCommand.class)))
                .thenReturn(Flux.error(new RagException(
                        ErrorCode.INTERNAL_ERROR, "stream blew up")));

        assertThrows(RagException.class,
                () -> service.chatEvents(chatRequest(), RetrievalScope.unscoped())
                        .blockLast());
        verify(metricsService, never()).recordSuccess(anyLong(), Mockito.anyInt());
    }
}
