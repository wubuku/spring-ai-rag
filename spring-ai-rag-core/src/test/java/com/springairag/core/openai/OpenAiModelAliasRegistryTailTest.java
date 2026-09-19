package com.springairag.core.openai;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.config.RagOpenAiCompatibilityProperties;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAiModelAliasRegistry 长尾（Batch 529，JaCoCo 驱动）：别名排
 * 序、非法别名/缺失配置/空注册拒绝、候选列表归一化边界、resolve
 * 的模式/内存覆写门禁与非法值拒绝。
 */
class OpenAiModelAliasRegistryTailTest {

    private RagProperties properties(String alias,
                                     RagOpenAiCompatibilityProperties.ModelAlias model) {
        RagProperties properties = new RagProperties();
        RagOpenAiCompatibilityProperties openAi =
                properties.getOpenAiCompatibility();
        openAi.setEnabled(true);
        if (model != null) {
            openAi.getModels().put(alias, model);
        } else {
            openAi.getModels().put(alias, null);
        }
        return properties;
    }

    private RagOpenAiCompatibilityProperties.ModelAlias model(
            ChatMode mode, MemoryMode memory,
            boolean modeOverride, boolean memoryOverride,
            List<String> candidates) {
        RagOpenAiCompatibilityProperties.ModelAlias value =
                new RagOpenAiCompatibilityProperties.ModelAlias();
        value.setMode(mode);
        value.setMemory(memory);
        value.setAllowRequestModeOverride(modeOverride);
        value.setAllowRequestMemoryOverride(memoryOverride);
        value.setCandidates(candidates);
        return value;
    }

    @Test
    void listReturnsAliasesSortedByName() {
        RagProperties properties = new RagProperties();
        var openAi = properties.getOpenAiCompatibility();
        openAi.getModels().put("zeta", model(ChatMode.PLAIN,
                MemoryMode.STATELESS, false, false, List.of("m-z")));
        openAi.getModels().put("alpha", model(ChatMode.KNOWLEDGE,
                MemoryMode.SERVER, false, false, List.of("m-a")));

        var registry = new OpenAiModelAliasRegistry(properties);

        assertEquals(List.of("alpha", "zeta"),
                registry.list().stream()
                        .map(OpenAiModelAliasRegistry.AliasDefinition::alias)
                        .toList());
    }

    @Test
    void invalidAliasCharacterIsRejectedAtConstruction() {
        var error = assertThrows(IllegalStateException.class,
                () -> new OpenAiModelAliasRegistry(
                        properties("bad alias!", model(ChatMode.PLAIN,
                                MemoryMode.STATELESS, false, false,
                                List.of("m")))));
        assertTrue(error.getMessage().contains("Invalid"));
    }

    @Test
    void missingModelConfigurationIsRejected() {
        var error = assertThrows(IllegalStateException.class,
                () -> new OpenAiModelAliasRegistry(
                        properties("ghost", null)));
        assertTrue(error.getMessage().contains("Missing configuration"));
    }

    @Test
    void emptyRegistryIsRejected() {
        RagProperties properties = new RagProperties();
        properties.getOpenAiCompatibility().setEnabled(true);

        var error = assertThrows(IllegalStateException.class,
                () -> new OpenAiModelAliasRegistry(properties));
        assertTrue(error.getMessage().contains("at least one model alias"));
    }

    @Test
    void oversizeCandidateListIsRejected() {
        var error = assertThrows(IllegalStateException.class,
                () -> new OpenAiModelAliasRegistry(properties(
                        "m", model(ChatMode.PLAIN, MemoryMode.STATELESS,
                                false, false,
                                java.util.stream.IntStream.rangeClosed(0, 16)
                                        .mapToObj(i -> "c" + i)
                                        .toList()))));
        assertTrue(error.getMessage().contains("more than 16 candidates"));
    }

    @Test
    void blankCandidateIsRejectedAndDuplicatesDeduplicated() {
        assertThrows(IllegalStateException.class,
                () -> new OpenAiModelAliasRegistry(properties(
                        "m", model(ChatMode.PLAIN, MemoryMode.STATELESS,
                                false, false, List.of("  ")))));

        var registry = new OpenAiModelAliasRegistry(properties(
                "m", model(ChatMode.PLAIN, MemoryMode.STATELESS,
                        false, false,
                        List.of(" a ", "a", "b"))));
        assertEquals(List.of("a", "b"),
                registry.require("m").candidates());
    }

    @Test
    void requireUnknownAliasThrowsModelNotFound() {
        var registry = new OpenAiModelAliasRegistry(properties(
                "m", model(ChatMode.PLAIN, MemoryMode.STATELESS,
                        false, false, List.of("a"))));

        assertThrows(OpenAiProtocolException.class,
                () -> registry.require("nope"));
        assertThrows(OpenAiProtocolException.class,
                () -> registry.resolve(null, null, null));
    }

    @Test
    void resolveRejectsModeOverrideWhenDisabled() {
        var registry = new OpenAiModelAliasRegistry(properties(
                "m", model(ChatMode.KNOWLEDGE, MemoryMode.STATELESS,
                        false, false, List.of("a"))));

        var error = assertThrows(OpenAiProtocolException.class,
                () -> registry.resolve("m", ChatMode.AGENT, null));
        assertTrue(error.getMessage().contains("rag.mode override"));
    }

    @Test
    void resolveAppliesModeOverrideWhenEnabled() {
        var registry = new OpenAiModelAliasRegistry(properties(
                "m", model(ChatMode.KNOWLEDGE, MemoryMode.STATELESS,
                        true, false, List.of("a"))));

        var resolved = registry.resolve("m", ChatMode.AGENT, null);

        assertEquals(ChatMode.AGENT, resolved.mode());
    }

    @Test
    void resolveRejectsInvalidMemoryValue() {
        var registry = new OpenAiModelAliasRegistry(properties(
                "m", model(ChatMode.PLAIN, MemoryMode.STATELESS,
                        false, false, List.of("a"))));

        var error = assertThrows(OpenAiProtocolException.class,
                () -> registry.resolve("m", null, "NOT_A_MODE"));
        assertTrue(error.getMessage().contains("rag.memory"));
    }

    @Test
    void resolveRejectsMemoryOverrideWhenDisabled() {
        var registry = new OpenAiModelAliasRegistry(properties(
                "m", model(ChatMode.PLAIN, MemoryMode.STATELESS,
                        false, false, List.of("a"))));

        var error = assertThrows(OpenAiProtocolException.class,
                () -> registry.resolve("m", null, "SERVER"));
        assertTrue(error.getMessage().contains("rag.memory override"));
    }

    @Test
    void resolveAppliesMemoryOverrideWhenEnabled() {
        var registry = new OpenAiModelAliasRegistry(properties(
                "m", model(ChatMode.PLAIN, MemoryMode.STATELESS,
                        false, true, List.of("a"))));

        var resolved = registry.resolve("m", null, " server ");

        assertEquals(MemoryMode.SERVER, resolved.memory());
    }
}
