package com.springairag.api.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API DTO 类综合测试
 */
class DtoTest {

    // ========== BatchDocumentRequest ==========

    @Test
    void batchDocumentRequest_getterSetter() {
        BatchDocumentRequest req = new BatchDocumentRequest();
        assertNull(req.getDocuments());

        List<DocumentRequest> docs = List.of(new DocumentRequest());
        req.setDocuments(docs);
        assertEquals(docs, req.getDocuments());
    }

    @Test
    void batchDocumentRequest_constructor() {
        List<DocumentRequest> docs = List.of(new DocumentRequest());
        BatchDocumentRequest req = new BatchDocumentRequest(docs);
        assertEquals(docs, req.getDocuments());
    }

    // ========== CollectionRequest ==========

    @Test
    void collectionRequest_defaults() {
        CollectionRequest req = new CollectionRequest();
        assertNull(req.getName());
        assertNull(req.getDescription());
        assertNull(req.getEmbeddingModel());
        assertEquals(1024, req.getDimensions());
        assertTrue(req.getEnabled());
        assertNull(req.getMetadata());
    }

    @Test
    void collectionRequest_getterSetter() {
        CollectionRequest req = new CollectionRequest();
        req.setName("test-collection");
        req.setDescription("测试集合");
        req.setEmbeddingModel("bge-m3");
        req.setDimensions(512);
        req.setEnabled(false);
        req.setMetadata(Map.of("key", "value"));

        assertEquals("test-collection", req.getName());
        assertEquals("测试集合", req.getDescription());
        assertEquals("bge-m3", req.getEmbeddingModel());
        assertEquals(512, req.getDimensions());
        assertFalse(req.getEnabled());
        assertEquals("value", req.getMetadata().get("key"));
    }

    // ========== EvaluateRequest ==========

    @Test
    void evaluateRequest_constructor() {
        EvaluateRequest req = new EvaluateRequest("query", List.of(1L, 2L), List.of(1L, 3L));
        assertEquals("query", req.getQuery());
        assertEquals(List.of(1L, 2L), req.getRetrievedDocIds());
        assertEquals(List.of(1L, 3L), req.getRelevantDocIds());
        assertEquals("AUTO", req.getEvaluationMethod());
    }

    @Test
    void evaluateRequest_getterSetter() {
        EvaluateRequest req = new EvaluateRequest();
        req.setQuery("Spring AI 配置");
        req.setRetrievedDocIds(List.of(1L));
        req.setRelevantDocIds(List.of(1L, 2L));
        req.setEvaluationMethod("MANUAL");
        req.setEvaluatorId("user-001");

        assertEquals("Spring AI 配置", req.getQuery());
        assertEquals(List.of(1L), req.getRetrievedDocIds());
        assertEquals(List.of(1L, 2L), req.getRelevantDocIds());
        assertEquals("MANUAL", req.getEvaluationMethod());
        assertEquals("user-001", req.getEvaluatorId());
    }

    // ========== FeedbackRequest ==========

    @Test
    void feedbackRequest_getterSetter() {
        FeedbackRequest req = new FeedbackRequest();
        req.setSessionId("session-123");
        req.setQuery("如何配置向量数据库？");
        req.setFeedbackType("THUMBS_UP");
        req.setRating(5);
        req.setComment("回答很有帮助");
        req.setRetrievedDocumentIds(List.of(1L, 2L));
        req.setSelectedDocumentIds(List.of(1L));
        req.setDwellTimeMs(3000L);

        assertEquals("session-123", req.getSessionId());
        assertEquals("如何配置向量数据库？", req.getQuery());
        assertEquals("THUMBS_UP", req.getFeedbackType());
        assertEquals(5, req.getRating());
        assertEquals("回答很有帮助", req.getComment());
        assertEquals(List.of(1L, 2L), req.getRetrievedDocumentIds());
        assertEquals(List.of(1L), req.getSelectedDocumentIds());
        assertEquals(3000L, req.getDwellTimeMs());
    }

    // ========== ChatResponse ==========

    @Test
    void chatResponse_getterSetter() {
        ChatResponse response = new ChatResponse();
        response.setAnswer("AI 回答");
        response.setMetadata(Map.of("model", "deepseek"));

        assertEquals("AI 回答", response.getAnswer());
        assertEquals("deepseek", response.getMetadata().get("model"));
    }

    @Test
    void chatResponse_constructor() {
        ChatResponse response = new ChatResponse("answer");
        assertEquals("answer", response.getAnswer());
    }

    @Test
    void chatResponse_builder() {
        ChatResponse.SourceDocument src = new ChatResponse.SourceDocument();
        src.setDocumentId("doc-1");
        src.setChunkText("chunk");
        src.setScore(0.9);

        ChatResponse response = ChatResponse.builder()
                .answer("回答")
                .sources(List.of(src))
                .metadata(Map.of("key", "val"))
                .build();

        assertEquals("回答", response.getAnswer());
        assertEquals(1, response.getSources().size());
        assertEquals("doc-1", response.getSources().get(0).getDocumentId());
    }

    @Test
    void chatResponse_sourceDocument() {
        ChatResponse.SourceDocument doc = new ChatResponse.SourceDocument();
        doc.setDocumentId("doc-1");
        doc.setChunkText("chunk text");
        doc.setScore(0.95);

        assertEquals("doc-1", doc.getDocumentId());
        assertEquals("chunk text", doc.getChunkText());
        assertEquals(0.95, doc.getScore());
    }

    // ========== SearchRequest ==========

    @Test
    void searchRequest_defaults() {
        SearchRequest req = new SearchRequest();
        assertNull(req.getQuery());
        assertNull(req.getDocumentIds());
        assertNull(req.getConfig());
    }

    @Test
    void searchRequest_getterSetter() {
        SearchRequest req = new SearchRequest();
        req.setQuery("测试查询");
        req.setDocumentIds(List.of(1L, 2L));
        req.setConfig(new RetrievalConfig());

        assertEquals("测试查询", req.getQuery());
        assertEquals(List.of(1L, 2L), req.getDocumentIds());
        assertNotNull(req.getConfig());
    }

    @Test
    void searchRequest_constructor() {
        SearchRequest req = new SearchRequest("query");
        assertEquals("query", req.getQuery());
    }

    // ========== DocumentRequest ==========

    @Test
    void documentRequest_getterSetter() {
        DocumentRequest req = new DocumentRequest();
        req.setTitle("文档标题");
        req.setContent("文档内容");
        req.setSource("test.pdf");
        req.setDocumentType("pdf");
        req.setMetadata(Map.of("author", "test"));

        assertEquals("文档标题", req.getTitle());
        assertEquals("文档内容", req.getContent());
        assertEquals("test.pdf", req.getSource());
        assertEquals("pdf", req.getDocumentType());
        assertEquals("test", req.getMetadata().get("author"));
    }

    @Test
    void documentRequest_constructor() {
        DocumentRequest req = new DocumentRequest("title", "content");
        assertEquals("title", req.getTitle());
        assertEquals("content", req.getContent());
    }

    // ========== RetrievalResult ==========

    @Test
    void retrievalResult_getterSetter() {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId("doc-1");
        result.setChunkText("chunk text");
        result.setScore(0.88);
        result.setVectorScore(0.9);
        result.setFulltextScore(0.7);
        result.setChunkIndex(2);
        result.setMetadata(Map.of("source", "file.txt"));

        assertEquals("doc-1", result.getDocumentId());
        assertEquals("chunk text", result.getChunkText());
        assertEquals(0.88, result.getScore());
        assertEquals(0.9, result.getVectorScore());
        assertEquals(0.7, result.getFulltextScore());
        assertEquals(2, result.getChunkIndex());
        assertEquals("file.txt", result.getMetadata().get("source"));
    }

    // ========== RetrievalConfig ==========

    @Test
    void retrievalConfig_defaults() {
        RetrievalConfig config = new RetrievalConfig();
        assertEquals(10, config.getMaxResults());
        assertEquals(0.5, config.getMinScore());
        assertTrue(config.isUseHybridSearch());
        assertTrue(config.isUseRerank());
        assertEquals(0.5, config.getVectorWeight());
        assertEquals(0.5, config.getFulltextWeight());
    }

    @Test
    void retrievalConfig_getterSetter() {
        RetrievalConfig config = new RetrievalConfig();
        config.setMaxResults(20);
        config.setMinScore(0.3);
        config.setUseHybridSearch(false);
        config.setUseRerank(false);
        config.setVectorWeight(0.7);
        config.setFulltextWeight(0.3);

        assertEquals(20, config.getMaxResults());
        assertEquals(0.3, config.getMinScore());
        assertFalse(config.isUseHybridSearch());
        assertFalse(config.isUseRerank());
        assertEquals(0.7, config.getVectorWeight());
        assertEquals(0.3, config.getFulltextWeight());
    }

    // ========== ChatRequest ==========

    @Test
    void chatRequest_defaults() {
        ChatRequest req = new ChatRequest();
        assertNull(req.getMessage());
        assertNull(req.getSessionId());
        assertEquals(5, req.getMaxResults());
        assertTrue(req.isUseHybridSearch());
        assertTrue(req.isUseRerank());
    }

    @Test
    void chatRequest_getterSetter() {
        ChatRequest req = new ChatRequest();
        req.setMessage("你好");
        req.setSessionId("s-1");
        req.setDomainId("medical");
        req.setMaxResults(10);
        req.setUseHybridSearch(false);
        req.setUseRerank(false);

        assertEquals("你好", req.getMessage());
        assertEquals("s-1", req.getSessionId());
        assertEquals("medical", req.getDomainId());
        assertEquals(10, req.getMaxResults());
        assertFalse(req.isUseHybridSearch());
        assertFalse(req.isUseRerank());
    }

    @Test
    void chatRequest_constructor() {
        ChatRequest req = new ChatRequest("hello", "session-1");
        assertEquals("hello", req.getMessage());
        assertEquals("session-1", req.getSessionId());
    }
}
