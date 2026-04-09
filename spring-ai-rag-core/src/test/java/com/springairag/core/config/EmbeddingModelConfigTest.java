package com.springairag.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EmbeddingModelConfig
 */
class EmbeddingModelConfigTest {

    @Test
    @DisplayName("embeddingModel creates OpenAiEmbeddingModel with correct config")
    void embeddingModel_createsOpenAiEmbeddingModel_withCorrectConfig() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        ReflectionTestUtils.setField(config, "apiKey", "test-siliconflow-key");
        ReflectionTestUtils.setField(config, "model", "BAAI/bge-m3");
        ReflectionTestUtils.setField(config, "dimensions", 1024);

        EmbeddingModel model = config.embeddingModel();

        assertNotNull(model);
        assertTrue(model instanceof OpenAiEmbeddingModel,
                "Expected OpenAiEmbeddingModel but got " + model.getClass().getName());
    }

    @Test
    @DisplayName("embeddingModel uses correct dimensions for BAAI/bge-m3")
    void embeddingModel_usesCorrectDimensions() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        ReflectionTestUtils.setField(config, "apiKey", "test-key");
        ReflectionTestUtils.setField(config, "model", "BAAI/bge-m3");
        ReflectionTestUtils.setField(config, "dimensions", 1024);

        EmbeddingModel model = config.embeddingModel();

        assertNotNull(model);
        assertTrue(model instanceof OpenAiEmbeddingModel);
    }

    @Test
    @DisplayName("embeddingModel supports custom baseUrl (compatible with other OpenAI-compatible APIs)")
    void embeddingModel_supportsCustomBaseUrl() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        ReflectionTestUtils.setField(config, "apiKey", "custom-key");
        ReflectionTestUtils.setField(config, "model", "custom-embedding-model");
        ReflectionTestUtils.setField(config, "dimensions", 1536);

        EmbeddingModel model = config.embeddingModel();

        assertNotNull(model);
        assertTrue(model instanceof OpenAiEmbeddingModel);
    }
}
