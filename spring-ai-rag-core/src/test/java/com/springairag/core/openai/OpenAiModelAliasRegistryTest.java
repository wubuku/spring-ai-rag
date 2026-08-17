package com.springairag.core.openai;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.config.RagOpenAiCompatibilityProperties;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiModelAliasRegistryTest {

    @Test
    void resolvesAliasWithoutEncodingCollectionScope() {
        RagProperties properties = properties();
        OpenAiModelAliasRegistry registry =
                new OpenAiModelAliasRegistry(properties);

        OpenAiModelAliasRegistry.ResolvedAlias resolved =
                registry.resolve("rag-default", null, null);

        assertEquals("rag-default", resolved.alias());
        assertEquals(List.of("openrouter/model-a", "openrouter/model-b"),
                resolved.candidates());
        assertEquals(ChatMode.KNOWLEDGE, resolved.mode());
        assertEquals(MemoryMode.STATELESS, resolved.memory());
    }

    @Test
    void unknownAliasUsesStableModelNotFoundError() {
        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> new OpenAiModelAliasRegistry(properties())
                        .require("missing"));

        assertEquals(404, error.getStatus());
        assertEquals("model_not_found", error.getCode());
    }

    @Test
    void disallowedModeOverrideIsRejected() {
        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> new OpenAiModelAliasRegistry(properties())
                        .resolve("rag-default", ChatMode.AGENT, null));

        assertEquals("unsupported_parameter", error.getCode());
        assertEquals("rag.mode", error.getParam());
    }

    @Test
    void enabledRegistryRequiresAtLeastOneAlias() {
        assertThrows(IllegalStateException.class,
                () -> new OpenAiModelAliasRegistry(new RagProperties()));
    }

    private RagProperties properties() {
        RagProperties properties = new RagProperties();
        RagOpenAiCompatibilityProperties.ModelAlias alias =
                new RagOpenAiCompatibilityProperties.ModelAlias();
        alias.setCandidates(List.of(
                "openrouter/model-a", "openrouter/model-b"));
        properties.getOpenAiCompatibility().getModels()
                .put("rag-default", alias);
        return properties;
    }
}
