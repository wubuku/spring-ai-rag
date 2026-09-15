package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.RagChatToolPolicy;
import com.springairag.api.service.RagChatToolProvider;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.skill.RuntimeSkillCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagChatToolRegistry 注册校验长尾（Batch 435）：provider 空名/
* 空注册数据/空策略表、callback 空 definition/元数据、空名与重
 * 名工具、未知策略键、null 策略、输入 schema 空白与非法 JSON、
 * 策略四元组约束。
 */
class RagChatToolRegistryValidateTailTest {

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

    private org.springframework.ai.tool.ToolCallback mockDefinitionCallback(
            String name, String schema) {
        org.springframework.ai.tool.ToolCallback callback =
                mock(org.springframework.ai.tool.ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(definition.inputSchema()).thenReturn(schema);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.getToolMetadata()).thenReturn(
                ToolMetadata.builder().returnDirect(false).build());
        return callback;
    }

    private RagChatToolProvider provider(
            String name,
            Set<String> domains,
            Map<String, RagChatToolPolicy> policies,
            List<org.springframework.ai.tool.ToolCallback> callbacks) {
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
            public List<org.springframework.ai.tool.ToolCallback> getToolCallbacks() {
                return callbacks;
            }

            @Override
            public Map<String, RagChatToolPolicy> getToolPolicies() {
                return policies;
            }
        };
    }

    private org.springframework.ai.tool.ToolCallback callback(
            String name, String schema) {
        return mockCallback(name, schema);
    }

    /** schema/name 允许非法值的 mock callback（绕过 builder 校验）。 */
    private org.springframework.ai.tool.ToolCallback mockCallback(
            String name, String schema) {
        return new org.springframework.ai.tool.ToolCallback() {
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
                return "ok";
            }
        };
    }

    private IllegalStateException failingRegistry(
            RagChatToolProvider provider) {
        try {
            new RagChatToolRegistry(
                    new RagChatProperties(),
                    knowledgeTool(),
                    null,
                    List.of(provider));
        } catch (IllegalStateException e) {
            return e;
        }
        throw new AssertionError("expected IllegalStateException");
    }

    private RagChatToolPolicy validPolicy() {
        return new RagChatToolPolicy(
                RagChatToolPolicy.Effect.READ_ONLY, 1, 1_024,
                Duration.ofSeconds(1));
    }

    @Test
    void blankProviderNameIsRejected() {
        assertTrue(failingRegistry(provider("", Set.of(), Map.of(),
                List.of())).getMessage().contains("name must not be blank"));
    }

    @Test
    void nullRegistrationDataIsRejected() {
        RagChatToolProvider nullModes = new RagChatToolProvider() {
            @Override
            public String getName() {
                return "p";
            }

            @Override
            public Set<ChatMode> supportedModes() {
                return null;
            }

            @Override
            public List<org.springframework.ai.tool.ToolCallback> getToolCallbacks() {
                return List.of();
            }
        };
        assertTrue(failingRegistry(nullModes).getMessage()
                .contains("null registration data"));

        RagChatToolProvider nullPolicies = new RagChatToolProvider() {
            @Override
            public String getName() {
                return "p";
            }

            @Override
            public List<org.springframework.ai.tool.ToolCallback> getToolCallbacks() {
                return List.of();
            }

            @Override
            public Map<String, RagChatToolPolicy> getToolPolicies() {
                return null;
            }
        };
        assertTrue(failingRegistry(nullPolicies).getMessage()
                .contains("null tool policies"));
    }

    @Test
    void invalidCallbackIsRejected() {
        org.springframework.ai.tool.ToolCallback missingDefinition =
                mock(org.springframework.ai.tool.ToolCallback.class);
        when(missingDefinition.getToolDefinition()).thenReturn(null);
        assertTrue(failingRegistry(provider("p", Set.of(), Map.of(),
                List.of(missingDefinition))).getMessage()
                .contains("invalid callback"));
    }

    @Test
    void blankAndDuplicateToolNamesAreRejected() {
        assertTrue(failingRegistry(provider("p", Set.of(), Map.of(),
                List.of(mockDefinitionCallback("  ", "{}")))).getMessage()
                .contains("Duplicate or blank chat tool name"));

        // 内置 searchKnowledge 与外部同名 → 跨 provider 重名。
        assertTrue(failingRegistry(provider("p", Set.of(), Map.of(),
                List.of(callback("searchKnowledge", "{}")))).getMessage()
                .contains("Duplicate or blank chat tool name"));
    }

    @Test
    void unknownOrNullPolicyKeysAreRejected() {
        assertTrue(failingRegistry(provider("p", Set.of(),
                Map.of("ghost", validPolicy()),
                List.of(callback("t", "{}")))).getMessage()
                .contains("Unknown chat tool policy key"));

        Map<String, RagChatToolPolicy> withNull = new java.util.HashMap<>();
        withNull.put("t", null);
        assertTrue(failingRegistry(provider("p", Set.of(),
                withNull, List.of(callback("t", "{}")))).getMessage()
                .contains("Null policy for chat tool"));
    }

    @Test
    void emptyOrInvalidInputSchemaIsRejected() {
        assertTrue(failingRegistry(provider("p", Set.of(), Map.of(),
                List.of(mockDefinitionCallback("t", "  ")))).getMessage()
                .contains("Empty input schema"));

        assertTrue(failingRegistry(provider("p", Set.of(), Map.of(),
                List.of(mockDefinitionCallback("t", "not-json")))).getMessage()
                .contains("Invalid input schema"));
    }

    @Test
    void policyQuadrupleConstraintsAreEnforced() {
        // maxResultCharacters 低于 1_024 下限 → 拒绝。
        assertTrue(failingRegistry(provider("p", Set.of(),
                Map.of("t", new RagChatToolPolicy(
                        RagChatToolPolicy.Effect.READ_ONLY, 1, 512,
                        Duration.ofSeconds(1))),
                List.of(callback("t", "{}")))).getMessage()
                .contains("Invalid policy for chat tool"));

        // maxCallsPerRequest 超过全局上限。
        RagChatProperties properties = new RagChatProperties();
        properties.getAgent().setMaxToolCallsPerName(1);
        IllegalStateException error;
        try {
            new RagChatToolRegistry(
                    properties,
                    knowledgeTool(),
                    null,
                    List.of(provider("p", Set.of(),
                            Map.of("t", new RagChatToolPolicy(
                                    RagChatToolPolicy.Effect.READ_ONLY, 5,
                                    1_024, Duration.ofSeconds(1))),
                            List.of(callback("t", "{}")))));
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException e) {
            error = e;
        }
        assertTrue(error.getMessage().contains("Invalid policy for chat tool"));

        // timeout 为零 → 拒绝。
        assertTrue(failingRegistry(provider("p", Set.of(),
                Map.of("t", new RagChatToolPolicy(
                        RagChatToolPolicy.Effect.READ_ONLY, 1, 1_024,
                        Duration.ZERO)),
                List.of(callback("t", "{}")))).getMessage()
                .contains("Invalid policy for chat tool"));
    }
}
