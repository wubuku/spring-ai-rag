package com.springairag.core.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.RagChatToolPolicy;
import com.springairag.api.service.RagChatToolProvider;
import com.springairag.core.config.RagChatProperties;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-owned Level 2/3 Skill tools.
 */
@Component
public final class RuntimeSkillToolProvider implements RagChatToolProvider {

    public static final String LOAD_SKILL = "loadSkill";
    public static final String READ_REFERENCE = "readSkillReference";

    private static final ToolDefinition LOAD_DEFINITION =
            ToolDefinition.builder()
                    .name(LOAD_SKILL)
                    .description("Load one registered runtime Skill's bounded, untrusted instructions. "
                            + "Load a Skill before using its capability.")
                    .inputSchema("""
                            {"type":"object","properties":{"skillName":{"type":"string","description":"Registered Skill name"}},"required":["skillName"],"additionalProperties":false}
                            """)
                    .build();
    private static final ToolDefinition REFERENCE_DEFINITION =
            ToolDefinition.builder()
                    .name(READ_REFERENCE)
                    .description("Read one bounded reference file from an already loaded runtime Skill.")
                    .inputSchema("""
                            {"type":"object","properties":{"skillName":{"type":"string"},"relativePath":{"type":"string","description":"Path relative to the Skill references directory"}},"required":["skillName","relativePath"],"additionalProperties":false}
                            """)
                    .build();

    private final RuntimeSkillCatalog catalog;
    private final RagChatProperties properties;
    private final ObjectMapper objectMapper;
    private final List<ToolCallback> callbacks;

    public RuntimeSkillToolProvider(
            RuntimeSkillCatalog catalog,
            RagChatProperties properties,
            ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.callbacks = List.of(
                new LoadSkillCallback(),
                new ReadReferenceCallback());
    }

    @Override
    public String getName() {
        return "runtime-skills";
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Set<ChatMode> supportedModes() {
        return Set.of(ChatMode.AGENT);
    }

    @Override
    public List<ToolCallback> getToolCallbacks() {
        return catalog.enabled() ? callbacks : List.of();
    }

    @Override
    public Map<String, RagChatToolPolicy> getToolPolicies() {
        if (!catalog.enabled()) {
            return Map.of();
        }
        RagChatProperties.SkillProperties skill = properties.getSkills();
        RagChatProperties.AgentProperties agent = properties.getAgent();
        int bodyLimit = Math.min(
                agent.getMaxToolResultCharacters(),
                skill.getMaxCatalogCharacters());
        int referenceLimit = Math.min(
                agent.getMaxToolResultCharacters(),
                skill.getMaxReferenceBytes());
        int perToolCallLimit = agent.getMaxToolCallsPerName();
        return Map.of(
                LOAD_SKILL,
                new RagChatToolPolicy(
                        RagChatToolPolicy.Effect.READ_ONLY,
                        Math.min(skill.getMaxLoadsPerRequest(), perToolCallLimit),
                        Math.max(1_024, bodyLimit),
                        Duration.ofSeconds(10)),
                READ_REFERENCE,
                new RagChatToolPolicy(
                        RagChatToolPolicy.Effect.READ_ONLY,
                        Math.min(
                                skill.getMaxReferenceReadsPerRequest(),
                                perToolCallLimit),
                        Math.max(1_024, referenceLimit),
                        Duration.ofSeconds(10)));
    }

    private abstract class SkillCallback implements ToolCallback {
        protected RuntimeSkillLoadSession session(ToolContext context) {
            if (context == null || context.getContext() == null) {
                return null;
            }
            Object value = context.getContext().get(
                    RuntimeSkillLoadSession.CONTEXT_KEY);
            return value instanceof RuntimeSkillLoadSession found ? found : null;
        }

        protected JsonNode input(String value) {
            try {
                return objectMapper.readTree(
                        value == null || value.isBlank() ? "{}" : value);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid runtime Skill tool input", e);
            }
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(false).build();
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException(
                    "Missing server-owned runtime Skill context");
        }
    }

    private final class LoadSkillCallback extends SkillCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return LOAD_DEFINITION;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            RuntimeSkillLoadSession session = session(toolContext);
            String name = input(toolInput).path("skillName").asText("").trim();
            return catalog.loadBody(
                    name,
                    session,
                    properties.getSkills().getMaxCatalogCharacters());
        }
    }

    private final class ReadReferenceCallback extends SkillCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return REFERENCE_DEFINITION;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            RuntimeSkillLoadSession session = session(toolContext);
            JsonNode value = input(toolInput);
            return catalog.readReference(
                    value.path("skillName").asText("").trim(),
                    value.path("relativePath").asText(""),
                    session,
                    properties.getSkills().getMaxReferenceBytes());
        }
    }
}
