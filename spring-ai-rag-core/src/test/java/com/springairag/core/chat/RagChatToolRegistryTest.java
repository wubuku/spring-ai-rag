package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.RagChatToolPolicy;
import com.springairag.api.service.RagChatToolProvider;
import com.springairag.api.service.RagChatToolRequestContext;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.rag.KnowledgeSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagChatToolRegistryTest {

    @Test
    void registeredCallbackEnforcesPolicyCallBudget() {
        RagChatProperties properties = new RagChatProperties();
        properties.getAgent().setMaxToolCalls(4);
        properties.getAgent().setMaxToolCallsPerName(3);
        properties.getAgent().setMaxToolResultCharactersTotal(50_000);
        AtomicInteger invocations = new AtomicInteger();
        KnowledgeSearchTool knowledge = knowledgeTool();
        ToolCallback external = callback("lookupInventory", "{\"type\":\"object\"}",
                ignored -> {
                    invocations.incrementAndGet();
                    return "ok";
                });
        RagChatToolProvider provider = provider(
                "inventory", Set.of(), Map.of(
                        "lookupInventory",
                        new RagChatToolPolicy(
                                RagChatToolPolicy.Effect.READ_ONLY,
                                1,
                                1_024,
                                Duration.ofSeconds(1))),
                List.of(external));
        RagChatToolRegistry registry = new RagChatToolRegistry(
                properties,
                knowledge,
                null,
                List.of(provider));
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 2, 4, 3, 4, 2, 4_000);
        ToolContext context = new ToolContext(Map.of(
                com.springairag.api.service.RagChatToolContextKeys.REQUEST,
                new RagChatToolRequestContext(
                        "principal", "USER", false, "session",
                        null, ChatMode.AGENT, "test/model",
                        Instant.now().plusSeconds(10)),
                ChatExecutionBudget.CONTEXT_KEY, budget));

        ToolCallback registered = registry.callbacks(ChatMode.AGENT, null).stream()
                .filter(tool -> "lookupInventory".equals(
                        tool.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();

        assertEquals("ok", registered.call("{}", context));
        assertEquals("{\"error\":\"tool_call_policy_exhausted\"}",
                registered.call("{}", context));
        assertEquals(1, invocations.get());
    }

    @Test
    void domainProviderOnlyMatchesItsExplicitDomain() {
        RagChatProperties properties = new RagChatProperties();
        KnowledgeSearchTool knowledge = knowledgeTool();
        ToolCallback external = callback("domainTool", "{}",
                ignored -> "domain");
        RagChatToolProvider provider = provider(
                "domain", Set.of("orders"), Map.of(), List.of(external));
        RagChatToolRegistry registry = new RagChatToolRegistry(
                properties, knowledge, null, List.of(provider));

        assertEquals(0, registry.callbacks(ChatMode.AGENT, null).stream()
                .filter(tool -> "domainTool".equals(tool.getToolDefinition().name()))
                .count());
        assertEquals(1, registry.callbacks(ChatMode.AGENT, "orders").stream()
                .filter(tool -> "domainTool".equals(tool.getToolDefinition().name()))
                .count());
    }

    @Test
    void omittedPolicyInheritsTighterGlobalLimits() {
        RagChatProperties properties = new RagChatProperties();
        properties.getAgent().setMaxToolCalls(2);
        properties.getAgent().setMaxToolCallsPerName(1);
        properties.getAgent().setMaxToolResultCharacters(4_000);
        properties.getAgent().setMaxToolResultCharactersTotal(4_000);
        KnowledgeSearchTool knowledge = knowledgeTool();
        ToolCallback external = callback("smallLookup", "{}", ignored -> "ok");
        RagChatToolProvider provider = provider(
                "small-provider", Set.of(), Map.of(), List.of(external));

        RagChatToolRegistry registry = new RagChatToolRegistry(
                properties, knowledge, null, List.of(provider));
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 1, 2, 1, 2, 1, 4_000);
        ToolContext context = new ToolContext(Map.of(
                com.springairag.api.service.RagChatToolContextKeys.REQUEST,
                new RagChatToolRequestContext(
                        "principal", "USER", false, "session",
                        null, ChatMode.AGENT, "test/model",
                        Instant.now().plusSeconds(10)),
                ChatExecutionBudget.CONTEXT_KEY, budget));

        ToolCallback registered = registry.callbacks(ChatMode.AGENT, null).stream()
                .filter(tool -> "smallLookup".equals(
                        tool.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();

        assertEquals("ok", registered.call("{}", context));
        assertEquals("{\"error\":\"tool_call_policy_exhausted\"}",
                registered.call("{}", context));
    }

    @Test
    void invalidReturnDirectCallbackIsRejectedAtStartup() {
        RagChatProperties properties = new RagChatProperties();
        KnowledgeSearchTool knowledge = knowledgeTool();
        ToolCallback invalid = mock(ToolCallback.class);
        when(invalid.getToolDefinition()).thenReturn(
                ToolDefinition.builder()
                        .name("invalid")
                        .description("invalid")
                        .inputSchema("{}")
                        .build());
        when(invalid.getToolMetadata()).thenReturn(
                ToolMetadata.builder().returnDirect(true).build());

        assertThrows(
                IllegalStateException.class,
                () -> new RagChatToolRegistry(
                        properties,
                        knowledge,
                        null,
                        List.of(provider(
                                "invalid-provider",
                                Set.of(),
                                Map.of(),
                                List.of(invalid)))));
    }

    private RagChatToolProvider provider(
            String name,
            Set<String> domains,
            Map<String, RagChatToolPolicy> policies,
            List<ToolCallback> callbacks) {
        return new RagChatToolProvider() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public Set<String> supportedDomains() {
                return domains;
            }

            @Override
            public List<ToolCallback> getToolCallbacks() {
                return callbacks;
            }

            @Override
            public Map<String, RagChatToolPolicy> getToolPolicies() {
                return policies;
            }
        };
    }

    private ToolCallback callback(
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
}
