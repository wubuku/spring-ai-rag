package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagProperties 单元测试
 *
 * 验证默认值、setter/getter、嵌套类结构。
 */
class RagPropertiesTest {

    @Test
    void defaultValues_areCorrect() {
        RagProperties props = new RagProperties();

        // Embedding defaults
        assertEquals("", props.getEmbedding().getApiKey());
        assertEquals("https://api.siliconflow.cn/v1", props.getEmbedding().getBaseUrl());
        assertEquals("BAAI/bge-m3", props.getEmbedding().getModel());
        assertEquals(1024, props.getEmbedding().getDimensions());

        // Retrieval defaults
        assertEquals(0.5f, props.getRetrieval().getVectorWeight());
        assertEquals(0.5f, props.getRetrieval().getFulltextWeight());
        assertEquals(10, props.getRetrieval().getDefaultLimit());
        assertEquals(0.3f, props.getRetrieval().getMinScore());

        // QueryRewrite defaults
        assertTrue(props.getQueryRewrite().isEnabled());
        assertEquals(2, props.getQueryRewrite().getPaddingCount());
        assertTrue(props.getQueryRewrite().getSynonymDictionary().isEmpty());
        assertTrue(props.getQueryRewrite().getDomainQualifiers().isEmpty());

        // Rerank defaults
        assertFalse(props.getRerank().isEnabled());
        assertEquals(0.2f, props.getRerank().getDiversityWeight());

        // Memory defaults
        assertEquals(20, props.getMemory().getMaxMessages());

        // Chunk defaults
        assertEquals(1000, props.getChunk().getDefaultChunkSize());
        assertEquals(100, props.getChunk().getDefaultChunkOverlap());

        // Async defaults
        assertEquals(4, props.getAsync().getCorePoolSize());
        assertEquals(16, props.getAsync().getMaxPoolSize());
        assertEquals(100, props.getAsync().getQueueCapacity());
    }

    @Test
    void embedding_settersWork() {
        RagProperties props = new RagProperties();
        props.getEmbedding().setApiKey("sk-test");
        props.getEmbedding().setBaseUrl("https://custom.api.com");
        props.getEmbedding().setModel("custom-model");
        props.getEmbedding().setDimensions(768);

        assertEquals("sk-test", props.getEmbedding().getApiKey());
        assertEquals("https://custom.api.com", props.getEmbedding().getBaseUrl());
        assertEquals("custom-model", props.getEmbedding().getModel());
        assertEquals(768, props.getEmbedding().getDimensions());
    }

    @Test
    void retrieval_settersWork() {
        RagProperties props = new RagProperties();
        props.getRetrieval().setVectorWeight(0.7f);
        props.getRetrieval().setFulltextWeight(0.3f);
        props.getRetrieval().setDefaultLimit(20);
        props.getRetrieval().setMinScore(0.5f);

        assertEquals(0.7f, props.getRetrieval().getVectorWeight());
        assertEquals(0.3f, props.getRetrieval().getFulltextWeight());
        assertEquals(20, props.getRetrieval().getDefaultLimit());
        assertEquals(0.5f, props.getRetrieval().getMinScore());
    }

    @Test
    void queryRewrite_settersWork() {
        RagProperties props = new RagProperties();
        props.getQueryRewrite().setEnabled(false);
        props.getQueryRewrite().setPaddingCount(5);
        props.getQueryRewrite().setSynonymDictionary(Map.of("a", new String[]{"b"}));
        props.getQueryRewrite().setDomainQualifiers(List.of("q1", "q2"));

        assertFalse(props.getQueryRewrite().isEnabled());
        assertEquals(5, props.getQueryRewrite().getPaddingCount());
        assertEquals(1, props.getQueryRewrite().getSynonymDictionary().size());
        assertEquals(2, props.getQueryRewrite().getDomainQualifiers().size());
    }

    @Test
    void nestedObjects_areIndependent() {
        RagProperties props = new RagProperties();
        RagProperties props2 = new RagProperties();

        props.getMemory().setMaxMessages(50);
        assertEquals(50, props.getMemory().getMaxMessages());
        assertEquals(20, props2.getMemory().getMaxMessages(), "不同实例应独立");
    }
}
