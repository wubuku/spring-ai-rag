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
        assertEquals("https://api.siliconflow.cn", props.getEmbedding().getBaseUrl());
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
        assertEquals(100, props.getChunk().getMinChunkSize());

        // Async defaults
        assertEquals(4, props.getAsync().getCorePoolSize());
        assertEquals(16, props.getAsync().getMaxPoolSize());
        assertEquals(100, props.getAsync().getQueueCapacity());
        assertEquals(5, props.getAsync().getRetrievalTimeoutSeconds());
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

    @Test
    void security_defaults() {
        RagProperties props = new RagProperties();
        assertEquals("", props.getSecurity().getApiKey());
        assertFalse(props.getSecurity().isEnabled());
    }

    @Test
    void security_setters() {
        RagProperties props = new RagProperties();
        props.getSecurity().setApiKey("sk-test-key");
        props.getSecurity().setEnabled(true);

        assertEquals("sk-test-key", props.getSecurity().getApiKey());
        assertTrue(props.getSecurity().isEnabled());
    }

    @Test
    void rerank_setters() {
        RagProperties props = new RagProperties();
        props.getRerank().setEnabled(true);
        props.getRerank().setDiversityWeight(0.5f);

        assertTrue(props.getRerank().isEnabled());
        assertEquals(0.5f, props.getRerank().getDiversityWeight());
    }

    @Test
    void chunk_setters() {
        RagProperties props = new RagProperties();
        props.getChunk().setDefaultChunkSize(500);
        props.getChunk().setDefaultChunkOverlap(50);
        props.getChunk().setMinChunkSize(200);

        assertEquals(500, props.getChunk().getDefaultChunkSize());
        assertEquals(50, props.getChunk().getDefaultChunkOverlap());
        assertEquals(200, props.getChunk().getMinChunkSize());
    }

    @Test
    void async_setters() {
        RagProperties props = new RagProperties();
        props.getAsync().setCorePoolSize(8);
        props.getAsync().setMaxPoolSize(32);
        props.getAsync().setQueueCapacity(200);
        props.getAsync().setRetrievalTimeoutSeconds(10);

        assertEquals(8, props.getAsync().getCorePoolSize());
        assertEquals(32, props.getAsync().getMaxPoolSize());
        assertEquals(200, props.getAsync().getQueueCapacity());
        assertEquals(10, props.getAsync().getRetrievalTimeoutSeconds());
    }

    @Test
    void memory_setters() {
        RagProperties props = new RagProperties();
        props.getMemory().setMaxMessages(100);

        assertEquals(100, props.getMemory().getMaxMessages());
    }

    @Test
    void rateLimit_defaults() {
        RagProperties props = new RagProperties();
        assertFalse(props.getRateLimit().isEnabled());
        assertEquals(60, props.getRateLimit().getRequestsPerMinute());
    }

    @Test
    void rateLimit_setters() {
        RagProperties props = new RagProperties();
        props.getRateLimit().setEnabled(true);
        props.getRateLimit().setRequestsPerMinute(120);

        assertTrue(props.getRateLimit().isEnabled());
        assertEquals(120, props.getRateLimit().getRequestsPerMinute());
    }
}
