package com.springairag.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EmbeddingModelConfig 单元测试
 */
class EmbeddingModelConfigTest {

    @Test
    @DisplayName("embeddingModel 创建 OpenAiEmbeddingModel，配置正确传递")
    void embeddingModel_createsOpenAiEmbeddingModel_withCorrectConfig() {
        RagEmbeddingProperties embedding = new RagEmbeddingProperties();
        embedding.setBaseUrl("https://api.siliconflow.cn/v1");
        embedding.setApiKey("test-siliconflow-key");
        embedding.setModel("BAAI/bge-m3");
        embedding.setDimensions(1024);

        RagProperties ragProperties = new RagProperties();
        ReflectionTestUtils.setField(ragProperties, "embedding", embedding);

        EmbeddingModelConfig config = new EmbeddingModelConfig(ragProperties);
        EmbeddingModel model = config.embeddingModel();

        assertNotNull(model);
        assertTrue(model instanceof OpenAiEmbeddingModel,
                "Expected OpenAiEmbeddingModel but got " + model.getClass().getName());
    }

    @Test
    @DisplayName("embeddingModel 使用正确维度配置 BAAI/bge-m3")
    void embeddingModel_usesCorrectDimensions() {
        RagEmbeddingProperties embedding = new RagEmbeddingProperties();
        embedding.setBaseUrl("https://api.siliconflow.cn/v1");
        embedding.setApiKey("test-key");
        embedding.setModel("BAAI/bge-m3");
        embedding.setDimensions(1024);

        RagProperties ragProperties = new RagProperties();
        ReflectionTestUtils.setField(ragProperties, "embedding", embedding);

        EmbeddingModelConfig config = new EmbeddingModelConfig(ragProperties);
        EmbeddingModel model = config.embeddingModel();

        assertNotNull(model);
        assertTrue(model instanceof OpenAiEmbeddingModel);
    }

    @Test
    @DisplayName("embeddingModel 支持自定义 baseUrl（兼容其他 OpenAI 兼容 API）")
    void embeddingModel_supportsCustomBaseUrl() {
        RagEmbeddingProperties embedding = new RagEmbeddingProperties();
        embedding.setBaseUrl("https://api.example.com/v1");
        embedding.setApiKey("custom-key");
        embedding.setModel("custom-embedding-model");
        embedding.setDimensions(1536);

        RagProperties ragProperties = new RagProperties();
        ReflectionTestUtils.setField(ragProperties, "embedding", embedding);

        EmbeddingModelConfig config = new EmbeddingModelConfig(ragProperties);
        EmbeddingModel model = config.embeddingModel();

        assertNotNull(model);
        assertTrue(model instanceof OpenAiEmbeddingModel);
    }
}
