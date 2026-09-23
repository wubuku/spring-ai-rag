package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.exception.RagException;
import com.springairag.core.skill.RuntimeSkillCatalog;
import com.springairag.core.diagnostics.RetrievalTraceSession;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatExecutionService 编排深水区长尾（Batch 605，JaCoCo 驱动）：
 * resolveCandidateRefs 的模式校验与流式能力过滤、finalizePrepared
 * Operation 的空入参/压缩失败容错/诊断落库、execute 经会话协调器
 * 提交、AGENT 模式下工具注册表 + Skill 会话 + HTTP 工具状态的
 * 上下文装配、可观测性 providerCall 计数。
 */
class ChatExecutionServiceOrchestrationTailTest {

    private ChatModelRouter modelRouter;
    private ModeAwareChatClientFactory clientFactory;
    private ChatSessionCoordinator sessionCoordinator;
    private RagChatToolRegistry toolRegistry;
    private ConversationSummaryService summaryService;
    private RetrievalDiagnosticsService diagnosticsService;
    private ChatObservabilityService observability;
    private RuntimeSkillCatalog skillCatalog;
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
                        java.time.Instant.now().plusSeconds(60)));
        toolRegistry = mock(RagChatToolRegistry.class);
        summaryService = mock(ConversationSummaryService.class);
        diagnosticsService = mock(RetrievalDiagnosticsService.class);
        observability = mock(ChatObservabilityService.class);
        skillCatalog = mock(RuntimeSkillCatalog.class);
        ragProperties = new RagProperties();

        var historyRepository = mock(RagChatHistoryRepository.class);
        when(historyRepository.findBySessionId(anyString(), any(Integer.class)))
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
                toolRegistry);
        service.setDiagnosticsService(diagnosticsService);
        service.setSummaryService(summaryService);
        service.setChatObservability(observability);
        service.setRuntimeSkillCatalog(skillCatalog);
    }

    private ChatCommand command(ChatMode mode) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                mode, MemoryMode.SERVER, null, null,
                RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, true, 0.55, 0.45),
                Map.of("client", "test"));
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

    private ChatClient blockingClient(String content) {
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
        when(call.chatClientResponse()).thenReturn(
                new ChatClientResponse(
                        new ChatResponse(
                                List.of(new Generation(
                                        new AssistantMessage(content))),
                                ChatResponseMetadata.builder().build()),
                        Map.of()));
        return client;
    }

    private AuthorizedRetrievalContext context() {
        return new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, true, 0.55, 0.45),
                new RetrievalTraceCollector(),
                "session-1",
                ChatPrincipal.local());
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
        when(spec.toolCallbacks(any(
                org.springframework.ai.tool.ToolCallback.class),
                any(org.springframework.ai.tool.ToolCallback.class)))
                .thenReturn(spec);
        when(spec.toolCallbacks(anyList())).thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.options(any(ToolCallingChatOptions.class))).thenReturn(spec);
        when(spec.options(any(org.springframework.ai.chat.prompt.ChatOptions.class)))
                .thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.chatClientResponse()).thenReturn(responses);
        return client;
    }

    private ChatClientResponse streamResponse(String content) {
        return new ChatClientResponse(
                new ChatResponse(
                        List.of(new Generation(new AssistantMessage(content))),
                        ChatResponseMetadata.builder().build()),
                Map.of());
    }

    @Test
    void resolveCandidateRefsFiltersByStreamingCapability() {
        ChatModelRouter.ChatModelCandidate streaming =
                candidate("streamer", true);
        ChatModelRouter.ChatModelCandidate noStream =
                candidate("blocker", false);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(streaming, noStream));

        List<String> refs = service.resolveCandidateRefs(
                command(ChatMode.PLAIN), true);

        assertEquals(List.of("streamer"), refs);
    }

    @Test
    void resolveCandidateRefsRejectsUnsupportedMode() {
        RagException error = assertThrows(RagException.class,
                () -> service.resolveCandidateRefs(
                        command(ChatMode.PLAIN), true));
        assertEquals(ErrorCode.MODEL_STREAMING_UNSUPPORTED,
                error.getErrorCodeEnum());
    }

    @Test
    void finalizePreparedOperationToleratesNullAndCompactionFailure() {
        // null 入参直接返回。
        service.finalizePreparedOperation(null);

        // 压缩抛异常 → 吞掉并继续落诊断。
        when(summaryService.compactIfNeeded(any(), any(), any()))
                .thenThrow(new IllegalStateException("compaction down"));
        ModeAwareChatClientFactory.Attempt attempt =
                new ModeAwareChatClientFactory.Attempt(
                        streamingClient(Flux.empty()), candidate("solo", true),
                        context(), null);
        ChatExecutionService.PreparedExecution prepared =
                new ChatExecutionService.PreparedExecution(
                        command(ChatMode.PLAIN), null, List.of(),
                        null, attempt, candidate("solo", true));

        service.finalizePreparedOperation(prepared);

        verify(summaryService).compactIfNeeded(any(), any(), any());
    }

    @Test
    void executeCommitsThroughSessionCoordinator() {
        ChatModelRouter.ChatModelCandidate solo =
                candidate("solo", true);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(solo));
        when(modelRouter.resolve(eq("solo"))).thenReturn(solo.model());
        ChatClient client = blockingClient("答");
        when(clientFactory.create(any(), same(solo), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, solo, context(), null));

        ChatCommand executeCommand = command(ChatMode.PLAIN)
                .withExecutionBudget(new ChatExecutionBudget(
                        null, 3, 3, 3, 6, 3, 48_000));

        ChatExecutionResult result = service.execute(executeCommand);

        assertEquals("答", result.answer());
        verify(sessionCoordinator).commit(
                any(), any(), any(), anyList(), any());
        verify(observability).providerCall();
    }

    @Test
    void agentExecutionAssemblesToolContextWithSkillAndHttpState() {
        ragProperties.getChat().getHttpTools().setEnabled(true);
        when(skillCatalog.enabled()).thenReturn(true);

        ChatModelRouter.ChatModelCandidate solo =
                candidate("agent", true);
        ChatModel toolModel = mock(ChatModel.class);
        when(toolModel.getDefaultOptions())
                .thenReturn(ToolCallingChatOptions.builder().build());
        ChatModelRouter.ChatModelCandidate toolCandidate =
                new ChatModelRouter.ChatModelCandidate(
                        "agent", toolModel,
                        new com.springairag.core.config.MultiModelProperties
                                .ModelCapabilities(true, true));
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(toolCandidate));
        when(modelRouter.resolve(eq("agent"))).thenReturn(toolModel);
        when(toolRegistry.callbacks(any(ChatMode.class), any()))
                .thenReturn(List.of());
        when(toolRegistry.requestContext(any(), any()))
                .thenReturn(Map.of("policyKey", "policyValue"));
        ChatClient client = blockingClient("ok");
        when(clientFactory.create(any(), same(toolCandidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, toolCandidate, context(), null));

        ChatCommand agentCommand = command(ChatMode.AGENT)
                .withExecutionBudget(new ChatExecutionBudget(
                        null, 3, 3, 3, 6, 3, 48_000));

        ChatExecutionResult result = service.execute(agentCommand);

        assertEquals("ok", result.answer());
        // 规划与装配各调用一次注册表。
        verify(toolRegistry, times(2)).callbacks(ChatMode.AGENT, null);
        verify(toolRegistry).requestContext(any(), any());
        verify(observability).providerCall();
    }

    @Test
    void persistOperationDiagnosticsDelegatesWhenSessionPresent() {
        // 无检索追踪会话 → 静默跳过。
        service.persistOperationDiagnostics(command(ChatMode.PLAIN));
        verify(diagnosticsService, org.mockito.Mockito.never())
                .persist(any());

        // 有追踪会话 → 委托持久化。
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "CHAT", "session-1");
        service.persistOperationDiagnostics(
                command(ChatMode.PLAIN).withTraceSession(session));
        verify(diagnosticsService).persist(session);
    }

    @Test
    void streamWithCoordinatorCommitsAndEmitsCompleted() {
        ChatModelRouter.ChatModelCandidate solo = candidate("solo", true);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(solo));
        ChatClient client = streamingClient(Flux.just(streamResponse("流式")));
        when(clientFactory.create(any(), same(solo), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, solo, context(), null));

        List<ChatEvent> events = service.stream(command(ChatMode.PLAIN))
                .collectList().block();

        assertTrue(events != null && events.getLast()
                instanceof ChatEvent.Completed);
        verify(sessionCoordinator).commit(
                any(), any(), any(), anyList(), any());
    }


}
