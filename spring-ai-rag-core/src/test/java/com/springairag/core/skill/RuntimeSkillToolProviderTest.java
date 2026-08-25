package com.springairag.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.RagChatToolRegistry;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.resource.ResourceCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeSkillToolProviderTest {

    @Test
    void defaultSkillPoliciesRespectGlobalRegistryBudgets() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        properties.getSkills().setLocations(List.of("classpath:skills-fixture/"));
        RuntimeSkillCatalog catalog = new RuntimeSkillCatalog(
                new ResourceCatalog(), properties);
        catalog.initialize();
        RuntimeSkillToolProvider provider = new RuntimeSkillToolProvider(
                catalog, properties, new ObjectMapper());
        KnowledgeSearchTool knowledge = mock(KnowledgeSearchTool.class);
        when(knowledge.getToolDefinition()).thenReturn(
                ToolDefinition.builder()
                        .name("searchKnowledge")
                        .description("search")
                        .inputSchema("{}")
                        .build());
        when(knowledge.getToolMetadata()).thenReturn(
                ToolMetadata.builder().returnDirect(false).build());

        RagChatToolRegistry registry = new RagChatToolRegistry(
                properties, knowledge, null, List.of(provider));

        assertEquals(2, registry.callbacks(ChatMode.AGENT, null).stream()
                .filter(callback -> callback.getToolDefinition().name()
                        .equals(RuntimeSkillToolProvider.LOAD_SKILL)
                        || callback.getToolDefinition().name()
                        .equals(RuntimeSkillToolProvider.READ_REFERENCE))
                .count());
        assertTrue(provider.getToolPolicies().values().stream()
                .allMatch(policy -> policy.maxCallsPerRequest()
                        <= properties.getAgent().getMaxToolCallsPerName()));
    }

    @Test
    void toolCallbacksRequireRequestLocalSkillSession() {
        RagChatProperties properties = new RagChatProperties();
        properties.getSkills().setEnabled(true);
        properties.getSkills().setLocations(List.of("classpath:skills-fixture/"));
        RuntimeSkillCatalog catalog = new RuntimeSkillCatalog(
                new ResourceCatalog(), properties);
        catalog.initialize();

        RuntimeSkillToolProvider provider = new RuntimeSkillToolProvider(
                catalog, properties, new ObjectMapper());
        var load = provider.getToolCallbacks().stream()
                .filter(callback -> "loadSkill".equals(
                        callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
        var reference = provider.getToolCallbacks().stream()
                .filter(callback -> "readSkillReference".equals(
                        callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();

        assertTrue(load.call("{\"skillName\":\"weather\"}",
                new ToolContext(Map.of())).contains("skill_session_missing"));

        RuntimeSkillLoadSession session = new RuntimeSkillLoadSession(1, 1, 4_000);
        ToolContext context = new ToolContext(Map.of(
                RuntimeSkillLoadSession.CONTEXT_KEY, session));
        assertTrue(load.call("{\"skillName\":\"weather\"}", context)
                .contains("weather.read"));
        assertTrue(reference.call(
                "{\"skillName\":\"weather\",\"relativePath\":\"api.md\"}",
                context).contains("configured city"));
        assertEquals("{\"error\":\"skill_reference_budget_exhausted\"}",
                reference.call(
                        "{\"skillName\":\"weather\",\"relativePath\":\"api.md\"}",
                        context));
    }
}
