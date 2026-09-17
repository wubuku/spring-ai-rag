package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.AdvisorScope;
import com.springairag.api.service.RagAdvisorProvider;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.rag.CitationQueryAugmenter;
import com.springairag.core.rag.ProjectDocumentRetriever;
import com.springairag.core.rag.ProjectRerankPostProcessor;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;


import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ModeAwareChatClientFactory 自定义 Advisor provider 校验矩阵与
 * 预算包装长尾（Batch 493，JaCoCo 驱动）：空白名称/null 支持
 * 模式/null 作用域三拒绝、provider 返回 null advisor 忽略、合法
 * provider 进入链、超量 provider 拒绝、预算存在时 chat/query
 * transform/query expand 三处 budgetedModelFor 包装。
 */
class ModeAwareChatClientFactoryAdvisorsTailTest {

    private RagProperties properties;
    private ProjectDocumentRetriever documentRetriever;
    private ProjectRerankPostProcessor rerankPostProcessor;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getChat().getKnowledge().setQueryTransformer("none");
        documentRetriever = mock(ProjectDocumentRetriever.class);
        when(documentRetriever.retrieve(any())).thenReturn(List.of());
        rerankPostProcessor = mock(ProjectRerankPostProcessor.class);
        when(rerankPostProcessor.process(any(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private ModeAwareChatClientFactory factory(
            List<RagAdvisorProvider> providers) {
        return new ModeAwareChatClientFactory(
                documentRetriever,
                rerankPostProcessor,
                new CitationQueryAugmenter(properties),
                properties,
                providers,
                mock(org.springframework.ai.model.tool.ToolCallingManager.class));
    }

    private RagAdvisorProvider provider(String name,
                                        Set<ChatMode> modes,
                                        AdvisorScope scope,
                                        BaseAdvisor advisor) {
        return new RagAdvisorProvider() {
            @Override public String getName() { return name; }
            @Override public int getOrder() { return 0; }
            @Override public BaseAdvisor createAdvisor() { return advisor; }
            @Override public Set<ChatMode> supportedModes() { return modes; }
            @Override public AdvisorScope advisorScope() { return scope; }
        };
    }

    private BaseAdvisor passthroughAdvisor() {
        return new BaseAdvisor() {
            @Override public ChatClientRequest before(
                    ChatClientRequest request, AdvisorChain chain) {
                return request;
            }
            @Override public ChatClientResponse after(
                    ChatClientResponse response, AdvisorChain chain) {
                return response;
            }
            @Override public int getOrder() { return 0; }
        };
    }

    private ChatCommand command(ChatMode mode) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", "session-factory", principal,
                principal.memoryConversationId("session-factory"),
                mode, MemoryMode.STATELESS, null, null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of());
    }

    private ChatModelRouter.ChatModelCandidate candidate() {
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(
                ChatOptions.builder().model("test-model").build());
        return new ChatModelRouter.ChatModelCandidate(
                "knowledge", model,
                new MultiModelProperties.ModelCapabilities(true, false));
    }

    private ChatExecutionBudget budget() {
        return new ChatExecutionBudget(
                Instant.now().plusSeconds(60), 3, 10, 4, 20, 5, 100_000);
    }

    // ── provider 校验矩阵 ─────────────────────────────────────────

    @Test
    void blankProviderNameIsRejected() {
        ModeAwareChatClientFactory configured = factory(List.of(
                provider("  ", Set.of(ChatMode.KNOWLEDGE),
                        AdvisorScope.ATTEMPT, passthroughAdvisor())));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> configured.create(command(ChatMode.KNOWLEDGE),
                        candidate(), List.of()));
        assertTrue(error.getMessage().contains("name must not be blank"),
                "应报名称空白: " + error.getMessage());
    }

    @Test
    void nullSupportedModesIsRejected() {
        ModeAwareChatClientFactory configured = factory(List.of(
                provider("p1", null, AdvisorScope.ATTEMPT,
                        passthroughAdvisor())));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> configured.create(command(ChatMode.KNOWLEDGE),
                        candidate(), List.of()));
        assertTrue(error.getMessage().contains("null supportedModes"));
    }

    @Test
    void nullAdvisorScopeIsRejected() {
        ModeAwareChatClientFactory configured = factory(List.of(
                provider("p1", Set.of(ChatMode.KNOWLEDGE), null,
                        passthroughAdvisor())));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> configured.create(command(ChatMode.KNOWLEDGE),
                        candidate(), List.of()));
        assertTrue(error.getMessage().contains("null advisorScope"));
    }

    @Test
    void providerReturningNullAdvisorIsIgnored() {
        ModeAwareChatClientFactory configured = factory(List.of(
                provider("p1", Set.of(ChatMode.KNOWLEDGE),
                        AdvisorScope.ATTEMPT, null)));

        var attempt = configured.create(command(ChatMode.KNOWLEDGE),
                candidate(), List.of());

        assertNotNull(attempt);
        assertNotNull(attempt.client());
    }

    @Test
    void validProviderAdvisorJoinsChain() {
        java.util.concurrent.atomic.AtomicInteger created =
                new java.util.concurrent.atomic.AtomicInteger();
        BaseAdvisor advisor = passthroughAdvisor();
        RagAdvisorProvider counted = new RagAdvisorProvider() {
            @Override public String getName() { return "p1"; }
            @Override public int getOrder() { return 0; }
            @Override public BaseAdvisor createAdvisor() {
                created.incrementAndGet();
                return advisor;
            }
            @Override public Set<ChatMode> supportedModes() {
                return Set.of(ChatMode.KNOWLEDGE);
            }
            @Override public AdvisorScope advisorScope() {
                return AdvisorScope.ATTEMPT;
            }
        };
        ModeAwareChatClientFactory configured = factory(List.of(counted));

        var attempt = configured.create(command(ChatMode.KNOWLEDGE),
                candidate(), List.of());

        assertNotNull(attempt.client());
        assertEquals(1, created.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void tooManyProvidersForModeIsRejected() {
        List<RagAdvisorProvider> providers = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            providers.add(provider("p" + i, Set.of(ChatMode.KNOWLEDGE),
                    AdvisorScope.ATTEMPT, passthroughAdvisor()));
        }
        ModeAwareChatClientFactory configured = factory(providers);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> configured.create(command(ChatMode.KNOWLEDGE),
                        candidate(), List.of()));
        assertTrue(error.getMessage().contains("Too many"),
                "应报超量: " + error.getMessage());
    }

    // ── 预算包装路径 ─────────────────────────────────────────────

    @Test
    void createWithBudgetWrapsChatModel() {
        ModeAwareChatClientFactory configured = factory(List.of());
        ChatCommand commandWithBudget = ChatCommandAccessor.withBudget(
                command(ChatMode.KNOWLEDGE));

        var attempt = configured.create(
                commandWithBudget, candidate(), List.of());

        assertNotNull(attempt.client());
    }

    @Test
    void springAiQueryTransformerAndExpanderBuildWithBudget() {
        properties.getChat().getKnowledge().setQueryTransformer("spring-ai");
        properties.getChat().getKnowledge().setQueryExpanderVariants(2);
        ModeAwareChatClientFactory configured = factory(List.of());
        ChatCommand commandWithBudget = ChatCommandAccessor.withBudget(
                command(ChatMode.KNOWLEDGE));

        var attempt = configured.create(
                commandWithBudget, candidate(), List.of());

        assertNotNull(attempt.client());
    }

    /** 仅测试可见的预算注入助手（同包静态反射到 ChatCommand 拷贝）。 */
    private static final class ChatCommandAccessor {
        private static ChatCommand withBudget(ChatCommand command) {
            ChatExecutionBudget budget = new ChatExecutionBudget(
                    Instant.now().plusSeconds(60), 3, 10, 4, 20, 5, 100_000);
            return new ChatCommand(
                    command.message(),
                    command.sessionId(),
                    command.principal(),
                    command.memoryConversationId(),
                    command.mode(),
                    command.memoryMode(),
                    command.modelRef(),
                    command.domainId(),
                    command.retrievalScope(),
                    command.retrievalOptions(),
                    command.clientMetadata(),
                    command.inputMessages(),
                    command.modelCandidates(),
                    command.retrievalTraceSession(),
                    command.retrievalFilters(),
                    budget);
        }
    }
}
