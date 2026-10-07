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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        // 计数器证明 provider 真的被问过，而不是压根没进这条分支。
        // 原来的两条 assertNotNull(attempt/client) 只说明 create() 正常返回。
        //
        // 说清楚这条**验到**什么、**没验**什么（Batch 955 反向探针实测）：
        // 验到的是"provider 被调用恰好一次，且返回 null 之后 create() 仍然成功"。
        // 没验到的是"null 被显式跳过了"——把生产侧那段 `if (advisor == null) continue;`
        // 整个删掉，这条**照样绿**：OrderedAdvisorAdapter 包一层 null 之后
        // ChatClient 依然建得起来。也就是说"跳过"与"包一层"在 Attempt 这道
        // 缝上输出相同，这部分不可观测，不假装覆盖。
        java.util.concurrent.atomic.AtomicInteger consulted =
                new java.util.concurrent.atomic.AtomicInteger();
        RagAdvisorProvider nullAdvisorProvider = new RagAdvisorProvider() {
            @Override public String getName() { return "p1"; }
            @Override public int getOrder() { return 0; }
            @Override public BaseAdvisor createAdvisor() {
                consulted.incrementAndGet();
                return null;
            }
            @Override public Set<ChatMode> supportedModes() {
                return Set.of(ChatMode.KNOWLEDGE);
            }
            @Override public AdvisorScope advisorScope() { return AdvisorScope.ATTEMPT; }
        };
        ModeAwareChatClientFactory configured = factory(List.of(nullAdvisorProvider));

        var attempt = configured.create(command(ChatMode.KNOWLEDGE),
                candidate(), List.of());

        assertEquals(1, consulted.get(), "provider 必须被问过一次");
        assertNotNull(attempt.client());

        // 反向对照：换成返回真 advisor 的 provider，同一条链照样建得起来。
        // 没有这条，"provider 压根没被接进链"也会绿。
        var withReal = factory(List.of(provider("p2", Set.of(ChatMode.KNOWLEDGE),
                AdvisorScope.ATTEMPT, passthroughAdvisor())))
                .create(command(ChatMode.KNOWLEDGE), candidate(), List.of());
        assertNotNull(withReal.client());
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
        // Batch 955：名字写着 WrapsChatModel，可包装后的模型在 Attempt 这道
        // 缝上看不见（Attempt 只暴露 client / candidate / retrievalContext /
        // memory），原来那条 assertNotNull 等于什么都没验。
        // 真正能从这道缝观测到的是：预算被带进了授权上下文。
        assertSame(commandWithBudget.executionBudget(),
                attempt.retrievalContext().executionBudget(),
                "预算必须原样传到 AuthorizedRetrievalContext");

        // 反向对照：不带预算时就是 null，否则上面那条对谁都能过。
        var withoutBudget = configured.create(
                command(ChatMode.KNOWLEDGE), candidate(), List.of());
        assertNull(withoutBudget.retrievalContext().executionBudget());
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
        // 同上：queryTransformer / queryExpander 建出来的对象在 Attempt 上
        // 看不见，可预算进上下文是看得见的，顺带把它钉住。
        assertSame(commandWithBudget.executionBudget(),
                attempt.retrievalContext().executionBudget());
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
