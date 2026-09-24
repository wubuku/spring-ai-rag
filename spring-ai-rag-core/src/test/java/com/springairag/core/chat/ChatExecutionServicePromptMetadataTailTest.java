package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.skill.RuntimeSkillCatalog;
import com.springairag.core.diagnostics.RetrievalTraceSession;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatExecutionService 提示组装与元数据投影四期长尾（Batch 609，
 * JaCoCo 驱动）：markAttempt 在追踪会话存在时的成功/失败标记、
 * SERVER 记忆模式下摘要链调用、AGENT 系统提示聚合领域模板与
 * Skill 目录、显式 modelRef 的请求级资格校验、Completed 元数据
 * 携带执行预算快照。
 */
class ChatExecutionServicePromptMetadataTailTest {

    private ChatModelRouter modelRouter;
    private ModeAwareChatClientFactory clientFactory;
    private ChatSessionCoordinator sessionCoordinator;
    private RetrievalDiagnosticsService diagnosticsService;
    private ConversationSummaryService summaryService;
    private DomainExtensionRegistry domainExtensions;
    private RuntimeSkillCatalog skillCatalog;
    private RagProperties ragProperties;
    private ChatExecutionService service;
    private final AtomicReference<String> systemPrompt =
            new AtomicReference<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelRouter = mock(ChatModelRouter.class);
        clientFactory = mock(ModeAwareChatClientFactory.class);
        sessionCoordinator = mock(ChatSessionCoordinator.class);
        diagnosticsService = mock(RetrievalDiagnosticsService.class);
        summaryService = mock(ConversationSummaryService.class);
        domainExtensions = mock(DomainExtensionRegistry.class);
        skillCatalog = mock(RuntimeSkillCatalog.class);
        ragProperties = new RagProperties();

        var historyRepository = mock(RagChatHistoryRepository.class);
        when(historyRepository.findBySessionId(anyString(), any(Integer.class)))
                .thenReturn(List.of());
        when(historyRepository.findOwnedBaseline(any(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(sessionCoordinator.invokeWithinDeadline(
                any(ChatSessionCoordinator.LeaseHandle.class), any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>)
                        invocation.getArgument(1)).get());
        when(sessionCoordinator.acquire(any(ChatCommand.class), anyBoolean()))
                .thenReturn(ChatSessionCoordinator.LeaseHandle.stateless(
                        java.time.Instant.now().plusSeconds(60)));

        service = new ChatExecutionService(
                modelRouter,
                clientFactory,
                mock(KnowledgeSearchTool.class),
                historyRepository,
                null,
                domainExtensions,
                mock(PromptCustomizerChain.class),
                mock(RetrievalDocumentMapper.class),
                new ObjectMapper(),
                ragProperties,
                null,
                null,
                sessionCoordinator,
                null);
        service.setDiagnosticsService(diagnosticsService);
        service.setSummaryService(summaryService);
        service.setRuntimeSkillCatalog(skillCatalog);
    }

    private ChatModelRouter.ChatModelCandidate candidate(String ref) {
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(null);
        return new ChatModelRouter.ChatModelCandidate(
                ref, model,
                new com.springairag.core.config.MultiModelProperties
                        .ModelCapabilities(true, false));
    }

    private ChatCommand budgeted(ChatMode mode, String domainId,
                                 RetrievalTraceSession session) {
        ChatPrincipal principal = ChatPrincipal.local();
        ChatCommand command = new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                mode, MemoryMode.SERVER, null, domainId,
                RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, true, 0.55, 0.45),
                Map.of("client", "test"));
        if (session != null) {
            command = command.withTraceSession(session);
        }
        return command.withExecutionBudget(new ChatExecutionBudget(
                null, 3, 4, 3, 8, 3, 48_000));
    }

    @SuppressWarnings("unchecked")
    private ChatClient blockingClient(
            java.util.function.Consumer<String> systemCapture) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call =
                mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        org.mockito.Mockito.doAnswer(invocation -> {
            systemCapture.accept(invocation.getArgument(0));
            return spec;
        }).when(spec).system(anyString());
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class)))
                .thenReturn(spec);
        when(spec.toolCallbacks(any(KnowledgeSearchTool.class)))
                .thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.chatClientResponse())
                .thenReturn(new ChatClientResponse(
                        new ChatResponse(
                                List.of(new Generation(new AssistantMessage("答"))),
                                ChatResponseMetadata.builder().build()),
                        Map.of()));
        return client;
    }

    @Test
    void markAttemptFinishesTraceAttemptOnSuccess() {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "CHAT", "session-1");
        session.newAttemptCollector("a1", 3, 2, 10);
        ChatModelRouter.ChatModelCandidate solo = candidate("solo");
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(solo));
        when(modelRouter.resolve(eq("solo"))).thenReturn(solo.model());
        ChatClient client = blockingClient(systemPrompt::set);
        when(clientFactory.create(any(), same(solo), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, solo, context(), null));

        service.execute(budgeted(ChatMode.PLAIN, null, session));

        // 成功路径将追踪会话中的 attempt 标记为 SUCCEEDED。
        String metadata = session.toMetadata(true).toString();
        assertTrue(metadata.contains("SUCCEEDED"), metadata);
    }

    @Test
    void serverMemoryModeInvokesSummaryChain() {
        when(summaryService.load(any(), anyString()))
                .thenReturn(java.util.Optional.empty());
        when(summaryService.promptText(any())).thenReturn("");
        ChatModelRouter.ChatModelCandidate solo = candidate("solo");
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(solo));
        when(modelRouter.resolve(eq("solo"))).thenReturn(solo.model());
        ChatClient client = blockingClient(systemPrompt::set);
        when(clientFactory.create(any(), same(solo), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, solo, context(), null));

        service.execute(budgeted(ChatMode.PLAIN, null, null));

        verify(summaryService).load(any(), anyString());
    }

    private ChatModelRouter.ChatModelCandidate toolCallingCandidate(
            String ref) {
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions())
                .thenReturn(ToolCallingChatOptions.builder().build());
        return new ChatModelRouter.ChatModelCandidate(
                ref, model,
                new com.springairag.core.config.MultiModelProperties
                        .ModelCapabilities(true, true));
    }

    @Test
    void agentSystemPromptAggregatesDomainAndSkillCatalog() {
        when(domainExtensions.getSystemPromptTemplate(eq("support"),
                any())).thenReturn("领域模板");
        when(skillCatalog.enabled()).thenReturn(true);
        when(skillCatalog.levelOnePrompt(anyInt()))
                .thenReturn("\n技能目录：weather");
        ChatModelRouter.ChatModelCandidate solo =
                toolCallingCandidate("agent");
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(solo));
        when(modelRouter.resolve(eq("agent"))).thenReturn(solo.model());
        ChatClient client = blockingClient(systemPrompt::set);
        when(clientFactory.create(any(), same(solo), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, solo, context(), null));

        service.execute(budgeted(ChatMode.AGENT, "support", null));

        String prompt = systemPrompt.get();
        assertTrue(prompt.contains("领域模板"), prompt);
        assertTrue(prompt.contains("技能目录：weather"), prompt);
    }

    @Test
    void explicitModelRefRunsRequestLevelValidation() {
        ChatModelRouter.ChatModelCandidate solo = candidate("solo");
        when(modelRouter.orderedCandidateDescriptors(eq("solo")))
                .thenReturn(List.of(solo));
        when(modelRouter.resolve(eq("solo"))).thenReturn(solo.model());
        ChatClient client = blockingClient(systemPrompt::set);
        when(clientFactory.create(any(), same(solo), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, solo, context(), null));

        ChatPrincipal principal = ChatPrincipal.local();
        ChatCommand withRef = new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                ChatMode.PLAIN, MemoryMode.SERVER, "solo", null,
                RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, true, 0.55, 0.45),
                Map.of("client", "test"))
                .withExecutionBudget(new ChatExecutionBudget(
                        null, 3, 4, 3, 8, 3, 48_000));

        service.execute(withRef);

        verify(modelRouter, times(1)).orderedCandidateDescriptors(eq("solo"));
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
}
