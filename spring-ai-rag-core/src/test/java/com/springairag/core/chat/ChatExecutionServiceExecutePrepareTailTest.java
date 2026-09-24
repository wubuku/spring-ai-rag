package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.evaluation.CitationValidator;
import com.springairag.core.exception.RagException;
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
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatExecutionService 执行 / 准备 / 流式深水区长尾（Batch 631，JaCoCo 驱动）：
 * 兼容构造器委托、AGENT 关闭校验、无候选时的 LLM_UNAVAILABLE、
 * RagException 直接重抛与 RuntimeException 兜底、候选预算耗尽、
 * 指标成败记录、prepareForOperation 有 / 无租约与记忆投影、
 * 流式候选失败回退、空流无可用响应错误、多角色输入消息组装、
 * 引用校验挂载与部分用量统计。
 */
class ChatExecutionServiceExecutePrepareTailTest {

    private ChatModelRouter modelRouter;
    private ModeAwareChatClientFactory clientFactory;
    private ChatSessionCoordinator sessionCoordinator;
    private ConversationSummaryService summaryService;
    private RetrievalDiagnosticsService diagnosticsService;
    private com.springairag.core.metrics.RagMetricsService metricsService;
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
        diagnosticsService = mock(RetrievalDiagnosticsService.class);
        metricsService = mock(com.springairag.core.metrics.RagMetricsService.class);
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
                metricsService,
                null,
                sessionCoordinator,
                null);
        service.setDiagnosticsService(diagnosticsService);
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
                Map.of("client", "test"));
    }

    private ChatCommand commandWithInputMessages() {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "新问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                ChatMode.PLAIN, MemoryMode.SERVER, null, null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of(),
                List.of(new ChatInputMessage(ChatInputMessage.Role.USER, "旧问题"),
                        new ChatInputMessage(ChatInputMessage.Role.ASSISTANT, "旧回答"),
                        new ChatInputMessage(ChatInputMessage.Role.SYSTEM, "系统补充"),
                        new ChatInputMessage(ChatInputMessage.Role.DEVELOPER, "开发者指引"),
                        new ChatInputMessage(ChatInputMessage.Role.USER, "新问题")),
                List.of());
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

    private ChatClientResponse response(String content, Usage usage) {
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
        if (usage != null) {
            metadata.usage(usage);
        }
        return new ChatClientResponse(
                new ChatResponse(
                        List.of(new Generation(
                                new AssistantMessage(content),
                                ChatGenerationMetadata.builder().finishReason("STOP").build())),
                        metadata.build()),
                Map.of());
    }

    @SuppressWarnings("unchecked")
    private ChatClient blockingClient(String content) {
        return blockingClient(response(content, null));
    }

    @SuppressWarnings("unchecked")
    private ChatClient blockingClient(ChatClientResponse prepared) {
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
        when(call.chatClientResponse()).thenReturn(prepared);
        return client;
    }

    @SuppressWarnings("unchecked")
    private ChatClient brokenClient(RuntimeException failure) {
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
        when(spec.call()).thenAnswer(invocation -> {
            throw failure;
        });
        return client;
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

    private void stubCandidates(
            List<ChatModelRouter.ChatModelCandidate> candidates) {
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(candidates);
        for (ChatModelRouter.ChatModelCandidate candidate : candidates) {
            when(modelRouter.resolve(eq(candidate.ref()))).thenReturn(candidate.model());
        }
    }


    private void stubSingleCandidateFlow(
            ChatModelRouter.ChatModelCandidate candidate,
            ChatClient client) {
        stubCandidates(List.of(candidate));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, candidate, context(), null));
    }

    @Test
    void convenienceConstructorDelegatesAndExecutesPlainTurn() {
        var historyRepository = mock(RagChatHistoryRepository.class);
        when(historyRepository.findBySessionId(anyString(), any(Integer.class)))
                .thenReturn(List.of());
        ChatExecutionService convenience = new ChatExecutionService(
                modelRouter,
                clientFactory,
                mock(KnowledgeSearchTool.class),
                historyRepository,
                mock(com.springairag.core.rag.JsonRecordSearchTool.class),
                mock(DomainExtensionRegistry.class),
                mock(PromptCustomizerChain.class),
                mock(RetrievalDocumentMapper.class),
                new ObjectMapper(),
                ragProperties,
                null,
                null,
                sessionCoordinator);
        stubSingleCandidateFlow(candidate("solo", true),
                blockingClient("兼容构造器回答"));

        ChatExecutionResult result = convenience.execute(baseCommand(ChatMode.PLAIN));

        assertEquals("兼容构造器回答", result.answer());
    }

    @Test
    void agentModeDisabledRejectedBeforeLeaseAcquisition() {
        ragProperties.getChat().getAgent().setEnabled(false);

        RagException error = assertThrows(RagException.class,
                () -> service.execute(baseCommand(ChatMode.AGENT)));

        assertEquals(ErrorCode.CHAT_AGENT_DISABLED, error.getErrorCodeEnum());
        verify(sessionCoordinator, never())
                .acquire(any(ChatCommand.class), anyBoolean());
    }

    @Test
    void noEligibleCandidatesRejectedByCapabilityGuard() {
        stubCandidates(List.of());

        RagException error = assertThrows(RagException.class,
                () -> service.execute(baseCommand(ChatMode.PLAIN)));

        assertEquals(ErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                error.getErrorCodeEnum());
        verify(clientFactory, never()).create(any(), any(), anyList());
    }

    @Test
    void ragExceptionFromCandidateRethrownWithoutFallback() {
        stubSingleCandidateFlow(candidate("solo", true),
                brokenClient(new RagException(
                        ErrorCode.LLM_UNAVAILABLE, "上游失败")));

        RagException error = assertThrows(RagException.class,
                () -> service.execute(baseCommand(ChatMode.PLAIN)));

        assertEquals(ErrorCode.LLM_UNAVAILABLE, error.getErrorCodeEnum());
        verify(clientFactory, times(1)).create(any(), any(), anyList());
    }

    @Test
    void runtimeExceptionRecordedAndPropagatedAsLastFailure() {
        stubSingleCandidateFlow(candidate("solo", true),
                brokenClient(new IllegalStateException("模型崩溃")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.execute(baseCommand(ChatMode.PLAIN)));

        assertEquals("模型崩溃", error.getMessage());
        verify(metricsService).recordFailure(anyLong());
        verify(metricsService, never()).recordSuccess(anyLong(), anyInt());
    }

    @Test
    void candidateBudgetExhaustedStopsLaterCandidates() {
        ragProperties.getChat().getExecution().setMaxCandidateAttempts(1);
        ChatModelRouter.ChatModelCandidate first = candidate("first", true);
        ModeAwareChatClientFactory.Attempt firstAttempt =
                new ModeAwareChatClientFactory.Attempt(
                        brokenClient(new IllegalStateException("首个候选失败")),
                        first, context(), null);
        stubCandidates(List.of(first, candidate("second", true)));
        when(clientFactory.create(any(), same(first), anyList()))
                .thenReturn(firstAttempt);

        RagException error = assertThrows(RagException.class,
                () -> service.execute(baseCommand(ChatMode.PLAIN)));

        assertEquals(ErrorCode.CHAT_BUDGET_EXHAUSTED, error.getErrorCodeEnum());
        verify(clientFactory, times(1)).create(any(), any(), anyList());
    }

    @Test
    void fallbackSecondCandidateRecordsSuccessMetrics() {
        ChatModelRouter.ChatModelCandidate broken = candidate("broken", true);
        ChatModelRouter.ChatModelCandidate healthy = candidate("healthy", true);
        ModeAwareChatClientFactory.Attempt brokenAttempt =
                new ModeAwareChatClientFactory.Attempt(
                        brokenClient(new IllegalStateException("掉线")),
                        broken, context(), null);
        ModeAwareChatClientFactory.Attempt healthyAttempt =
                new ModeAwareChatClientFactory.Attempt(
                        blockingClient("回退成功回答"), healthy, context(), null);
        stubCandidates(List.of(broken, healthy));
        when(clientFactory.create(any(), same(broken), anyList()))
                .thenReturn(brokenAttempt);
        when(clientFactory.create(any(), same(healthy), anyList()))
                .thenReturn(healthyAttempt);

        ChatExecutionResult result = service.execute(baseCommand(ChatMode.PLAIN));

        assertEquals("回退成功回答", result.answer());
        verify(metricsService).recordSuccess(anyLong(), anyInt());
        verify(metricsService).recordFailure(anyLong());
    }

    @Test
    void prepareWithoutLeaseStillBuildsPreparedExecution() {
        stubSingleCandidateFlow(candidate("solo", true),
                blockingClient("预备回答"));

        ChatExecutionService.PreparedExecution prepared =
                service.prepareForOperation(baseCommand(ChatMode.PLAIN), null, false);

        assertEquals("预备回答", prepared.result().answer());
        assertTrue(prepared.committedMessages().isEmpty());
        verify(sessionCoordinator, never())
                .invokeWithinDeadline(any(), any());
    }

    @Test
    void prepareWithMemoryProjectsCommittedMessages() {
        stubCandidates(List.of(candidate("solo", true)));
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get(anyString()))
                .thenReturn(List.of(new UserMessage("历史消息")));
        when(clientFactory.create(any(), any(), anyList()))
                .thenAnswer(invocation -> {
                    ChatModelRouter.ChatModelCandidate cand =
                            invocation.getArgument(1);
                    return new ModeAwareChatClientFactory.Attempt(
                            blockingClient("带记忆回答"), cand, context(), memory);
                });

        ChatExecutionService.PreparedExecution prepared =
                service.prepareForOperation(
                        baseCommand(ChatMode.PLAIN),
                        ChatSessionCoordinator.LeaseHandle.stateless(
                                Instant.now().plusSeconds(60)),
                        false);

        assertEquals("带记忆回答", prepared.result().answer());
        assertEquals(1, prepared.committedMessages().size());
        assertEquals("历史消息",
                ((UserMessage) prepared.committedMessages().getFirst()).getText());
    }

    @Test
    void prepareAllCandidatesFailPropagatesLastFailure() {
        stubCandidates(List.of(candidate("solo", true)));
        ModeAwareChatClientFactory.Attempt failing =
                new ModeAwareChatClientFactory.Attempt(
                        brokenClient(new IllegalStateException("持久候选失败")),
                        candidate("solo", true), context(), null);
        when(clientFactory.create(any(), any(), anyList()))
                .thenReturn(failing);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.prepareForOperation(
                        baseCommand(ChatMode.PLAIN), null, false));

        assertEquals("持久候选失败", error.getMessage());
    }

    @Test
    void prepareNoCandidatesRejectedByCapabilityGuard() {
        stubCandidates(List.of());

        RagException error = assertThrows(RagException.class,
                () -> service.prepareForOperation(
                        baseCommand(ChatMode.PLAIN), null, false));

        assertEquals(ErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                error.getErrorCodeEnum());
    }

    @Test
    void streamCandidateResolutionFailureReleasesLease() {
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenThrow(new IllegalStateException("路由不可用"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.stream(baseCommand(ChatMode.PLAIN)).blockFirst());

        assertEquals("路由不可用", error.getMessage());
        verify(sessionCoordinator).release(any());
    }

    @Test
    void streamSingleCandidateEmptyResponseStillCompletesTurn() {
        stubSingleCandidateFlow(candidate("solo", true),
                streamingClient(Flux.<ChatClientResponse>empty()));

        List<ChatEvent> events = service.stream(baseCommand(ChatMode.PLAIN))
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        // 聚合器对空流合成兜底响应，回合照常提交（ Completed 事件收尾）。
        assertTrue(events.stream().anyMatch(
                event -> event instanceof ChatEvent.Completed));
    }

    @Test
    void streamFirstCandidateErrorFallsBackToSecond() {
        ChatModelRouter.ChatModelCandidate broken = candidate("broken", true);
        ChatModelRouter.ChatModelCandidate healthy = candidate("healthy", true);
        ModeAwareChatClientFactory.Attempt brokenAttempt =
                new ModeAwareChatClientFactory.Attempt(
                        streamingClient(Flux.error(
                                new IllegalStateException("流式掉线"))),
                        broken, context(), null);
        ModeAwareChatClientFactory.Attempt healthyAttempt =
                new ModeAwareChatClientFactory.Attempt(
                        streamingClient(Flux.just(response("回退流式回答", null))),
                        healthy, context(), null);
        stubCandidates(List.of(broken, healthy));
        when(clientFactory.create(any(), same(broken), anyList()))
                .thenReturn(brokenAttempt);
        when(clientFactory.create(any(), same(healthy), anyList()))
                .thenReturn(healthyAttempt);

        List<ChatEvent> events = service.stream(baseCommand(ChatMode.PLAIN))
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        assertTrue(events.stream().anyMatch(event ->
                event instanceof ChatEvent.ContentDelta delta
                        && delta.content().contains("回退流式回答")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiRoleInputMessagesAssembledIntoConversation() {
        stubCandidates(List.of(candidate("solo", true)));
        ChatClient client = blockingClient("多角色回答");
        ChatClient.ChatClientRequestSpec spec = client.prompt();
        ChatModelRouter.ChatModelCandidate solo = candidate("solo", true);
        stubCandidates(List.of(solo));
        ModeAwareChatClientFactory.Attempt attempt =
                new ModeAwareChatClientFactory.Attempt(
                        client, solo, context(), null);
        when(clientFactory.create(any(), any(), anyList()))
                .thenReturn(attempt);

        ChatExecutionResult result =
                service.execute(commandWithInputMessages());

        assertEquals("多角色回答", result.answer());
        ArgumentCaptor<List<Message>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(spec).messages(captor.capture());
        List<Message> conversation = captor.getValue();
        SystemMessage system = (SystemMessage) conversation.getFirst();
        assertTrue(system.getText().contains("[client system]"));
        assertTrue(system.getText().contains("[client developer]"));
        assertTrue(conversation.stream().anyMatch(message ->
                message instanceof UserMessage user
                        && "新问题".equals(user.getText())));
        assertTrue(conversation.stream().anyMatch(message ->
                message instanceof AssistantMessage assistant
                        && "旧回答".equals(assistant.getText())));
    }

    @Test
    void citationValidationAttachedWhenEnabledWithTraceSession() {
        ragProperties.getEvaluation().setCitationValidationEnabled(true);
        service.setCitationValidator(new CitationValidator());
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "CHAT", "session-1");
        stubSingleCandidateFlow(candidate("solo", true),
                blockingClient("依据 [S1] 的回答"));

        ChatExecutionResult result = service.execute(
                baseCommand(ChatMode.KNOWLEDGE).withTraceSession(session));

        assertTrue(result.metadata().containsKey("citationValidation"));
        assertTrue(!session.citationValidation().isEmpty());
        verify(diagnosticsService).persist(session);
    }

    @Test
    void usageProjectionKeepsOnlyProvidedTokenCounts() {
        Usage partialUsage = new Usage() {
            @Override
            public Integer getPromptTokens() {
                return null;
            }

            @Override
            public Integer getCompletionTokens() {
                return null;
            }

            @Override
            public Integer getTotalTokens() {
                return 30;
            }

            @Override
            public Object getNativeUsage() {
                return Map.of();
            }
        };
        stubSingleCandidateFlow(candidate("solo", true),
                blockingClient(response("部分用量回答", partialUsage)));

        ChatExecutionResult result = service.execute(baseCommand(ChatMode.PLAIN));

        assertEquals(Map.of("totalTokens", 30), result.usage());
    }
}
