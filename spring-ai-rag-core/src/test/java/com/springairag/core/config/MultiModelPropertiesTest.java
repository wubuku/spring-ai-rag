package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MultiModelProperties.
 */
class MultiModelPropertiesTest {

    @Test
    void defaults_configFileIsNull() {
        MultiModelProperties props = new MultiModelProperties();
        assertNull(props.getConfigFile());
    }

    @Test
    void defaults_providersIsEmpty() {
        MultiModelProperties props = new MultiModelProperties();
        assertTrue(props.getProviders().isEmpty());
    }

    @Test
    void defaults_chatModelIsNull() {
        MultiModelProperties props = new MultiModelProperties();
        assertNull(props.getChatModel());
    }

    @Test
    void defaults_embeddingModelIsNull() {
        MultiModelProperties props = new MultiModelProperties();
        assertNull(props.getEmbeddingModel());
    }

    @Test
    void setters_updateAllValues() {
        MultiModelProperties props = new MultiModelProperties();

        props.setConfigFile("/etc/models.json");

        MultiModelProperties.ProviderConfig provider = new MultiModelProperties.ProviderConfig(
                "MiniMax", "https://api.minimaxi.com", "sk-test",
                "openai-chat", true, 1,
                List.of(new MultiModelProperties.ModelItem(
                        "MiniMax-M2.7", "MiniMax M2.7", "chat",
                        false, List.of("text"), null, 200000, 8192, null))
        );
        props.setProviders(Map.of("minimax", provider));

        MultiModelProperties.ModelRouting chatRouting = new MultiModelProperties.ModelRouting(
                "minimax/MiniMax-M2.7", List.of()
        );
        props.setChatModel(chatRouting);

        assertEquals("/etc/models.json", props.getConfigFile());
        assertEquals(1, props.getProviders().size());
        assertEquals("MiniMax", props.getProviders().get("minimax").displayName());
        assertEquals("minimax/MiniMax-M2.7", props.getChatModel().primary());
    }

    @Test
    void getProviderByModelRef_returnsProviderForValidRef() {
        MultiModelProperties props = new MultiModelProperties();
        MultiModelProperties.ProviderConfig provider = new MultiModelProperties.ProviderConfig(
                "OpenAI", "https://api.openai.com", "sk-test",
                "openai-chat", true, 1, List.of()
        );
        props.setProviders(Map.of("openai", provider));

        MultiModelProperties.ProviderConfig found = props.getProviderByModelRef("openai/gpt-4o");
        assertNotNull(found);
        assertEquals("OpenAI", found.displayName());
    }

    @Test
    void getProviderByModelRef_returnsNullForNull() {
        MultiModelProperties props = new MultiModelProperties();
        assertNull(props.getProviderByModelRef(null));
    }

    @Test
    void getProviderByModelRef_returnsNullForNoSlash() {
        MultiModelProperties props = new MultiModelProperties();
        assertNull(props.getProviderByModelRef("justmodel"));
    }

    @Test
    void getProviderByModelRef_returnsNullForUnknownProvider() {
        MultiModelProperties props = new MultiModelProperties();
        assertNull(props.getProviderByModelRef("unknown/model"));
    }

    @Test
    void getModelItem_returnsModelForValidRef() {
        MultiModelProperties props = new MultiModelProperties();
        MultiModelProperties.ModelItem model = new MultiModelProperties.ModelItem(
                "gpt-4o", "GPT-4o", "chat",
                false, List.of("text"), null, 200000, 8192, null
        );
        MultiModelProperties.ProviderConfig provider = new MultiModelProperties.ProviderConfig(
                "OpenAI", "https://api.openai.com", "sk-test",
                "openai-chat", true, 1, List.of(model)
        );
        props.setProviders(Map.of("openai", provider));

        MultiModelProperties.ModelItem found = props.getModelItem("openai/gpt-4o");
        assertNotNull(found);
        assertEquals("gpt-4o", found.id());
    }

    @Test
    void getModelItem_returnsNullForUnknownModel() {
        MultiModelProperties props = new MultiModelProperties();
        assertNull(props.getModelItem("openai/unknown"));
    }

    @Test
    void modelRouting_fallbacksDefaultsToEmptyList() {
        MultiModelProperties.ModelRouting routing = new MultiModelProperties.ModelRouting(
                "openai/gpt-4o", null
        );
        assertNotNull(routing.fallbacks());
        assertTrue(routing.fallbacks().isEmpty());
    }

    @Test
    void modelCost_negativeValuesNormalizedToZero() {
        MultiModelProperties.ModelCost cost = new MultiModelProperties.ModelCost(-1.0, -2.0, -3.0, -4.0);
        assertEquals(0.0, cost.input());
        assertEquals(0.0, cost.output());
        assertEquals(0.0, cost.cacheRead());
        assertEquals(0.0, cost.cacheWrite());
    }

    @Test
    void providerConfig_findModel_returnsModelById() {
        MultiModelProperties.ModelItem model = new MultiModelProperties.ModelItem(
                "gpt-4o", "GPT-4o", "chat",
                false, List.of("text"), null, 200000, 8192, null
        );
        MultiModelProperties.ProviderConfig provider = new MultiModelProperties.ProviderConfig(
                "OpenAI", "https://api.openai.com", "sk-test",
                "openai-chat", true, 1, List.of(model)
        );

        MultiModelProperties.ModelItem found = provider.findModel("gpt-4o");
        assertNotNull(found);
        assertEquals("gpt-4o", found.id());
    }

    @Test
    void providerConfig_findModel_returnsNullForUnknown() {
        MultiModelProperties.ProviderConfig provider = new MultiModelProperties.ProviderConfig(
                "OpenAI", "https://api.openai.com", "sk-test",
                "openai-chat", true, 1, List.of()
        );
        assertNull(provider.findModel("unknown"));
    }

    @Test
    void providerConfig_chatModels_filtersCorrectly() {
        MultiModelProperties.ModelItem chatModel = new MultiModelProperties.ModelItem(
                "gpt-4o", "GPT-4o", "chat",
                false, List.of("text"), null, 200000, 8192, null
        );
        MultiModelProperties.ModelItem embeddingModel = new MultiModelProperties.ModelItem(
                "text-embedding-3-large", "Embedding 3 Large", "embedding",
                false, List.of("text"), null, null, null, 3072
        );
        MultiModelProperties.ProviderConfig provider = new MultiModelProperties.ProviderConfig(
                "OpenAI", "https://api.openai.com", "sk-test",
                "openai-chat", true, 1, List.of(chatModel, embeddingModel)
        );

        List<MultiModelProperties.ModelItem> chatModels = provider.chatModels();
        assertEquals(1, chatModels.size());
        assertEquals("gpt-4o", chatModels.get(0).id());
    }

    @Test
    void providerConfig_embeddingModels_filtersCorrectly() {
        MultiModelProperties.ModelItem chatModel = new MultiModelProperties.ModelItem(
                "gpt-4o", "GPT-4o", "chat",
                false, List.of("text"), null, 200000, 8192, null
        );
        MultiModelProperties.ModelItem embeddingModel = new MultiModelProperties.ModelItem(
                "text-embedding-3-large", "Embedding 3 Large", "embedding",
                false, List.of("text"), null, null, null, 3072
        );
        MultiModelProperties.ProviderConfig provider = new MultiModelProperties.ProviderConfig(
                "OpenAI", "https://api.openai.com", "sk-test",
                "openai-chat", true, 1, List.of(chatModel, embeddingModel)
        );

        List<MultiModelProperties.ModelItem> embeddingModels = provider.embeddingModels();
        assertEquals(1, embeddingModels.size());
        assertEquals("text-embedding-3-large", embeddingModels.get(0).id());
    }

    @Test
    void modelItem_isChatAndIsEmbedding() {
        MultiModelProperties.ModelItem chatModel = new MultiModelProperties.ModelItem(
                "gpt-4o", "GPT-4o", "chat",
                false, List.of("text"), null, 200000, 8192, null
        );
        MultiModelProperties.ModelItem embeddingModel = new MultiModelProperties.ModelItem(
                "text-embedding-3-large", "Embedding 3 Large", "embedding",
                false, List.of("text"), null, null, null, 3072
        );

        assertTrue(chatModel.isChat());
        assertFalse(chatModel.isEmbedding());
        assertTrue(embeddingModel.isEmbedding());
        assertFalse(embeddingModel.isChat());
    }

    @Test
    void modelItem_caseInsensitiveType() {
        MultiModelProperties.ModelItem upperCase = new MultiModelProperties.ModelItem(
                "gpt-4o", "GPT-4o", "CHAT",
                false, List.of("text"), null, 200000, 8192, null
        );
        MultiModelProperties.ModelItem mixedCase = new MultiModelProperties.ModelItem(
                "gpt-4o-mini", "GPT-4o Mini", "Chat",
                false, List.of("text"), null, 200000, 8192, null
        );

        assertTrue(upperCase.isChat());
        assertTrue(mixedCase.isChat());
    }
}
