package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.RagChatToolPolicy;
import com.springairag.api.service.RagChatToolContextKeys;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PolicyToolCallback#call 的调用矩阵（Batch 335）：单参入口与缺
 * 失请求上下文拒绝、每名调用预算耗尽短路、截止时间已过取消、委
 * 托透传、委托执行失败降级。
 */
class RagChatToolPolicyCallbackCallTest {

    private static RagChatProperties properties() {
        RagChatProperties properties = new RagChatProperties();
        properties.getAgent().setMaxToolCalls(4);
        properties.getAgent().setMaxToolCallsPerName(3);
        properties.getAgent().setMaxToolResultCharactersTotal(50_000);
        return properties;
    }

    private ToolCallback delegate(
            String name,
            AtomicInteger counter,
            Function<String, String> behavior) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(false).build();
            }

            @Override
            public String call(String toolInput) {
                counter.incrementAndGet();
                return behavior.apply(toolInput);
            }
        };
    }

    private RagChatToolProvider provider(ToolCallback callback) {
        return new RagChatToolProvider() {
            @Override public String getName() { return "lookup-provider"; }
            @Override public Set<ChatMode> supportedModes() {
                return Set.of(ChatMode.AGENT);
            }
            @Override public Set<String> supportedDomains() { return Set.of(); }
            @Override public List<ToolCallback> getToolCallbacks() {
                return List.of(callback);
            }
            @Override public Map<String, RagChatToolPolicy> getToolPolicies() {
                return Map.of("lookup", new RagChatToolPolicy(
                        RagChatToolPolicy.Effect.READ_ONLY, 3, 1_024,
                        Duration.ofSeconds(1)));
            }
        };
    }

    private ToolCallback wrappedTool(
            AtomicInteger delegateCalls,
            Function<String, String> behavior) {
        RagChatToolRegistry registry = new RagChatToolRegistry(
                properties(), knowledgeTool(), null,
                List.of(provider(delegate("lookup", delegateCalls, behavior))));
        return registry.callbacks(ChatMode.AGENT, null).stream()
                .filter(tool -> "lookup".equals(
                        tool.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
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

    private ToolContext context(
            ChatExecutionBudget budget, Instant deadline) {
        var request = new RagChatToolRequestContext(
                "principal", "USER", false, "session",
                null, ChatMode.AGENT, "test/model", deadline);
        var entries = new java.util.HashMap<String, Object>();
        entries.put(RagChatToolContextKeys.REQUEST, request);
        if (budget != null) {
            entries.put(ChatExecutionBudget.CONTEXT_KEY, budget);
        }
        return new ToolContext(entries);
    }

    private ChatExecutionBudget budgetAllowing(boolean allowed) {
        ChatExecutionBudget budget = mock(ChatExecutionBudget.class);
        when(budget.tryReservePolicyToolCall(anyString(), anyInt()))
                .thenReturn(allowed);
        return budget;
    }

    @Test
    void singleArgCallThrowsWithoutServerContext() {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback tool = wrappedTool(calls, input -> "ok");

        assertThrows(IllegalStateException.class, () -> tool.call("{}"));
        assertEquals(0, calls.get());
    }

    @Test
    void missingRequestContextThrows() {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback tool = wrappedTool(calls, input -> "ok");

        assertThrows(IllegalStateException.class,
                () -> tool.call("{}", new ToolContext(Map.of())));
        assertEquals(0, calls.get());
    }

    @Test
    void policyBudgetExhaustedShortCircuits() {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback tool = wrappedTool(calls, input -> "ok");

        String result = tool.call("{}",
                context(budgetAllowing(false),
                        Instant.now().plusSeconds(10)));

        assertEquals("{\"error\":\"tool_call_policy_exhausted\"}", result);
        assertEquals(0, calls.get());
    }

    /**
     * Pins that a **passed deadline means the tool is never dispatched**, not
     * "dispatched and then interrupted".
     *
     * <p>This assertion used to fail roughly one run in three. The registry
     * submitted the task first and checked the deadline afterwards, so
     * {@code future.cancel(true)} raced a task that had already started and
     * already incremented the counter. Batch 826 moved the check before the
     * submission; 8 consecutive runs then passed where the old code failed
     * within three.
     *
     * <p>Worth knowing before anyone "strengthens" this: the registry builds its
     * own {@link java.util.concurrent.ThreadPoolExecutor} with no injection
     * point, so the submission itself cannot be observed from a test. Adding a
     * constructor parameter or setter purely to watch it would be a test hook in
     * production code, which is worse than a slightly indirect assertion.
     * Practically: a single green run does not prove the ordering — this test
     * failed intermittently by nature before the fix.
     */
    @Test
    void passedDeadlineCancelsExecutionAndReportsTimeout() {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback tool = wrappedTool(calls, input -> "ok");

        String result = tool.call("{}",
                context(budgetAllowing(true),
                        Instant.now().minusSeconds(1)));

        assertEquals("{\"error\":\"tool_timeout\"}", result);
        assertEquals(0, calls.get());
    }

    @Test
    void delegateResultPassesThroughWithinBudget() {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback tool = wrappedTool(calls, input -> "lookup-result");

        String result = tool.call("{\"q\":\"x\"}",
                context(budgetAllowing(true),
                        Instant.now().plusSeconds(10)));

        assertEquals("lookup-result", result);
        assertEquals(1, calls.get());
    }

    @Test
    void delegateFailureDegradesToToolExecutionFailed() {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback tool = wrappedTool(calls, input -> {
            throw new IllegalStateException("boom");
        });

        String result = tool.call("{}",
                context(budgetAllowing(true),
                        Instant.now().plusSeconds(10)));

        assertEquals("{\"error\":\"tool_execution_failed\"}", result);
        assertEquals(1, calls.get());
    }
}
