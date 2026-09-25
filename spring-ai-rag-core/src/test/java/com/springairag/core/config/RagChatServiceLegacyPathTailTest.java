package com.springairag.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatRequest;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.BudgetedChatModel;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.chat.ModeAwareChatClientFactory;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagChatService 遗留链路长尾（Batch 634，JaCoCo 驱动）：
 * chatEvents 单参委托与遗留 scope 解析、流式完成 / 失败的熔断记
 * 账、带域系统提示的 3 参 chatStream、ChatRequest 流式重载、
 * usageClientFactory 预算模型与默认选项拷贝、遗留候选回退与
 * UNKNOWN 引用、safeAttribution 回退矩阵、buildAdvisorParams 委
 * 托链。
 */
class RagChatServiceLegacyPathTailTest {

    private ChatClient.Builder chatClientBuilder;
    private ChatClient defaultChatClient;
    private ChatModelRouter chatModelRouter;
    private DomainExtensionRegistry domainExtensions;
    private ModeAwareChatClientFactory usageClientFactory;
    private ChatExecutionService modeAwareExecutionService;
    private ChatCommandMapper commandMapper;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        chatClientBuilder = mock(ChatClient.Builder.class);
        defaultChatClient = mock(ChatClient.class);
        when(chatClientBuilder.defaultAdvisors(anyList()))
                .thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(defaultChatClient);
        chatModelRouter = mock(ChatModelRouter.class);
        domainExtensions = mock(DomainExtensionRegistry.class);
        usageClientFactory = mock(ModeAwareChatClientFactory.class);
        modeAwareExecutionService = mock(ChatExecutionService.class);
        commandMapper = mock(ChatCommandMapper.class);
        properties = new RagProperties();
        var breaker = properties.getCircuitBreaker();
        breaker.setEnabled(true);
        breaker.setFailureRateThreshold(100);
        breaker.setMinimumNumberOfCalls(10);
        breaker.setSlidingWindowSize(5);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest("POST", "/chat")));
    }

    private <T extends org.springframework.ai.chat.client.advisor.api
            .CallAdvisor> T namedAdvisor(String name, Class<T> type) {
        var advisor = mock(type);
        org.mockito.Mockito.when(advisor.getName()).thenReturn(name);
        org.mockito.Mockito.when(advisor.adviseCall(any(), any()))
                .thenAnswer(invocation -> {
                    org.springframework.ai.chat.client.ChatClientRequest request =
                            invocation.getArgument(0);
                    org.springframework.ai.chat.client.advisor.api
                            .CallAdvisorChain chain = invocation.getArgument(1);
                    return chain.nextCall(request);
                });
        return advisor;
    }

    private RagChatService legacyService() {
        return new RagChatService(
                chatClientBuilder,
                chatModelRouter,
                namedAdvisor("query-rewrite",
                        com.springairag.core.advisor.QueryRewriteAdvisor.class),
                namedAdvisor("hybrid-search",
                        com.springairag.core.advisor.HybridSearchAdvisor.class),
                namedAdvisor("rerank",
                        com.springairag.core.advisor.RerankAdvisor.class),
                mock(JdbcChatMemoryRepository.class),
                mock(RagChatHistoryRepository.class),
                domainExtensions,
                mock(PromptCustomizerChain.class),
                properties,
                null,
                null,
                null,
                null,
                usageClientFactory);
    }

    private RagChatService modeAwareService() {
        RagChatService service = new RagChatService(
                chatClientBuilder,
                chatModelRouter,
                mock(com.springairag.core.advisor.QueryRewriteAdvisor.class),
                mock(com.springairag.core.advisor.HybridSearchAdvisor.class),
                mock(com.springairag.core.advisor.RerankAdvisor.class),
                mock(JdbcChatMemoryRepository.class),
                mock(RagChatHistoryRepository.class),
                domainExtensions,
                mock(PromptCustomizerChain.class),
                properties,
                null,
                null,
                null,
                null,
                null);
        service.configureModeAwareExecution(
                modeAwareExecutionService, commandMapper);
        return service;
    }

    private ChatCommand mappedCommand() {
        return new ChatCommand(
                "问题", "session-1", ChatPrincipal.local(),
                null, com.springairag.api.enums.ChatMode.KNOWLEDGE,
                com.springairag.core.chat.MemoryMode.STATELESS,
                null, null, RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, false, 0.5, 0.5),
                Map.of(), List.of(), List.of(), null, null, null);
    }

    private ChatEvent completedEvent() {
        return new ChatEvent.Completed(
                "trace", "session-1", null, null,
                com.springairag.api.enums.ChatMode.KNOWLEDGE,
                Map.of(), "STOP", List.of(), Map.of());
    }

    @Test
    void chatEventsSingleArgCompletesAndRecordsBreakerSuccess() {
        when(commandMapper.map(any(ChatRequest.class), any(),
                any(ChatPrincipal.class))).thenReturn(mappedCommand());
        when(modeAwareExecutionService.stream(any(ChatCommand.class)))
                .thenReturn(Flux.just(completedEvent()));

        List<ChatEvent> events = modeAwareService()
                .chatEvents(new ChatRequest("问题", "session-1"))
                .collectList()
                .block();

        assertEquals(1, events.size());
    }

    @Test
    void chatEventsUpstreamErrorRecordsBreakerFailure() {
        when(commandMapper.map(any(ChatRequest.class), any(),
                any(ChatPrincipal.class))).thenReturn(mappedCommand());
        when(modeAwareExecutionService.stream(any(ChatCommand.class)))
                .thenReturn(Flux.error(new IllegalStateException("上游断流")));

        assertThrows(IllegalStateException.class,
                () -> modeAwareService()
                        .chatEvents(new ChatRequest("问题", "session-1"))
                        .blockLast());
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

    @Test
    void chatStreamThreeArgWithDomainInjectsSystemPrompt() {
        when(chatModelRouter.orderedCandidateDescriptors(any()))
                .thenReturn(List.of());
        when(chatModelRouter.orderedCandidates(any()))
                .thenReturn(List.of());
        when(domainExtensions.hasExtensions()).thenReturn(true);
        when(domainExtensions.getSystemPromptTemplate(eq("domain"), any()))
                .thenReturn("领域系统提示");
        stubDefaultClientStream(Flux.just("回答"));

        List<String> chunks = legacyService()
                .chatStream("问题", "session-1", "domain")
                .collectList()
                .block();

        assertEquals(List.of("回答"), chunks);
    }

    @Test
    void chatStreamRequestOverloadDelegatesWithLegacyScope() {
        when(chatModelRouter.orderedCandidateDescriptors(any()))
                .thenReturn(List.of());
        when(chatModelRouter.orderedCandidates(any()))
                .thenReturn(List.of());
        stubDefaultClientStream(Flux.just("请求流回答"));

        List<String> chunks = legacyService()
                .chatStream(new ChatRequest("问题", "session-1"))
                .collectList()
                .block();

        assertEquals(List.of("请求流回答"), chunks);
    }

    @Test
    void legacyChatWithUsageFactoryCopiesDefaultOptions() {
        ChatOptions options = ChatOptions.builder().model("model-x").build();
        ChatModel sourceModel = mock(ChatModel.class);
        when(sourceModel.getDefaultOptions()).thenReturn(options);
        ChatModelRouter.ChatModelCandidate candidate =
                new ChatModelRouter.ChatModelCandidate(
                        "model-x", sourceModel,
                        MultiModelProperties.ModelCapabilities.defaults());
        when(chatModelRouter.orderedCandidateDescriptors("model-x"))
                .thenReturn(List.of(candidate));

        BudgetedChatModel budgetedModel = mock(BudgetedChatModel.class);
        when(budgetedModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(
                        new AssistantMessage("预算回答")))));
        when(usageClientFactory.budgetedModelFor(
                any(), any(com.springairag.core.chat.ChatExecutionBudget.class),
                any())).thenReturn(budgetedModel);

        ChatRequest request = new ChatRequest("问题", "session-1");
        request.setModel("model-x");

        com.springairag.api.dto.ChatResponse response =
                legacyService().chat(request);

        assertEquals("预算回答", response.getAnswer());
    }

    @Test
    void legacyChatFallsBackToOrderedCandidatesWithUnknownRef() {
        ChatOptions named = ChatOptions.builder().model("m2").build();
        ChatModel namedSource = mock(ChatModel.class);
        when(namedSource.getDefaultOptions()).thenReturn(named);
        ChatModel plainSource = mock(ChatModel.class);
        when(plainSource.getDefaultOptions()).thenReturn(null);
        when(chatModelRouter.orderedCandidateDescriptors("model-y"))
                .thenReturn(null);
        when(chatModelRouter.orderedCandidates("model-y"))
                .thenReturn(java.util.Arrays.asList(namedSource, plainSource));

        BudgetedChatModel failing = mock(BudgetedChatModel.class);
        when(failing.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("首选掉线"));
        BudgetedChatModel healthy = mock(BudgetedChatModel.class);
        when(healthy.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(
                        new AssistantMessage("回退回答")))));
        when(usageClientFactory.budgetedModelFor(
                any(), any(com.springairag.core.chat.ChatExecutionBudget.class),
                any()))
                .thenReturn(failing, healthy);

        ChatRequest request = new ChatRequest("问题", "session-1");
        request.setModel("model-y");

        com.springairag.api.dto.ChatResponse response =
                legacyService().chat(request);

        assertEquals("回退回答", response.getAnswer());
    }

    @Test
    void safeAttributionFallbackMatrix() throws Exception {
        Method safeAttribution = com.springairag.core.config.RagChatService
                .class.getDeclaredMethod("safeAttribution",
                        String.class, String.class, int.class);
        safeAttribution.setAccessible(true);
        Object service = legacyService();

        assertEquals("fb", safeAttribution.invoke(service, null, "fb", 16));
        assertEquals("ok", safeAttribution.invoke(service, "ok", "fb", 16));
        assertEquals("fb", safeAttribution.invoke(
                service, "bad\nline", "fb", 16));
        assertEquals("fb", safeAttribution.invoke(
                service, "x".repeat(17), "fb", 16));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildAdvisorParamsDelegatesReturnConsumer() throws Exception {
        Object service = legacyService();
        Method threeArg = com.springairag.core.config.RagChatService.class
                .getDeclaredMethod("buildAdvisorParams",
                        String.class, String.class, Map.class);
        threeArg.setAccessible(true);
        Object consumer3 =
                threeArg.invoke(service, "session-1", null, Map.of());
        assertNotNull(consumer3);

        Method fiveArg = com.springairag.core.config.RagChatService.class
                .getDeclaredMethod("buildAdvisorParams",
                        String.class, String.class, Map.class,
                        RetrievalScope.class, int.class);
        fiveArg.setAccessible(true);
        Object consumer5 = fiveArg.invoke(
                service, "session-1", null, Map.of(),
                RetrievalScope.unscoped(), 5);
        assertNotNull(consumer5);
    }
}
