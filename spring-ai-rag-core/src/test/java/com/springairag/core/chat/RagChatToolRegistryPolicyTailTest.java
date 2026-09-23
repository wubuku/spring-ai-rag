package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.RagChatToolPolicy;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.api.service.RagChatToolProvider;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.rag.KnowledgeSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagChatToolRegistry 策略校验与过滤长尾（Batch 601，JaCoCo 驱动）：
 * 策略四元组各约束违反均拒绝、未知/空白策略键、重复与空白工具名、
 * callbacks 按 mode/domain 过滤、requestContext 对无预算命令省略
 * 预算键。
 */
class RagChatToolRegistryPolicyTailTest {

    private KnowledgeSearchTool knowledgeTool() {
        KnowledgeSearchTool knowledge = mock(KnowledgeSearchTool.class);
        when(knowledge.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("searchKnowledge")
                .description("search")
                .inputSchema("{}")
                .build());
        when(knowledge.getToolMetadata()).thenReturn(
                ToolMetadata.builder().returnDirect(false).build());
        return knowledge;
    }

    private RagChatToolProvider provider(
            String name,
            Set<ChatMode> modes,
            Set<String> domains,
            Map<String, RagChatToolPolicy> policies,
            List<org.springframework.ai.tool.ToolCallback> callbacks) {
        return new RagChatToolProvider() {
            @Override public String getName() { return name; }
            @Override public Set<ChatMode> supportedModes() { return modes; }
            @Override public Set<String> supportedDomains() { return domains; }
            @Override public int getOrder() { return 1; }
            @Override public List<org.springframework.ai.tool.ToolCallback> getToolCallbacks() {
                return callbacks;
            }
            @Override public Map<String, RagChatToolPolicy> getToolPolicies() {
                return policies;
            }
        };
    }

    private org.springframework.ai.tool.ToolCallback callback(String name) {
        return new org.springframework.ai.tool.ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }
            @Override public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(false).build();
            }
            @Override public String call(String toolInput) { return "ok"; }
        };
    }

    private IllegalStateException failingRegistry(
            RagChatToolProvider external) {
        try {
            new RagChatToolRegistry(
                    new RagChatProperties(),
                    knowledgeTool(),
                    null,
                    List.of(external));
        } catch (IllegalStateException e) {
            return e;
        }
        throw new AssertionError("expected IllegalStateException");
    }

    @Test
    void validatePolicyRejectsEveryConstraintViolation() {
        RagChatToolPolicy zeroCalls = new RagChatToolPolicy(
                RagChatToolPolicy.Effect.READ_ONLY, 0, 1_024,
                Duration.ofSeconds(1));
        RagChatToolPolicy hugeCalls = new RagChatToolPolicy(
                RagChatToolPolicy.Effect.READ_ONLY, 99_999, 1_024,
                Duration.ofSeconds(1));
        RagChatToolPolicy tinyResult = new RagChatToolPolicy(
                RagChatToolPolicy.Effect.READ_ONLY, 1, 8,
                Duration.ofSeconds(1));
        RagChatToolPolicy hugeResult = new RagChatToolPolicy(
                RagChatToolPolicy.Effect.READ_ONLY, 1, 99_999_999,
                Duration.ofSeconds(1));
        RagChatToolPolicy zeroTimeout = new RagChatToolPolicy(
                RagChatToolPolicy.Effect.READ_ONLY, 1, 1_024,
                Duration.ZERO);
        RagChatToolPolicy negativeTimeout = new RagChatToolPolicy(
                RagChatToolPolicy.Effect.READ_ONLY, 1, 1_024,
                Duration.ofSeconds(-1));
        for (RagChatToolPolicy bad : List.of(zeroCalls,
                hugeCalls, tinyResult, hugeResult, zeroTimeout,
                negativeTimeout)) {
            assertTrue(failingRegistry(provider("p", Set.of(ChatMode.AGENT),
                    Set.of(), Map.of("tool", bad),
                    List.of(callback("tool"))))
                    .getMessage().contains("Invalid policy for chat tool"),
                    "policy=" + bad);
        }
    }

    @Test
    void unknownAndBlankPolicyKeysAreRejected() {
        assertTrue(failingRegistry(provider("p", Set.of(ChatMode.AGENT),
                Set.of(), Map.of("ghost", new RagChatToolPolicy(
                        RagChatToolPolicy.Effect.READ_ONLY, 1, 1_024,
                        Duration.ofSeconds(1))),
                List.of(callback("tool")))).getMessage()
                .contains("Unknown chat tool policy key"));

        java.util.Map<String, RagChatToolPolicy> withNullKey =
                new java.util.HashMap<>();
        withNullKey.put(null, new RagChatToolPolicy(
                RagChatToolPolicy.Effect.READ_ONLY, 1, 1_024,
                Duration.ofSeconds(1)));
        assertTrue(failingRegistry(provider("p", Set.of(ChatMode.AGENT),
                Set.of(), withNullKey, List.of(callback("tool")))).getMessage()
                .contains("Unknown chat tool policy key"));
    }

    @Test
    void duplicateAndBlankToolNamesAreRejected() {
        assertTrue(failingRegistry(provider("p", Set.of(ChatMode.AGENT),
                Set.of(), Map.of("tool", new RagChatToolPolicy(
                        RagChatToolPolicy.Effect.READ_ONLY, 1, 1_024,
                        Duration.ofSeconds(1))),
                List.of(callback("tool"), callback("tool")))).getMessage()
                .contains("Duplicate or blank chat tool name"));

        org.springframework.ai.tool.ToolCallback blankDefinition =
                mock(org.springframework.ai.tool.ToolCallback.class);
        ToolDefinition blank = mock(ToolDefinition.class);
        when(blank.name()).thenReturn(" ");
        when(blank.inputSchema()).thenReturn("{}");
        when(blankDefinition.getToolDefinition()).thenReturn(blank);
        when(blankDefinition.getToolMetadata()).thenReturn(
                ToolMetadata.builder().returnDirect(false).build());
        assertTrue(failingRegistry(provider("p", Set.of(ChatMode.AGENT),
                Set.of(), Map.of(), List.of(blankDefinition))).getMessage()
                .contains("Duplicate or blank chat tool name"));
    }

    @Test
    void callbacksFilterByModeAndDomain() {
        RagChatToolPolicy policy = new RagChatToolPolicy(
                RagChatToolPolicy.Effect.READ_ONLY, 1, 1_024,
                Duration.ofSeconds(1));
        RagChatToolProvider domainScoped = provider(
                "scoped", Set.of(ChatMode.AGENT), Set.of("d1"),
                Map.of("scopedTool", policy), List.of(callback("scopedTool")));

        RagChatToolRegistry registry = new RagChatToolRegistry(
                new RagChatProperties(),
                knowledgeTool(),
                null,
                List.of(domainScoped));

        // KNOWLEDGE 模式无任何工具支持。
        assertTrue(registry.callbacks(ChatMode.KNOWLEDGE, null).isEmpty());
        // AGENT + 未指定领域 → 仅无领域限制的内建工具。
        assertEquals(List.of("searchKnowledge"),
                registry.callbacks(ChatMode.AGENT, null).stream()
                        .map(cb -> cb.getToolDefinition().name()).toList());
        // AGENT + 命中领域 → 内建工具与领域工具同时返回。
        assertEquals(List.of("searchKnowledge", "scopedTool"),
                registry.callbacks(ChatMode.AGENT, "d1").stream()
                        .map(cb -> cb.getToolDefinition().name()).toList());
        // AGENT + 未命中领域 → 领域工具被过滤。
        assertEquals(List.of("searchKnowledge"),
                registry.callbacks(ChatMode.AGENT, "d2").stream()
                        .map(cb -> cb.getToolDefinition().name()).toList());
    }

    @Test
    void requestContextOmitsBudgetKeyWhenBudgetAbsent() {
        RagChatToolPolicy policy = new RagChatToolPolicy(
                RagChatToolPolicy.Effect.READ_ONLY, 1, 1_024,
                Duration.ofSeconds(1));
        RagChatToolRegistry registry = new RagChatToolRegistry(
                new RagChatProperties(),
                knowledgeTool(),
                null,
                List.of(provider("p", Set.of(ChatMode.AGENT), Set.of(),
                        Map.of(),
                        List.of())));

        ChatPrincipal principal = ChatPrincipal.local();
        ChatCommand command = new ChatCommand(
                "question",
                "session-1",
                principal,
                principal.memoryConversationId("session-1"),
                ChatMode.AGENT,
                MemoryMode.SERVER,
                null,
                null,
                com.springairag.core.retrieval.RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        1, 0, false, false, 0, 0),
                java.util.Map.of());

        ChatModelRouter.ChatModelCandidate candidate =
                mock(ChatModelRouter.ChatModelCandidate.class);
        when(candidate.ref()).thenReturn("zhipu");

        var context = registry.requestContext(command, candidate);

        assertFalse(context.containsKey(ChatExecutionBudget.CONTEXT_KEY));
        assertTrue(context.containsKey(
                ChatExecutionBudget.TOOL_RESULT_CHARACTER_LIMITS_CONTEXT_KEY));
    }
}
