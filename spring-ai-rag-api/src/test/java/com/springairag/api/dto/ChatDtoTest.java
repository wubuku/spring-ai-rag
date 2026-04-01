package com.springairag.api.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatRequest / ChatResponse 基本测试
 */
class ChatDtoTest {

    @Test
    void chatRequest_defaultValues() {
        ChatRequest req = new ChatRequest();
        assertEquals(5, req.getMaxResults());
        assertTrue(req.isUseHybridSearch());
        assertTrue(req.isUseRerank());
    }

    @Test
    void chatRequest_constructor() {
        ChatRequest req = new ChatRequest("你好", "session-1");
        assertEquals("你好", req.getMessage());
        assertEquals("session-1", req.getSessionId());
    }

    @Test
    void chatRequest_metadata() {
        ChatRequest req = new ChatRequest();
        req.setMetadata(Map.of("domainId", "skin"));
        assertEquals("skin", req.getMetadata().get("domainId"));
    }

    @Test
    void chatResponse_builder() {
        ChatResponse resp = ChatResponse.builder()
                .answer("回答内容")
                .metadata(Map.of("sessionId", "s1"))
                .build();
        assertEquals("回答内容", resp.getAnswer());
        assertEquals("s1", resp.getMetadata().get("sessionId"));
    }

    @Test
    void retrievalConfig_builder() {
        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(20)
                .minScore(0.7)
                .vectorWeight(0.6)
                .fulltextWeight(0.4)
                .build();
        assertEquals(20, config.getMaxResults());
        assertEquals(0.7, config.getMinScore(), 0.001);
        assertEquals(0.6, config.getVectorWeight(), 0.001);
        assertEquals(0.4, config.getFulltextWeight(), 0.001);
        assertTrue(config.isUseHybridSearch());
    }

    @Test
    void retrievalResult_setters() {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId("doc-1");
        result.setChunkText("分块内容");
        result.setScore(0.92);
        result.setVectorScore(0.95);
        result.setFulltextScore(0.88);
        result.setChunkIndex(3);

        assertEquals("doc-1", result.getDocumentId());
        assertEquals("分块内容", result.getChunkText());
        assertEquals(0.92, result.getScore(), 0.001);
        assertEquals(3, result.getChunkIndex());
    }

    @Test
    void documentRequest_defaultConstructor() {
        DocumentRequest req = new DocumentRequest();
        assertNull(req.getTitle());
        assertNull(req.getContent());
        assertNull(req.getSource());
        assertNull(req.getDocumentType());
        assertNull(req.getMetadata());
    }

    @Test
    void documentRequest_constructorWithFields() {
        DocumentRequest req = new DocumentRequest("产品手册", "这是产品手册内容");
        assertEquals("产品手册", req.getTitle());
        assertEquals("这是产品手册内容", req.getContent());
    }

    @Test
    void documentRequest_setters() {
        DocumentRequest req = new DocumentRequest();
        req.setTitle("标题");
        req.setContent("内容");
        req.setSource("api-upload");
        req.setDocumentType("markdown");
        req.setMetadata(Map.of("key", "value"));

        assertEquals("标题", req.getTitle());
        assertEquals("api-upload", req.getSource());
        assertEquals("markdown", req.getDocumentType());
        assertEquals("value", req.getMetadata().get("key"));
    }

    @Test
    void searchRequest_defaultConstructor() {
        SearchRequest req = new SearchRequest();
        assertNull(req.getQuery());
        assertNull(req.getDocumentIds());
        assertNull(req.getConfig());
    }

    @Test
    void searchRequest_constructorWithQuery() {
        SearchRequest req = new SearchRequest("Spring AI");
        assertEquals("Spring AI", req.getQuery());
    }

    @Test
    void searchRequest_setters() {
        SearchRequest req = new SearchRequest();
        req.setQuery("测试查询");
        req.setDocumentIds(java.util.List.of(1L, 2L, 3L));
        req.setConfig(RetrievalConfig.builder().maxResults(10).build());

        assertEquals("测试查询", req.getQuery());
        assertEquals(3, req.getDocumentIds().size());
        assertEquals(10, req.getConfig().getMaxResults());
    }
}
