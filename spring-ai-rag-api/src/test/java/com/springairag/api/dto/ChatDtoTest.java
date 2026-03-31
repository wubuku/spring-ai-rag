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
}
