package com.springairag.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.resource.ResourceCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RuntimeSkillToolProvider 回调入口长尾（Batch 751，JaCoCo 驱
 * 动）：1 参 call 无上下文直接抛 ISE（141）、input 对 null/空白归
 * 一 "{}"（128-129）、损坏 JSON 包装 IAE（130）。
 */
class RuntimeSkillToolProviderInputTailTest {

    private RuntimeSkillToolProvider provider;
    private RuntimeSkillLoadSession session;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        properties.getSkills().setLocations(java.util.List.of("classpath:skills-fixture/"));
        RuntimeSkillCatalog catalog = new RuntimeSkillCatalog(
                new ResourceCatalog(), properties);
        catalog.initialize();
        provider = new RuntimeSkillToolProvider(
                catalog, properties, new ObjectMapper());
        session = new RuntimeSkillLoadSession(2, 2, 4_000);
        context = new ToolContext(Map.of(
                RuntimeSkillLoadSession.CONTEXT_KEY, session));
    }

    private org.springframework.ai.tool.ToolCallback loadCallback() {
        return provider.getToolCallbacks().stream()
                .filter(callback -> "loadSkill".equals(
                        callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void oneArgCallWithoutContextThrowsIllegalState() {
        assertThrows(IllegalStateException.class,
                () -> loadCallback().call("{\"skillName\":\"weather\"}"));
    }

    @Test
    void nullToolInputNormalizesToEmptyObjectArgs() {
        String response = loadCallback().call(null, context);
        // null 入参归一 "{}"；缺少 skillName → 返回错误 JSON 而非异常。
        assertTrue(response.contains("error") || response.contains("skillName"),
                "unexpected response: " + response);
    }

    @Test
    void malformedJsonInputWrapsAsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> loadCallback().call("{broken", context));
    }
}
