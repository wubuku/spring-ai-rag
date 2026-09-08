package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.RagChatToolPolicy;
import com.springairag.api.service.RagChatToolProvider;
import com.springairag.api.service.RagChatToolRequestContext;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.rag.KnowledgeSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具注册表启动校验与请求上下文：重复工具名、非法策略（写效果/
 * 超全局上限）、非法输入 schema、模式过滤、requestContext 的主上/
 * 预算/每工具字符限制装配。
 */
class RagChatToolRegistryPolicyTest {

    private static RagChatProperties properties() {
        RagChatProperties properties = new RagChatProperties();
        properties.getAgent().setMaxToolCalls(4);
        properties.getAgent().setMaxToolCallsPerName(3);
        properties.getAgent().setMaxToolResultCharactersTotal(50_000);
        return properties;
    }

    private static ToolCallback callback(
            String name,
            String schema,
            java.util.function.Function<String, String> function) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema(schema)
                        .build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(false).build();
            }

            @Override
            public String call(String toolInput) {
                return function.apply(toolInput);
            }
        };
    }

    private RagChatToolProvider provider(
            String name,
            Set<ChatMode> modes,
            Set<String> domains,
            Map<String, RagChatToolPolicy> policies,
            List<ToolCallback> callbacks) {
        return new RagChatToolProvider() {
            @Override public String getName() { return name; }
            @Override public Set<ChatMode> supportedModes() { return modes; }
            @Override public Set<String> supportedDomains() { return domains; }
            @Override public List<ToolCallback> getToolCallbacks() { return callbacks; }
            @Override public Map<String, RagChatToolPolicy> getToolPolicies() { return policies; }
        };
    }

    private static RagChatToolPolicy readOnlyPolicy(int calls, int chars) {
        return new RagChatToolPolicy(
                RagChatToolPolicy.Effect.READ_ONLY, calls, chars,
                Duration.ofSeconds(1));
    }

    private KnowledgeSearchTool knowledgeTool() {
        KnowledgeSearchTool knowledge = mock(KnowledgeSearchTool.class);
        when(knowledge.getToolDefinition()).thenReturn(
                ToolDefinition.builder()
                        .name("searchKnowledge")
                        .description("search")
                        .inputSchema("{}")
                        .build());
        when(knowledge.getToolMetadata()).thenReturn(
                ToolMetadata.builder().returnDirect(false).build());
        return knowledge;
    }

    @Test
    void duplicateToolNameAcrossProvidersRejectedAtStartup() {
        ToolCallback first = callback("shared", "{}", ignored -> "a");
        ToolCallback second = callback("shared", "{}", ignored -> "b");
        RagChatToolProvider p1 = provider("p1", Set.of(ChatMode.AGENT),
                Set.of(), Map.of(), List.of(first));
        RagChatToolProvider p2 = provider("p2", Set.of(ChatMode.AGENT),
                Set.of(), Map.of(), List.of(second));

        assertThrows(IllegalStateException.class,
                () -> new RagChatToolRegistry(
                        properties(), knowledgeTool(), null,
                        List.of(p1, p2)));
    }

    @Test
    void policyExceedingGlobalLimitsRejectedAtStartup() {
        ToolCallback tool = callback("greedy", "{}", ignored -> "ok");
        RagChatToolProvider provider = provider("p", Set.of(ChatMode.AGENT),
                Set.of(), Map.of("greedy", readOnlyPolicy(9, 1_024)),
                List.of(tool));

        assertThrows(IllegalStateException.class,
                () -> new RagChatToolRegistry(
                        properties(), knowledgeTool(), null,
                        List.of(provider)));
    }

    @Test
    void blankInputSchemaRejectedAtStartup() {
        ToolCallback tool = callback("schemaless", "   ", ignored -> "ok");
        RagChatToolProvider provider = provider("p", Set.of(ChatMode.AGENT),
                Set.of(), Map.of(), List.of(tool));

        // ToolDefinition 构建器先于注册表拒绝空白 schema。
        assertThrows(IllegalArgumentException.class,
                () -> new RagChatToolRegistry(
                        properties(), knowledgeTool(), null,
                        List.of(provider)));
    }

    @Test
    void nonObjectInputSchemaRejectedAtStartup() {
        ToolCallback tool = callback("arraySchema", "[1,2]", ignored -> "ok");
        RagChatToolProvider provider = provider("p", Set.of(ChatMode.AGENT),
                Set.of(), Map.of(), List.of(tool));

        assertThrows(IllegalStateException.class,
                () -> new RagChatToolRegistry(
                        properties(), knowledgeTool(), null,
                        List.of(provider)));
    }

    @Test
    void modeFilteringExcludesProviderForOtherModes() {
        ToolCallback agentOnly = callback("agentTool", "{}", ignored -> "ok");
        RagChatToolProvider provider = provider(
                "p", Set.of(ChatMode.AGENT), Set.of(), Map.of(),
                List.of(agentOnly));
        RagChatToolRegistry registry = new RagChatToolRegistry(
                properties(), knowledgeTool(), null, List.of(provider));

        // AGENT 模式含内置知识工具，按名称过滤后再断言。
        assertEquals(1, registry.callbacks(ChatMode.AGENT, null).stream()
                .filter(tool -> "agentTool".equals(
                        tool.getToolDefinition().name()))
                .count());
        assertEquals(0, registry.callbacks(ChatMode.KNOWLEDGE, null).stream()
                .filter(tool -> "agentTool".equals(
                        tool.getToolDefinition().name()))
                .count());
    }

    @Test
    void requestContextCarriesPrincipalBudgetAndPerToolLimits() {
        ToolCallback tool = callback("lookup", "{}", ignored -> "ok");
        RagChatToolProvider provider = provider("inventory",
                Set.of(ChatMode.AGENT), Set.of(),
                Map.of("lookup", readOnlyPolicy(2, 2_048)),
                List.of(tool));
        RagChatToolRegistry registry = new RagChatToolRegistry(
                properties(), knowledgeTool(), null, List.of(provider));

        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 2, 4, 3, 4, 2, 4_000);
        ChatPrincipal principal = new ChatPrincipal(
                "db:1", "DATABASE_API_KEY", false);
        ChatCommand command = new ChatCommand(
                "question", "session-1", principal, null,
                ChatMode.AGENT, null, null, null,
                null, null, null, null, null, null, null, budget);
        ChatModelRouter.ChatModelCandidate candidate =
                new ChatModelRouter.ChatModelCandidate(
                        "zhipu/model", mock(ChatModel.class),
                        MultiModelProperties.ModelCapabilities.defaults(),
                        null, null, true, null);

        Map<String, Object> context =
                registry.requestContext(command, candidate);

        RagChatToolRequestContext request =
                (RagChatToolRequestContext) context.get(
                        com.springairag.api.service.RagChatToolContextKeys.REQUEST);
        assertEquals("db:1", request.principalId());
        assertEquals(ChatMode.AGENT, request.mode());
        assertEquals("zhipu/model", request.resolvedModelRef());
        assertEquals(budget.deadline(), request.deadline());
        org.junit.jupiter.api.Assertions.assertSame(
                budget, context.get(ChatExecutionBudget.CONTEXT_KEY));

        @SuppressWarnings("unchecked")
        Map<String, Integer> limits =
                (Map<String, Integer>) context.get(
                        ChatExecutionBudget.TOOL_RESULT_CHARACTER_LIMITS_CONTEXT_KEY);
        assertEquals(2_048, limits.get("lookup"));
    }

}
