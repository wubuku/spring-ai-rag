package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.http.HttpToolExecutionState;
import com.springairag.core.rag.JsonRecordSearchTool;
import com.springairag.core.skill.RuntimeSkillCatalog;
import com.springairag.core.skill.RuntimeSkillLoadSession;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AGENT 模式 applyAgentTools 工具装配矩阵（Batch 326）：注册表
 * 工具与请求上下文、搜索工具回退链（禁用 json 工具→仅搜索）、
 * 技能会话/HTTP 预算/执行预算上下文装配、ToolCallingChatOptions
 * 复制。
 */
class ChatExecutionAgentToolsTest {

    private ChatModelRouter modelRouter;
    private ModeAwareChatClientFactory clientFactory;
    private RagChatHistoryRepository historyRepository;
    private KnowledgeSearchTool knowledgeSearchTool;
    private RagChatToolRegistry toolRegistry;
    private JsonRecordSearchTool jsonRecordSearchTool;
    private RagProperties ragProperties;

    @BeforeEach
    void setUp() {
        modelRouter = mock(ChatModelRouter.class);
        clientFactory = mock(ModeAwareChatClientFactory.class);
        knowledgeSearchTool = mock(KnowledgeSearchTool.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        when(historyRepository.findBySessionId(anyString(),
                any(Integer.class))).thenReturn(List.of());
        toolRegistry = mock(RagChatToolRegistry.class);
        jsonRecordSearchTool = mock(JsonRecordSearchTool.class);
        ragProperties = new RagProperties();
    }

    private ChatCommand command(ChatExecutionBudget budget) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                ChatMode.AGENT, MemoryMode.SERVER, null, null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of()).withExecutionBudget(budget);
    }

    private AuthorizedRetrievalContext context(ChatExecutionBudget budget) {
        return new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                new RetrievalTraceCollector(),
                "session-1",
                ChatPrincipal.local(),
                24_000,
                RetrievalFilters.none(),
                budget);
    }

    private ChatModelRouter.ChatModelCandidate candidate() {
        // AGENT + streaming 要求：流式/工具调用能力 + 默认选项为
        // ToolCallingChatOptions。
        ChatModel model = mock(ChatModel.class);
        ToolCallingChatOptions options = mock(ToolCallingChatOptions.class);
        when(model.getDefaultOptions()).thenReturn(options);
        when(options.copy()).thenReturn(options);
        return new ChatModelRouter.ChatModelCandidate(
                "primary",
                model,
                new MultiModelProperties.ModelCapabilities(true, true));
    }

    @SuppressWarnings("unchecked")
    private ChatClient.ChatClientRequestSpec attempt(
            ChatModelRouter.ChatModelCandidate candidate,
            AuthorizedRetrievalContext retrievalContext,
            Flux<ChatClientResponse> flux) {
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
        when(spec.options(any(ToolCallingChatOptions.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.toolCallbacks(any(ToolCallback[].class))).thenReturn(spec);
        when(spec.toolCallbacks(any(java.util.List.class))).thenReturn(spec);
        when(spec.toolCallbacks(any(ToolCallback.class))).thenReturn(spec);
        when(spec.toolContext(any(Map.class))).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.chatClientResponse()).thenReturn(flux);
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, candidate, retrievalContext, null));
        return spec;
    }

    private ChatClientResponse usableResponse() {
        return new ChatClientResponse(
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("ok"))),
                        ChatResponseMetadata.builder().build()),
                Map.of());
    }

    private ChatExecutionService service(
            JsonRecordSearchTool jsonTool,
            RagChatToolRegistry registry) {
        ChatExecutionService executionService = new ChatExecutionService(
                modelRouter,
                clientFactory,
                knowledgeSearchTool,
                historyRepository,
                jsonTool,
                mock(DomainExtensionRegistry.class),
                hasNoCustomizers(),
                mock(RetrievalDocumentMapper.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                ragProperties,
                null,
                null,
                null,
                registry);
        return executionService;
    }

    private PromptCustomizerChain hasNoCustomizers() {
        PromptCustomizerChain chain = mock(PromptCustomizerChain.class);
        when(chain.hasCustomizers()).thenReturn(false);
        return chain;
    }

    private static final Instant DEADLINE = Instant.now().plusSeconds(30);

    @Test
    @SuppressWarnings("unchecked")
    void registryToolsAndAllBudgetContextsAreAttached() {
        ragProperties.getChat().getHttpTools().setEnabled(true);
        when(toolRegistry.callbacks(any(), any()))
                .thenReturn(List.of(mock(ToolCallback.class)));
        when(toolRegistry.requestContext(any(), any()))
                .thenReturn(Map.of("registry-entry", "v"));
        ChatExecutionBudget budget = new ChatExecutionBudget(
                DEADLINE, 4, 8, 2, 4, 2, 20_000);
        ChatCommand command = command(budget);
        ChatClient.ChatClientRequestSpec spec = attempt(
                candidate(), context(budget),
                Flux.just(usableResponse()));

        List<ChatEvent> events = service(null, toolRegistry)
                .stream(command)
                .collectList()
                .block();

        assertNotNull(events);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contexts =
                ArgumentCaptor.forClass(Map.class);
        verify(spec).toolContext(contexts.capture());
        Map<String, Object> context = contexts.getValue();
        assertTrue(context.containsKey(KnowledgeSearchTool.CONTEXT_KEY));
        assertTrue(context.containsKey(HttpToolExecutionState.CONTEXT_KEY));
        assertTrue(context.containsKey(ChatExecutionBudget.CONTEXT_KEY));
        assertEquals("v", context.get("registry-entry"));
        // 注册表工具列表被装配（List 重载）。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolCallback>> registryTools =
                ArgumentCaptor.forClass((Class<List<ToolCallback>>)
                        (Class) List.class);
        verify(spec).toolCallbacks(registryTools.capture());
        assertEquals(1, registryTools.getValue().size());
        verify(toolRegistry).requestContext(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void skillSessionAttachedWhenCatalogEnabled() {
        ragProperties.getChat().getHttpTools().setEnabled(true);
        ChatExecutionService executionService = service(null, null);
        RuntimeSkillCatalog catalog = mock(RuntimeSkillCatalog.class);
        when(catalog.enabled()).thenReturn(true);
        executionService.setRuntimeSkillCatalog(catalog);
        ChatCommand command = command(null);
        ChatClient.ChatClientRequestSpec spec = attempt(
                candidate(), context(null), Flux.just(usableResponse()));

        executionService.stream(command).collectList().block();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contexts =
                ArgumentCaptor.forClass(Map.class);
        verify(spec).toolContext(contexts.capture());
        Map<String, Object> context = contexts.getValue();
        // 无截止预算命令：HTTP 状态来自全新分配；技能会话已装配。
        assertTrue(context.containsKey(HttpToolExecutionState.CONTEXT_KEY));
        assertTrue(context.containsKey(RuntimeSkillLoadSession.CONTEXT_KEY));
        // 检索上下文无执行预算 → 不放执行预算键。
        assertNull(context.get(ChatExecutionBudget.CONTEXT_KEY));
        assertTrue(context.get(HttpToolExecutionState.CONTEXT_KEY)
                instanceof HttpToolExecutionState);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutRegistryFallsBackToSearchTools() {
        when(jsonRecordSearchTool.isEnabled()).thenReturn(true);
        ChatClient.ChatClientRequestSpec spec = attempt(
                candidate(), context(null), Flux.just(usableResponse()));

        service(jsonRecordSearchTool, null).stream(command(null))
                .collectList().block();

        ArgumentCaptor<ToolCallback[]> callbacks =
                ArgumentCaptor.forClass(ToolCallback[].class);
        verify(spec).toolCallbacks(callbacks.capture());
        assertEquals(2, callbacks.getValue().length);
        assertEquals(knowledgeSearchTool, callbacks.getValue()[0]);
        assertEquals(jsonRecordSearchTool, callbacks.getValue()[1]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void disabledJsonToolLimitsToolsToSearchOnly() {
        when(jsonRecordSearchTool.isEnabled()).thenReturn(false);
        ChatClient.ChatClientRequestSpec spec = attempt(
                candidate(), context(null), Flux.just(usableResponse()));

        service(jsonRecordSearchTool, null).stream(command(null))
                .collectList().block();

        ArgumentCaptor<ToolCallback[]> callbacks =
                ArgumentCaptor.forClass(ToolCallback[].class);
        verify(spec).toolCallbacks(callbacks.capture());
        assertEquals(1, callbacks.getValue().length);
        assertEquals(knowledgeSearchTool, callbacks.getValue()[0]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolCallingOptionsAreCopiedForAgentCandidate() {
        ChatModel model = mock(ChatModel.class);
        ToolCallingChatOptions options = mock(ToolCallingChatOptions.class);
        when(model.getDefaultOptions()).thenReturn(options);
        when(options.copy()).thenReturn(options);
        ChatModelRouter.ChatModelCandidate candidate =
                new ChatModelRouter.ChatModelCandidate(
                        "primary",
                        model,
                        new MultiModelProperties.ModelCapabilities(true, false));
        ChatClient.ChatClientRequestSpec spec = attempt(
                candidate(), context(null), Flux.just(usableResponse()));

        service(null, null).stream(command(null)).collectList().block();

        verify(spec, times(1)).options(any(ToolCallingChatOptions.class));
    }
}
