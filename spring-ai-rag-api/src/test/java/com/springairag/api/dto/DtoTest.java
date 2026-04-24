package com.springairag.api.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;

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

    @Test
    void chatResponse_stepMetrics_setterGetter() {
        ChatResponse response = new ChatResponse();
        List<ChatResponse.StepMetricRecord> metrics = List.of(
                new ChatResponse.StepMetricRecord("HybridSearch", 23, 12),
                new ChatResponse.StepMetricRecord("Rerank", 5, 8)
        );
        response.setStepMetrics(metrics);

        assertEquals(2, response.getStepMetrics().size());
        assertEquals("HybridSearch", response.getStepMetrics().get(0).getStepName());
        assertEquals(23, response.getStepMetrics().get(0).getDurationMs());
        assertEquals(12, response.getStepMetrics().get(0).getResultCount());
        assertEquals("Rerank", response.getStepMetrics().get(1).getStepName());
        assertEquals(5, response.getStepMetrics().get(1).getDurationMs());
        assertEquals(8, response.getStepMetrics().get(1).getResultCount());
    }

    @Test
    void stepMetricRecord_constructorAndGetters() {
        ChatResponse.StepMetricRecord record = new ChatResponse.StepMetricRecord("QueryRewrite", 15, 5);
        assertEquals("QueryRewrite", record.getStepName());
        assertEquals(15, record.getDurationMs());
        assertEquals(5, record.getResultCount());
    }

    @Test
    void stepMetricRecord_defaultConstructorAndSetters() {
        ChatResponse.StepMetricRecord record = new ChatResponse.StepMetricRecord();
        record.setStepName("Embedding");
        record.setDurationMs(120);
        record.setResultCount(10);

        assertEquals("Embedding", record.getStepName());
        assertEquals(120, record.getDurationMs());
        assertEquals(10, record.getResultCount());
    }

    // ========== ChatResponse ==========

    @Test
    void chatResponse_equals_sameInstance() {
        ChatResponse r = new ChatResponse("answer");
        assertEquals(r, r);
        assertEquals(r.hashCode(), r.hashCode());
    }

    @Test
    void chatResponse_equals_sameFields() {
        ChatResponse r1 = new ChatResponse("answer");
        r1.setTraceId("trace-1");
        ChatResponse r2 = new ChatResponse("answer");
        r2.setTraceId("trace-1");
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void chatResponse_equals_differentAnswer_notEqual() {
        ChatResponse r1 = new ChatResponse("answer1");
        ChatResponse r2 = new ChatResponse("answer2");
        assertNotEquals(r1, r2);
    }

    @Test
    void chatResponse_equals_differentTraceId_notEqual() {
        ChatResponse r1 = new ChatResponse("answer");
        r1.setTraceId("trace-1");
        ChatResponse r2 = new ChatResponse("answer");
        r2.setTraceId("trace-2");
        assertNotEquals(r1, r2);
    }

    @Test
    void chatResponse_equals_nullTraceId_sameAnswer_equal() {
        ChatResponse r1 = new ChatResponse("answer");
        ChatResponse r2 = new ChatResponse("answer");
        assertEquals(r1, r2);
    }

    @Test
    void chatResponse_toString_containsKeyFields() {
        ChatResponse r = new ChatResponse("The answer is 42");
        r.setTraceId("abc123");

        String str = r.toString();
        assertTrue(str.contains("ChatResponse"));
        assertTrue(str.contains("The answer is 42"));
        assertTrue(str.contains("abc123"));
    }

    // ========== StepMetricRecord ==========

    @Test
    void stepMetricRecord_equals_sameFields() {
        ChatResponse.StepMetricRecord r1 = new ChatResponse.StepMetricRecord("HybridSearch", 23, 12);
        ChatResponse.StepMetricRecord r2 = new ChatResponse.StepMetricRecord("HybridSearch", 23, 12);
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void stepMetricRecord_equals_differentStepName_notEqual() {
        ChatResponse.StepMetricRecord r1 = new ChatResponse.StepMetricRecord("HybridSearch", 23, 12);
        ChatResponse.StepMetricRecord r2 = new ChatResponse.StepMetricRecord("Rerank", 23, 12);
        assertNotEquals(r1, r2);
    }

    @Test
    void stepMetricRecord_equals_differentDuration_notEqual() {
        ChatResponse.StepMetricRecord r1 = new ChatResponse.StepMetricRecord("HybridSearch", 23, 12);
        ChatResponse.StepMetricRecord r2 = new ChatResponse.StepMetricRecord("HybridSearch", 99, 12);
        assertNotEquals(r1, r2);
    }

    @Test
    void stepMetricRecord_toString_containsKeyFields() {
        ChatResponse.StepMetricRecord r = new ChatResponse.StepMetricRecord("Embedding", 150, 20);
        String str = r.toString();
        assertTrue(str.contains("Embedding"));
        assertTrue(str.contains("150"));
        assertTrue(str.contains("20"));
    }

    // ========== SourceDocument ==========

    @Test
    void sourceDocument_equals_sameFields() {
        ChatResponse.SourceDocument d1 = new ChatResponse.SourceDocument();
        d1.setDocumentId("doc-1");
        d1.setTitle("Title");
        d1.setChunkText("chunk text");
        d1.setScore(0.95);
        ChatResponse.SourceDocument d2 = new ChatResponse.SourceDocument();
        d2.setDocumentId("doc-1");
        d2.setTitle("Title");
        d2.setChunkText("chunk text");
        d2.setScore(0.95);
        assertEquals(d1, d2);
        assertEquals(d1.hashCode(), d2.hashCode());
    }

    @Test
    void sourceDocument_equals_differentDocumentId_notEqual() {
        ChatResponse.SourceDocument d1 = new ChatResponse.SourceDocument();
        d1.setDocumentId("doc-1");
        ChatResponse.SourceDocument d2 = new ChatResponse.SourceDocument();
        d2.setDocumentId("doc-2");
        assertNotEquals(d1, d2);
    }

    @Test
    void sourceDocument_equals_differentScore_notEqual() {
        ChatResponse.SourceDocument d1 = new ChatResponse.SourceDocument();
        d1.setScore(0.95);
        ChatResponse.SourceDocument d2 = new ChatResponse.SourceDocument();
        d2.setScore(0.50);
        assertNotEquals(d1, d2);
    }

    @Test
    void sourceDocument_toString_containsKeyFields() {
        ChatResponse.SourceDocument d = new ChatResponse.SourceDocument();
        d.setDocumentId("doc-456");
        d.setTitle("Spring AI Reference");
        d.setChunkText("Return policy: Within 7 days of receiving the product...");
        d.setScore(0.92);
        String str = d.toString();
        assertTrue(str.contains("doc-456"));
        assertTrue(str.contains("Spring AI Reference"));
        assertTrue(str.contains("0.92"));
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

    // ========== RetrievalResult equals/hashCode/toString ==========

    @Test
    void retrievalResult_equals_sameDocumentIdAndChunkIndexAndTextAndTitle() {
        RetrievalResult r1 = new RetrievalResult();
        r1.setDocumentId("doc-1");
        r1.setChunkIndex(0);
        r1.setChunkText("same text");
        r1.setTitle("Same Title");
        r1.setScore(0.9);

        RetrievalResult r2 = new RetrievalResult();
        r2.setDocumentId("doc-1");
        r2.setChunkIndex(0);
        r2.setChunkText("same text");
        r2.setTitle("Same Title");
        r2.setScore(0.5); // different score, but equal

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void retrievalResult_equals_differentDocumentId_notEqual() {
        RetrievalResult r1 = new RetrievalResult();
        r1.setDocumentId("doc-1");
        r1.setChunkIndex(0);
        r1.setChunkText("same text");

        RetrievalResult r2 = new RetrievalResult();
        r2.setDocumentId("doc-2");
        r2.setChunkIndex(0);
        r2.setChunkText("same text");

        assertNotEquals(r1, r2);
    }

    @Test
    void retrievalResult_equals_differentChunkIndex_notEqual() {
        RetrievalResult r1 = new RetrievalResult();
        r1.setDocumentId("doc-1");
        r1.setChunkIndex(0);
        r1.setChunkText("same text");

        RetrievalResult r2 = new RetrievalResult();
        r2.setDocumentId("doc-1");
        r2.setChunkIndex(1);
        r2.setChunkText("same text");

        assertNotEquals(r1, r2);
    }

    @Test
    void retrievalResult_equals_scoresIgnored() {
        RetrievalResult r1 = new RetrievalResult();
        r1.setDocumentId("doc-1");
        r1.setChunkIndex(0);
        r1.setChunkText("same text");
        r1.setScore(0.9);
        r1.setVectorScore(0.95);
        r1.setFulltextScore(0.85);

        RetrievalResult r2 = new RetrievalResult();
        r2.setDocumentId("doc-1");
        r2.setChunkIndex(0);
        r2.setChunkText("same text");
        r2.setScore(0.1); // completely different scores
        r2.setVectorScore(0.0);
        r2.setFulltextScore(0.0);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void retrievalResult_equals_nullFields_treatedAsAbsent() {
        RetrievalResult r1 = new RetrievalResult();
        r1.setDocumentId(null);
        r1.setChunkIndex(0);
        r1.setChunkText(null);
        r1.setTitle(null);

        RetrievalResult r2 = new RetrievalResult();
        r2.setDocumentId(null);
        r2.setChunkIndex(0);
        r2.setChunkText(null);
        r2.setTitle(null);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void retrievalResult_equals_sameClass_notEqualToNull() {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId("doc-1");
        r.setChunkIndex(0);
        assertNotEquals(r, null);
    }

    @Test
    void retrievalResult_toString_containsKeyFields() {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId("doc-xyz");
        r.setChunkIndex(3);
        r.setScore(0.75);
        r.setTitle("Test Doc");

        String str = r.toString();
        assertTrue(str.contains("doc-xyz"));
        assertTrue(str.contains("chunkIndex=3"));
        assertTrue(str.contains("score=0.75"));
        assertTrue(str.contains("Test Doc"));
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

    // ========== RetrievalConfig equals/hashCode/toString ==========

    @Test
    void retrievalConfig_equals_sameFields() {
        RetrievalConfig c1 = RetrievalConfig.builder()
                .maxResults(20).minScore(0.3).useHybridSearch(false)
                .useRerank(false).vectorWeight(0.7).fulltextWeight(0.3).build();
        RetrievalConfig c2 = RetrievalConfig.builder()
                .maxResults(20).minScore(0.3).useHybridSearch(false)
                .useRerank(false).vectorWeight(0.7).fulltextWeight(0.3).build();
        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
    }

    @Test
    void retrievalConfig_equals_differentMaxResults_notEqual() {
        RetrievalConfig c1 = new RetrievalConfig();
        c1.setMaxResults(10);
        RetrievalConfig c2 = new RetrievalConfig();
        c2.setMaxResults(20);
        assertNotEquals(c1, c2);
    }

    @Test
    void retrievalConfig_equals_differentMinScore_notEqual() {
        RetrievalConfig c1 = new RetrievalConfig();
        c1.setMinScore(0.5);
        RetrievalConfig c2 = new RetrievalConfig();
        c2.setMinScore(0.7);
        assertNotEquals(c1, c2);
    }

    @Test
    void retrievalConfig_equals_differentBooleanFlags_notEqual() {
        RetrievalConfig c1 = new RetrievalConfig();
        c1.setUseHybridSearch(true);
        c1.setUseRerank(true);
        RetrievalConfig c2 = new RetrievalConfig();
        c2.setUseHybridSearch(false);
        c2.setUseRerank(false);
        assertNotEquals(c1, c2);
    }

    @Test
    void retrievalConfig_equals_differentWeights_notEqual() {
        RetrievalConfig c1 = new RetrievalConfig();
        c1.setVectorWeight(0.5);
        c1.setFulltextWeight(0.5);
        RetrievalConfig c2 = new RetrievalConfig();
        c2.setVectorWeight(0.7);
        c2.setFulltextWeight(0.3);
        assertNotEquals(c1, c2);
    }

    @Test
    void retrievalConfig_equals_sameDefaults() {
        RetrievalConfig c1 = new RetrievalConfig();
        RetrievalConfig c2 = new RetrievalConfig();
        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
    }

    @Test
    void retrievalConfig_toString_containsKeyFields() {
        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(20).minScore(0.3).useHybridSearch(false)
                .useRerank(false).vectorWeight(0.7).fulltextWeight(0.3).build();
        var str = config.toString();
        assertTrue(str.contains("maxResults=20"));
        assertTrue(str.contains("minScore=0.3"));
        assertTrue(str.contains("useHybridSearch=false"));
        assertTrue(str.contains("useRerank=false"));
        assertTrue(str.contains("vectorWeight=0.7"));
        assertTrue(str.contains("fulltextWeight=0.3"));
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

    // ========== DocumentDetailResponse ==========

    @Test
    void documentDetailResponse_constructorAndGetters() {
        var response = new DocumentDetailResponse(
                42L, "RAG Tutorial", "https://example.com/doc.pdf",
                "PDF", "COMPLETED",
                null, null,
                4096L, "sha256:abc123", true,
                1L, "My KB", 5L,
                "Full content here", Map.of("author", "John")
        );
        assertEquals(42L, response.id());
        assertEquals("RAG Tutorial", response.title());
        assertEquals("https://example.com/doc.pdf", response.source());
        assertEquals("PDF", response.documentType());
        assertEquals("COMPLETED", response.processingStatus());
        assertEquals(4096L, response.size());
        assertEquals("sha256:abc123", response.contentHash());
        assertTrue(response.enabled());
        assertEquals(1L, response.collectionId());
        assertEquals("My KB", response.collectionName());
        assertEquals(5L, response.chunkCount());
        assertEquals("Full content here", response.content());
        assertEquals("John", response.metadata().get("author"));
    }

    // ========== DocumentStatsResponse ==========

    @Test
    void documentStatsResponse_constructorAndGetters() {
        Map<String, Long> byStatus = Map.of("COMPLETED", 10L, "PENDING", 5L, "FAILED", 2L);
        var response = new DocumentStatsResponse(17L, byStatus);
        assertEquals(17L, response.total());
        assertEquals(10L, response.byStatus().get("COMPLETED"));
        assertEquals(5L, response.byStatus().get("PENDING"));
        assertEquals(2L, response.byStatus().get("FAILED"));
    }

    @Test
    void documentStatsResponse_emptyByStatus() {
        var response = new DocumentStatsResponse(0L, Map.of());
        assertEquals(0L, response.total());
        assertTrue(response.byStatus().isEmpty());
    }

    // ========== EmbeddingStatusResponse ==========

    @Test
    void embeddingStatusResponse_constructorAndGetters() {
        var response = new EmbeddingStatusResponse(100L, 80L, 20L, true);
        assertEquals(100L, response.totalDocuments());
        assertEquals(80L, response.withEmbeddings());
        assertEquals(20L, response.withoutEmbeddings());
        assertTrue(response.hasMissing());
    }

    @Test
    void embeddingStatusResponse_noMissing() {
        var response = new EmbeddingStatusResponse(50L, 50L, 0L, false);
        assertEquals(50L, response.totalDocuments());
        assertEquals(50L, response.withEmbeddings());
        assertEquals(0L, response.withoutEmbeddings());
        assertFalse(response.hasMissing());
    }

    // ========== BatchEmbedResponse ==========

    @Test
    void batchEmbedResponse_constructorAndGetters() {
        var item1 = new BatchEmbedResponse.BatchEmbedResultItem(
                1L, "COMPLETED", 5, 5, null, null);
        var item2 = new BatchEmbedResponse.BatchEmbedResultItem(
                2L, "CACHED", 0, 0, null, "Already embedded");
        var item3 = new BatchEmbedResponse.BatchEmbedResultItem(
                3L, "FAILED", 0, 0, "Connection timeout", null);
        var item4 = new BatchEmbedResponse.BatchEmbedResultItem(
                4L, "NOT_FOUND", 0, 0, null, "Document does not exist");
        var item5 = new BatchEmbedResponse.BatchEmbedResultItem(
                5L, "SKIPPED", 0, 0, null, "Empty content");

        var summary = new BatchEmbedResponse.BatchEmbedSummary(5, 1, 1, 1, 2);

        var response = new BatchEmbedResponse(List.of(item1, item2, item3, item4, item5), summary);
        assertEquals(5, response.results().size());
        assertEquals(5, response.summary().total());
        assertEquals(1, response.summary().success());
        assertEquals(1, response.summary().cached());
        assertEquals(1, response.summary().failed());
        assertEquals(2, response.summary().skipped());

        assertEquals("COMPLETED", response.results().get(0).status());
        assertEquals(5, response.results().get(0).chunksCreated());
        assertEquals("CACHED", response.results().get(1).status());
        assertEquals("Already embedded", response.results().get(1).reason());
        assertEquals("Connection timeout", response.results().get(2).error());
    }

    @Test
    void batchEmbedResultItem_allFields() {
        var item = new BatchEmbedResponse.BatchEmbedResultItem(
                99L, "COMPLETED", 10, 10, null, null);
        assertEquals(99L, item.documentId());
        assertEquals("COMPLETED", item.status());
        assertEquals(10, item.chunksCreated());
        assertEquals(10, item.embeddingsStored());
        assertNull(item.error());
        assertNull(item.reason());
    }

    @Test
    void batchEmbedSummary_allFields() {
        var summary = new BatchEmbedResponse.BatchEmbedSummary(20, 15, 3, 1, 1);
        assertEquals(20, summary.total());
        assertEquals(15, summary.success());
        assertEquals(3, summary.cached());
        assertEquals(1, summary.failed());
        assertEquals(1, summary.skipped());
    }

    // ========== CacheStatsResponse ==========

    @Test
    void cacheStatsResponse_constructorAndGetters() {
        var response = new CacheStatsResponse(127L, 43L, 170L, "74.7%", Map.of("key1", "value1"));
        assertEquals(127L, response.hitCount());
        assertEquals(43L, response.missCount());
        assertEquals(170L, response.totalCount());
        assertEquals("74.7%", response.hitRate());
        assertEquals("value1", response.details().get("key1"));
    }

    @Test
    void cacheStatsResponse_fromFactoryMethod() {
        Map<String, Object> stats = Map.of(
                "hitCount", 100L,
                "missCount", 50L,
                "totalCount", 150L,
                "hitRate", "66.7%"
        );
        var response = CacheStatsResponse.from(stats);
        assertEquals(100L, response.hitCount());
        assertEquals(50L, response.missCount());
        assertEquals(150L, response.totalCount());
        assertEquals("66.7%", response.hitRate());
        assertEquals(150L, response.details().get("totalCount"));
    }

    @Test
    void cacheStatsResponse_fromWithDefaults() {
        Map<String, Object> emptyStats = Map.of();
        var response = CacheStatsResponse.from(emptyStats);
        assertEquals(0L, response.hitCount());
        assertEquals(0L, response.missCount());
        assertEquals(0L, response.totalCount());
        assertEquals("N/A", response.hitRate());
    }

    // ========== CollectionExportResponse ==========

    @Test
    void collectionExportResponse_constructorAndGetters() {
        var doc1 = new CollectionExportResponse.ExportedDocumentSummary(
                "Doc 1", "https://example.com/1.pdf", "Content of doc 1",
                "PDF", Map.of("author", "Alice"), 2048L);
        var doc2 = new CollectionExportResponse.ExportedDocumentSummary(
                "Doc 2", "https://example.com/2.pdf", "Content of doc 2",
                "TXT", Map.of(), 1024L);
        var response = new CollectionExportResponse(
                "My KB", "RAG knowledge base", "BAAI/bge-m3", 1024, true,
                Map.of("industry", "tech"),
                List.of(doc1, doc2),
                null, 2
        );
        assertEquals("My KB", response.name());
        assertEquals("RAG knowledge base", response.description());
        assertEquals("BAAI/bge-m3", response.embeddingModel());
        assertEquals(1024, response.dimensions());
        assertTrue(response.enabled());
        assertEquals("tech", response.metadata().get("industry"));
        assertEquals(2, response.documentCount());
        assertEquals(2, response.documents().size());
        assertEquals("Doc 1", response.documents().get(0).title());
        assertEquals("Content of doc 1", response.documents().get(0).content());
        assertEquals("Alice", response.documents().get(0).metadata().get("author"));
    }

    @Test
    void exportedDocumentSummary_allFields() {
        var doc = new CollectionExportResponse.ExportedDocumentSummary(
                "Test Doc", "https://example.com/test.pdf", "Full text",
                "PDF", Map.of("page", 5), 8192L);
        assertEquals("Test Doc", doc.title());
        assertEquals("https://example.com/test.pdf", doc.source());
        assertEquals("Full text", doc.content());
        assertEquals("PDF", doc.documentType());
        assertEquals(5, doc.metadata().get("page"));
        assertEquals(8192L, doc.size());
    }

    // ========== VersionHistoryResponse ==========

    @Test
    void versionHistoryResponse_constructorAndGetters() {
        var v1 = new DocumentVersionResponse(1L, 42L, 1, "hash1", 1024L,
                "CREATED", "Initial version", null, null);
        var v2 = new DocumentVersionResponse(2L, 42L, 2, "hash2", 2048L,
                "UPDATED", "Content updated", null, "Updated content");
        var response = new VersionHistoryResponse(42L, 2L, 0, 20, List.of(v1, v2));
        assertEquals(42L, response.documentId());
        assertEquals(2L, response.totalVersions());
        assertEquals(0, response.page());
        assertEquals(20, response.size());
        assertEquals(2, response.versions().size());
        assertEquals(1, response.versions().get(0).versionNumber());
        assertEquals("hash1", response.versions().get(0).contentHash());
    }

    // ========== DocumentVersionResponse ==========

    @Test
    void documentVersionResponse_constructorAndGetters() {
        var response = new DocumentVersionResponse(
                5L, 42L, 3, "sha256:xyz789", 4096L,
                "UPDATED", "Content updated via batch import",
                null, "Snapshot of updated content"
        );
        assertEquals(5L, response.id());
        assertEquals(42L, response.documentId());
        assertEquals(3, response.versionNumber());
        assertEquals("sha256:xyz789", response.contentHash());
        assertEquals(4096L, response.size());
        assertEquals("UPDATED", response.changeType());
        assertEquals("Content updated via batch import", response.changeDescription());
        assertEquals("Snapshot of updated content", response.contentSnapshot());
    }

    @Test
    void documentVersionResponse_nullContentSnapshot() {
        var response = new DocumentVersionResponse(
                1L, 42L, 1, "hash1", 1024L,
                "CREATED", "Initial version", null, null
        );
        assertNull(response.contentSnapshot());
        assertEquals("CREATED", response.changeType());
    }

    // ========== AlertActionResponse ==========

    @Test
    void alertActionResponse_okFactory() {
        var r = AlertActionResponse.ok("Alert silenced for 2 hours");
        assertTrue(r.success());
        assertEquals("Alert silenced for 2 hours", r.message());
    }

    @Test
    void alertActionResponse_failFactory() {
        var r = AlertActionResponse.fail("Alert not found");
        assertFalse(r.success());
        assertEquals("Alert not found", r.message());
    }

    @Test
    void alertActionResponse_constructor() {
        var r = new AlertActionResponse(true, "Operation succeeded");
        assertTrue(r.success());
        assertEquals("Operation succeeded", r.message());
    }

    // ========== VariantResponse ==========

    @Test
    void variantResponse_ofFactory() {
        var r = VariantResponse.of("control");
        assertEquals("control", r.variant());
    }

    @Test
    void variantResponse_constructor() {
        var r = new VariantResponse("treatment");
        assertEquals("treatment", r.variant());
    }

    // ========== BatchDeleteItem ==========

    @Test
    void batchDeleteItem_constructor() {
        var item = new BatchDeleteItem(42L, "DELETED");
        assertEquals(42L, item.id());
        assertEquals("DELETED", item.status());
    }

    @Test
    void batchDeleteItem_notFound() {
        var item = new BatchDeleteItem(99L, "NOT_FOUND");
        assertEquals(99L, item.id());
        assertEquals("NOT_FOUND", item.status());
    }

    // ========== BatchDeleteItem equals/hashCode/toString ==========

    @Test
    void batchDeleteItem_equals_sameFields() {
        var a = new BatchDeleteItem(42L, "DELETED");
        var b = new BatchDeleteItem(42L, "DELETED");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void batchDeleteItem_equals_differentId() {
        var a = new BatchDeleteItem(1L, "DELETED");
        var b = new BatchDeleteItem(2L, "DELETED");
        assertNotEquals(a, b);
    }

    @Test
    void batchDeleteItem_equals_differentStatus() {
        var a = new BatchDeleteItem(42L, "DELETED");
        var b = new BatchDeleteItem(42L, "NOT_FOUND");
        assertNotEquals(a, b);
    }

    @Test
    void batchDeleteItem_equals_nullFields() {
        var a = new BatchDeleteItem(null, null);
        var b = new BatchDeleteItem(null, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void batchDeleteItem_toString() {
        var item = new BatchDeleteItem(42L, "DELETED");
        var str = item.toString();
        assertTrue(str.contains("42"));
        assertTrue(str.contains("DELETED"));
    }

    // ========== BatchDeleteSummary ==========

    @Test
    void batchDeleteSummary_constructor() {
        var s = new BatchDeleteSummary(10, 8, 2);
        assertEquals(10, s.total());
        assertEquals(8, s.deleted());
        assertEquals(2, s.notFound());
    }

    @Test
    void batchDeleteSummary_allDeleted() {
        var s = new BatchDeleteSummary(5, 5, 0);
        assertEquals(5, s.total());
        assertEquals(5, s.deleted());
        assertEquals(0, s.notFound());
    }

    // ========== BatchDeleteSummary equals/hashCode/toString ==========

    @Test
    void batchDeleteSummary_equals_sameFields() {
        var a = new BatchDeleteSummary(10, 8, 2);
        var b = new BatchDeleteSummary(10, 8, 2);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void batchDeleteSummary_equals_differentTotal() {
        var a = new BatchDeleteSummary(10, 8, 2);
        var b = new BatchDeleteSummary(99, 8, 2);
        assertNotEquals(a, b);
    }

    @Test
    void batchDeleteSummary_equals_differentDeleted() {
        var a = new BatchDeleteSummary(10, 8, 2);
        var b = new BatchDeleteSummary(10, 9, 2);
        assertNotEquals(a, b);
    }

    @Test
    void batchDeleteSummary_equals_differentNotFound() {
        var a = new BatchDeleteSummary(10, 8, 2);
        var b = new BatchDeleteSummary(10, 8, 3);
        assertNotEquals(a, b);
    }

    @Test
    void batchDeleteSummary_toString() {
        var s = new BatchDeleteSummary(10, 8, 2);
        var str = s.toString();
        assertTrue(str.contains("10"));
        assertTrue(str.contains("8"));
        assertTrue(str.contains("2"));
    }

    // ========== BatchDeleteResponse ==========

    @Test
    void batchDeleteResponse_constructor() {
        var item = new BatchDeleteItem(1L, "DELETED");
        var summary = new BatchDeleteSummary(10, 8, 2);
        var r = new BatchDeleteResponse(List.of(item), summary);
        assertEquals(1, r.results().size());
        assertEquals(10, r.summary().total());
        assertEquals(8, r.summary().deleted());
        assertEquals(2, r.summary().notFound());
    }

    @Test
    void batchDeleteResponse_emptyResults() {
        var summary = new BatchDeleteSummary(0, 0, 0);
        var r = new BatchDeleteResponse(List.of(), summary);
        assertTrue(r.results().isEmpty());
        assertEquals(0, r.summary().total());
    }

    // ========== FireAlertResponse ==========

    @Test
    void fireAlertResponse_ofFactory() {
        var r = FireAlertResponse.of(42L);
        assertEquals(42L, r.alertId());
        assertEquals("Alert triggered", r.message());
    }

    @Test
    void fireAlertResponse_constructor() {
        var r = new FireAlertResponse(99L, "Custom message");
        assertEquals(99L, r.alertId());
        assertEquals("Custom message", r.message());
    }

    // ========== DocumentDeleteResponse ==========

    @Test
    void documentDeleteResponse_constructor() {
        var r = new DocumentDeleteResponse("Document deleted", 42L, 5L);
        assertEquals("Document deleted", r.message());
        assertEquals(42L, r.id());
        assertEquals(5L, r.embeddingsRemoved());
    }

    @Test
    void documentDeleteResponse_zeroEmbeddings() {
        var r = new DocumentDeleteResponse("Not found", 99L, 0L);
        assertEquals(0L, r.embeddingsRemoved());
    }

    // ========== ClearHistoryResponse ==========

    @Test
    void clearHistoryResponse_ofFactory() {
        var r = ClearHistoryResponse.of("session-abc", 10);
        assertEquals("Session history cleared", r.message());
        assertEquals("session-abc", r.sessionId());
        assertEquals(10, r.deletedCount());
    }

    @Test
    void clearHistoryResponse_constructor() {
        var r = new ClearHistoryResponse("History purged", "session-xyz", 5);
        assertEquals("History purged", r.message());
        assertEquals("session-xyz", r.sessionId());
        assertEquals(5, r.deletedCount());
    }

    // ========== ClientErrorCountResponse ==========

    @Test
    void clientErrorCountResponse_constructor() {
        var r = new ClientErrorCountResponse(42);
        assertEquals(42, r.count());
    }

    @Test
    void clientErrorCountResponse_zero() {
        var r = new ClientErrorCountResponse(0);
        assertEquals(0, r.count());
    }

    // ========== PdfImportResponse ==========

    @Test
    void pdfImportResponse_constructor() {
        var r = new PdfImportResponse("uuid-123", "uuid-123/default.md", 2);
        assertEquals("uuid-123", r.uuid());
        assertEquals("uuid-123/default.md", r.entryMarkdown());
        assertEquals(2, r.filesStored());
    }

    @Test
    void pdfImportResponse_singleFile() {
        var r = new PdfImportResponse("uuid-456", "uuid-456/default.md", 1);
        assertEquals(1, r.filesStored());
    }

    // ========== ReembedResultResponse ==========

    @Test
    void reembedResultResponse_constructor() {
        var r = new ReembedResultResponse(42L, "Doc Title", "COMPLETED", 10, "Success");
        assertEquals(42L, r.documentId());
        assertEquals("Doc Title", r.title());
        assertEquals("COMPLETED", r.status());
        assertEquals(10, r.chunks());
        assertEquals("Success", r.message());
    }

    @Test
    void reembedResultResponse_failed() {
        var r = new ReembedResultResponse(99L, "Fail Doc", "FAILED", null, "Connection timeout");
        assertEquals("FAILED", r.status());
        assertNull(r.chunks());
    }

    // ========== ReembedMissingResponse ==========

    @Test
    void reembedMissingResponse_constructor() {
        var result = new ReembedResultResponse(42L, "Title", "COMPLETED", 10, "OK");
        var r = new ReembedMissingResponse(5, 4, 1, List.of(result));
        assertEquals(5, r.total());
        assertEquals(4, r.success());
        assertEquals(1, r.failed());
        assertEquals(1, r.results().size());
    }

    @Test
    void reembedMissingResponse_emptyResults() {
        var r = new ReembedMissingResponse(0, 0, 0, List.of());
        assertEquals(0, r.total());
        assertTrue(r.results().isEmpty());
    }

    // ========== CollectionResponse ==========

    @Test
    void collectionResponse_constructor() {
        var now = java.time.ZonedDateTime.now();
        var r = new CollectionResponse(1L, "My Collection", "A test collection",
                "BGE-M3", 1024, true, Map.of("key", "val"), now, now, 25);
        assertEquals(1L, r.id());
        assertEquals("My Collection", r.name());
        assertEquals("A test collection", r.description());
        assertEquals("BGE-M3", r.embeddingModel());
        assertEquals(1024, r.dimensions());
        assertTrue(r.enabled());
        assertEquals("val", r.metadata().get("key"));
        assertEquals(now, r.createdAt());
        assertEquals(25, r.documentCount());
    }

    // ========== CollectionListResponse ==========

    @Test
    void collectionListResponse_constructor() {
        var r = new CollectionListResponse(List.of(), 0, 0, 10);
        assertEquals(0, r.total());
        assertEquals(0, r.page());
        assertEquals(10, r.pageSize());
        assertTrue(r.collections().isEmpty());
    }

    @Test
    void collectionListResponse_withCollections() {
        var now = java.time.ZonedDateTime.now();
        var coll = new CollectionResponse(1L, "Col1", null, "BGE-M3", 1024, true, null, now, now, 5);
        var r = new CollectionListResponse(List.of(coll), 1, 0, 10);
        assertEquals(1, r.total());
        assertEquals(1, r.collections().size());
    }

    // ========== CollectionDocumentListResponse ==========

    @Test
    void collectionDocumentListResponse_constructor() {
        var r = new CollectionDocumentListResponse(1L, List.of(), 0, 0, 20);
        assertEquals(1L, r.collectionId());
        assertEquals(0, r.total());
        assertEquals(0, r.offset());
        assertEquals(20, r.limit());
        assertTrue(r.documents().isEmpty());
    }

    // ========== DocumentCreateResponse ==========

    @Test
    void documentCreateResponse_created() {
        var r = DocumentCreateResponse.created(42L, "New Doc", "sha256:abc");
        assertEquals(42L, r.id());
        assertEquals("New Doc", r.title());
        assertEquals("CREATED", r.status());
        assertEquals("sha256:abc", r.contentHash());
        assertNull(r.existingDocumentId());
    }

    @Test
    void documentCreateResponse_duplicate() {
        var r = DocumentCreateResponse.duplicate(99L, "Existing Doc", "sha256:xyz");
        assertEquals(99L, r.id());
        assertEquals("Existing Doc", r.title());
        assertEquals("DUPLICATE", r.status());
        assertEquals("sha256:xyz", r.contentHash());
        assertEquals(99L, r.existingDocumentId());
    }

    @Test
    void documentCreateResponse_constructor() {
        var r = new DocumentCreateResponse(1L, "Title", "STATUS", "msg", "hash", null);
        assertEquals(1L, r.id());
        assertEquals("Title", r.title());
        assertEquals("STATUS", r.status());
        assertEquals("msg", r.message());
        assertEquals("hash", r.contentHash());
        assertNull(r.existingDocumentId());
    }

    // ========== ApiKeyCreateRequest ==========

    @Test
    void apiKeyCreateRequest_constructor() {
        var expiry = java.time.LocalDateTime.of(2027, 1, 1, 0, 0);
        var r = new ApiKeyCreateRequest("Prod Key", expiry);
        assertEquals("Prod Key", r.getName());
        assertEquals(expiry, r.getExpiresAt());
    }

    @Test
    void apiKeyCreateRequest_defaultConstructor() {
        var r = new ApiKeyCreateRequest();
        assertNull(r.getName());
        assertNull(r.getExpiresAt());
        r.setName("Test Key");
        r.setExpiresAt(null);
        assertEquals("Test Key", r.getName());
    }

    // ========== ApiKeyResponse ==========

    @Test
    void apiKeyResponse_constructor() {
        var created = java.time.LocalDateTime.of(2026, 4, 12, 3, 50);
        var lastUsed = java.time.LocalDateTime.of(2026, 4, 12, 10, 0);
        var expiry = java.time.LocalDateTime.of(2027, 1, 1, 0, 0);
        var r = new ApiKeyResponse("rag_k_abc", "Prod Server", created, lastUsed, expiry, true);
        assertEquals("rag_k_abc", r.getKeyId());
        assertEquals("Prod Server", r.getName());
        assertEquals(created, r.getCreatedAt());
        assertEquals(lastUsed, r.getLastUsedAt());
        assertEquals(expiry, r.getExpiresAt());
        assertTrue(r.getEnabled());
    }

    @Test
    void apiKeyResponse_defaultConstructor() {
        var r = new ApiKeyResponse();
        assertNull(r.getKeyId());
        r.setKeyId("rag_k_xyz");
        r.setEnabled(false);
        assertEquals("rag_k_xyz", r.getKeyId());
        assertFalse(r.getEnabled());
    }

    // ========== ApiKeyCreatedResponse ==========

    @Test
    void apiKeyCreatedResponse_constructor() {
        var expiry = java.time.LocalDateTime.of(2027, 1, 1, 0, 0);
        var r = new ApiKeyCreatedResponse("rag_k_abc", "rag_sk_xxx", "Prod Server", expiry);
        assertEquals("rag_k_abc", r.getKeyId());
        assertEquals("rag_sk_xxx", r.getRawKey());
        assertEquals("Prod Server", r.getName());
        assertEquals(expiry, r.getExpiresAt());
        assertNotNull(r.getWarning());
    }

    @Test
    void apiKeyCreatedResponse_defaultConstructor() {
        var r = new ApiKeyCreatedResponse();
        assertNull(r.getKeyId());
        r.setKeyId("rag_k_new");
        assertEquals("rag_k_new", r.getKeyId());
    }

    // ========== BatchCreateResponse ==========

    @Test
    void batchCreateResponse_constructor() {
        var docResult = new BatchCreateResponse.DocumentResult(42L, "Test Doc", true, null);
        var r = new BatchCreateResponse(10, 2, 0, List.of(docResult));
        assertEquals(10, r.created());
        assertEquals(2, r.skipped());
        assertEquals(0, r.failed());
        assertEquals(1, r.results().size());
    }

    @Test
    void batchCreateResponse_documentResult() {
        var result = new BatchCreateResponse.DocumentResult(99L, "Fail Doc", false, "Embedding error");
        assertEquals(99L, result.documentId());
        assertEquals("Fail Doc", result.title());
        assertFalse(result.newlyCreated());
        assertEquals("Embedding error", result.error());
    }

    @Test
    void batchCreateResponse_allFields() {
        var r = new BatchCreateResponse(5, 3, 2, List.of());
        assertEquals(5, r.created());
        assertEquals(3, r.skipped());
        assertEquals(2, r.failed());
    }

    // ========== SilenceScheduleRequest ==========

    @Test
    void silenceScheduleRequest_constructorAndGetters() {
        var req = new com.springairag.api.dto.SilenceScheduleRequest();
        req.setName("weekend-maintenance");
        req.setAlertKey("high-latency");
        req.setSilenceType("RECURRING");
        req.setStartTime("0 0 2 * * SAT");
        req.setEndTime("0 0 6 * * SAT");
        req.setDescription("Silence alerts during weekend maintenance window");
        req.setEnabled(true);
        req.setMetadata(Map.of("owner", "ops-team"));

        assertEquals("weekend-maintenance", req.getName());
        assertEquals("high-latency", req.getAlertKey());
        assertEquals("RECURRING", req.getSilenceType());
        assertEquals("0 0 2 * * SAT", req.getStartTime());
        assertEquals("0 0 6 * * SAT", req.getEndTime());
        assertEquals("Silence alerts during weekend maintenance window", req.getDescription());
        assertTrue(req.getEnabled());
        assertEquals("ops-team", req.getMetadata().get("owner"));
    }

    @Test
    void silenceScheduleRequest_setters() {
        var req = new com.springairag.api.dto.SilenceScheduleRequest();
        req.setName(null);
        req.setAlertKey(null);
        req.setSilenceType(null);
        req.setStartTime(null);
        req.setEndTime(null);
        assertNull(req.getName());
        assertNull(req.getAlertKey());
        assertNull(req.getSilenceType());
        assertNull(req.getStartTime());
        assertNull(req.getEndTime());
    }

    // ========== SloConfigRequest ==========

    @Test
    void sloConfigRequest_constructorAndGetters() {
        var req = new com.springairag.api.dto.SloConfigRequest();
        req.setSloName("availability_p99");
        req.setSloType("AVAILABILITY");
        req.setTargetValue(99.5);
        req.setUnit("%");
        req.setDescription("P99 availability over 5-minute window");
        req.setEnabled(true);
        req.setMetadata(Map.of("service", "rag-api"));

        assertEquals("availability_p99", req.getSloName());
        assertEquals("AVAILABILITY", req.getSloType());
        assertEquals(99.5, req.getTargetValue());
        assertEquals("%", req.getUnit());
        assertEquals("P99 availability over 5-minute window", req.getDescription());
        assertTrue(req.getEnabled());
        assertEquals("rag-api", req.getMetadata().get("service"));
    }

    @Test
    void sloConfigRequest_setters() {
        var req = new com.springairag.api.dto.SloConfigRequest();
        req.setSloName(null);
        req.setSloType(null);
        req.setTargetValue(null);
        req.setUnit(null);
        req.setDescription(null);
        req.setEnabled(false);
        assertNull(req.getSloName());
        assertNull(req.getSloType());
        assertNull(req.getTargetValue());
        assertNull(req.getUnit());
        assertNull(req.getDescription());
        assertFalse(req.getEnabled());
    }

    // ========== FileTreeResponse ==========

    @Test
    void fileTreeResponse_recordConstructor() {
        var entry1 = new com.springairag.api.dto.FileTreeEntryResponse("intro.md", "abc123/intro.md", "file", "text/markdown", 2048);
        var entry2 = new com.springairag.api.dto.FileTreeEntryResponse("images", "abc123/images", "directory", null, 0);
        var response = new com.springairag.api.dto.FileTreeResponse("abc123/", java.util.List.of(entry1, entry2), 2);

        assertEquals("abc123/", response.path());
        assertEquals(2, response.total());
        assertEquals(2, response.entries().size());

        var e1 = response.entries().get(0);
        assertEquals("intro.md", e1.name());
        assertEquals("abc123/intro.md", e1.path());
        assertEquals("file", e1.type());
        assertEquals("text/markdown", e1.mimeType());
        assertEquals(2048, e1.size());

        var e2 = response.entries().get(1);
        assertEquals("images", e2.name());
        assertEquals("directory", e2.type());
        assertNull(e2.mimeType());
        assertEquals(0, e2.size());
    }

    @Test
    void fileTreeEntryResponse_recordValidation() {
        var entry = new com.springairag.api.dto.FileTreeEntryResponse("default.md", "abc123/default.md", "file", "text/markdown", 4096);
        assertEquals("default.md", entry.name());
        assertEquals("abc123/default.md", entry.path());
        assertEquals("file", entry.type());
        assertEquals("text/markdown", entry.mimeType());
        assertEquals(4096, entry.size());
    }

    // ========== SlowQueryStatsResponse ==========

    @Test
    void slowQueryStatsResponse_recordConstructor() {
        var record1 = new com.springairag.api.dto.SlowQueryStatsResponse.SlowQueryRecordDto(1712000000000L, 250, "SELECT * FROM rag_documents");
        var response = new com.springairag.api.dto.SlowQueryStatsResponse(true, 1000L, 5000L, 42L, 45L, java.util.List.of(record1));

        assertTrue(response.enabled());
        assertEquals(1000L, response.thresholdMs());
        assertEquals(5000L, response.totalQueryCount());
        assertEquals(42L, response.slowQueryCount());
        assertEquals(45L, response.averageDurationMs());
        assertEquals(1, response.recentSlowQueries().size());

        var rec = response.recentSlowQueries().get(0);
        assertEquals(1712000000000L, rec.timestampMs());
        assertEquals(250, rec.durationMs());
        assertEquals("SELECT * FROM rag_documents", rec.sql());
    }

    @Test
    void slowQueryStatsResponse_emptySlowQueries() {
        var response = new com.springairag.api.dto.SlowQueryStatsResponse(false, 500L, 100L, 0L, 30L, java.util.List.of());
        assertFalse(response.enabled());
        assertEquals(500L, response.thresholdMs());
        assertEquals(100L, response.totalQueryCount());
        assertEquals(0L, response.slowQueryCount());
        assertEquals(30.0, response.averageDurationMs());
        assertTrue(response.recentSlowQueries().isEmpty());
    }

    @Test
    void slowQueryRecordDto_constructorAndGetters() {
        var record = new com.springairag.api.dto.SlowQueryStatsResponse.SlowQueryRecordDto(1712000000000L, 150, "SELECT id FROM rag_collection");
        assertEquals(1712000000000L, record.timestampMs());
        assertEquals(150, record.durationMs());
        assertEquals("SELECT id FROM rag_collection", record.sql());
    }

    // ========== DocumentSummary ==========

    @Test
    void documentSummary_recordConstructor() {
        var now = java.time.LocalDateTime.now();
        var summary = new com.springairag.api.dto.DocumentSummary(
                42L, "RAG Guide", "https://example.com/doc.pdf", "PDF", "COMPLETED",
                now, 4096L, "hash_abc", true, now, 1L, "My KB", 10L, "Preview...", "Full content", java.util.Map.of("key", "value"));

        assertEquals(42L, summary.id());
        assertEquals("RAG Guide", summary.title());
        assertEquals("https://example.com/doc.pdf", summary.source());
        assertEquals("PDF", summary.documentType());
        assertEquals("COMPLETED", summary.processingStatus());
        assertEquals(now, summary.createdAt());
        assertEquals(4096L, summary.size());
        assertEquals("hash_abc", summary.contentHash());
        assertTrue(summary.enabled());
        assertEquals(now, summary.updatedAt());
        assertEquals(1L, summary.collectionId());
        assertEquals("My KB", summary.collectionName());
        assertEquals(10L, summary.chunkCount());
        assertEquals("Preview...", summary.contentPreview());
        assertEquals("Full content", summary.content());
        assertEquals("value", summary.metadata().get("key"));
    }

    @Test
    void documentSummary_nullFields() {
        var summary = new com.springairag.api.dto.DocumentSummary(
                null, null, null, null, null, null, null, null, false, null, null, null, 0L, null, null, null);
        assertNull(summary.id());
        assertNull(summary.title());
        assertNull(summary.source());
        assertNull(summary.documentType());
        assertNull(summary.processingStatus());
        assertNull(summary.createdAt());
        assertNull(summary.size());
        assertNull(summary.contentHash());
        assertFalse(summary.enabled());
        assertNull(summary.updatedAt());
        assertNull(summary.collectionId());
        assertNull(summary.collectionName());
        assertEquals(0L, summary.chunkCount());
        assertNull(summary.contentPreview());
        assertNull(summary.content());
        assertNull(summary.metadata());
    }

    // ========== DocumentSummary equals/hashCode/toString ==========

    @Test
    void documentSummary_equals_sameFields() {
        var now = java.time.LocalDateTime.of(2026, 4, 25, 10, 0);
        var a = new com.springairag.api.dto.DocumentSummary(
                42L, "RAG Guide", "https://example.com/doc.pdf", "PDF", "COMPLETED",
                now, 4096L, "hash_abc", true, now, 1L, "My KB", 10L, "Preview", "Full", java.util.Map.of("k", "v"));
        var b = new com.springairag.api.dto.DocumentSummary(
                42L, "RAG Guide", "https://example.com/doc.pdf", "PDF", "COMPLETED",
                now, 4096L, "hash_abc", true, now, 1L, "My KB", 10L, "Preview", "Full", java.util.Map.of("k", "v"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void documentSummary_equals_differentId() {
        var now = java.time.LocalDateTime.of(2026, 4, 25, 10, 0);
        var a = new com.springairag.api.dto.DocumentSummary(
                1L, "Title", "src", "PDF", "DONE", now, 100L, "h", true, now, 1L, "KB", 5L, "P", "F", null);
        var b = new com.springairag.api.dto.DocumentSummary(
                2L, "Title", "src", "PDF", "DONE", now, 100L, "h", true, now, 1L, "KB", 5L, "P", "F", null);
        assertNotEquals(a, b);
    }

    @Test
    void documentSummary_equals_differentEnabled() {
        var now = java.time.LocalDateTime.of(2026, 4, 25, 10, 0);
        var a = new com.springairag.api.dto.DocumentSummary(
                1L, "Title", "src", "PDF", "DONE", now, 100L, "h", true, now, 1L, "KB", 5L, "P", "F", null);
        var b = new com.springairag.api.dto.DocumentSummary(
                1L, "Title", "src", "PDF", "DONE", now, 100L, "h", false, now, 1L, "KB", 5L, "P", "F", null);
        assertNotEquals(a, b);
    }

    @Test
    void documentSummary_equals_nullFields() {
        var a = new com.springairag.api.dto.DocumentSummary(
                null, null, null, null, null, null, null, null, false, null, null, null, 0L, null, null, null);
        var b = new com.springairag.api.dto.DocumentSummary(
                null, null, null, null, null, null, null, null, false, null, null, null, 0L, null, null, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void documentSummary_toString() {
        var summary = new com.springairag.api.dto.DocumentSummary(
                42L, "RAG Guide", "https://example.com/doc.pdf", "PDF", "COMPLETED",
                java.time.LocalDateTime.of(2026, 4, 25, 10, 0), 4096L, "hash_abc",
                true, java.time.LocalDateTime.of(2026, 4, 25, 10, 0), 1L, "My KB",
                10L, "Preview...", "Full content", java.util.Map.of("key", "value"));
        var str = summary.toString();
        assertTrue(str.contains("42"));
        assertTrue(str.contains("RAG Guide"));
        assertTrue(str.contains("PDF"));
    }

    // ========== SilenceAlertRequest ==========

    @Test
    void silenceAlertRequest_recordConstructor() {
        var req = new com.springairag.api.dto.SilenceAlertRequest("high-latency", 60);
        assertEquals("high-latency", req.alertKey());
        assertEquals(60, req.durationMinutes());
    }

    @Test
    void silenceAlertRequest_nullDuration() {
        var req = new com.springairag.api.dto.SilenceAlertRequest("high-latency", null);
        assertEquals("high-latency", req.alertKey());
        assertNull(req.durationMinutes());
    }

    // ========== RetrievalResult ==========

    @Test
    void retrievalResult_defaultConstructor() {
        var r = new RetrievalResult();
        assertNull(r.getDocumentId());
    }

    @Test
    void retrievalResult_gettersAndSetters() {
        var r = new RetrievalResult();
        r.setDocumentId("doc-123");
        r.setChunkText("Spring AI is a framework...");
        r.setScore(0.85);
        r.setVectorScore(0.90);
        r.setFulltextScore(0.80);
        r.setChunkIndex(3);
        r.setTitle("Spring AI Guide");
        r.setMetadata(Map.of("source", "manual"));

        assertEquals("doc-123", r.getDocumentId());
        assertEquals("Spring AI is a framework...", r.getChunkText());
        assertEquals(0.85, r.getScore());
        assertEquals(0.90, r.getVectorScore());
        assertEquals(0.80, r.getFulltextScore());
        assertEquals(3, r.getChunkIndex());
        assertEquals("Spring AI Guide", r.getTitle());
        assertEquals("manual", r.getMetadata().get("source"));
    }

    // ========== ResolveAlertRequest ==========

    @Test
    void resolveAlertRequest_constructorAndGetters() {
        var req = new ResolveAlertRequest("Service restarted after upgrade");
        assertEquals("Service restarted after upgrade", req.resolution());
    }

    @Test
    void resolveAlertRequest_nullResolution() {
        var req = new ResolveAlertRequest(null);
        assertNull(req.resolution());
    }

    @Test
    void resolveAlertRequest_emptyResolution() {
        var req = new ResolveAlertRequest("");
        assertEquals("", req.resolution());
    }

    // ========== AnswerQualityRequest ==========

    @Test
    void answerQualityRequest_constructorAndGetters() {
        var req = new AnswerQualityRequest("What is RAG?", "RAG is retrieval.", "RAG is a technique.");
        assertEquals("What is RAG?", req.getQuery());
        assertEquals("RAG is retrieval.", req.getContext());
        assertEquals("RAG is a technique.", req.getAnswer());
    }

    @Test
    void answerQualityRequest_setters() {
        var req = new AnswerQualityRequest();
        req.setQuery("Query");
        req.setContext("Context");
        req.setAnswer("Answer");
        assertEquals("Query", req.getQuery());
        assertEquals("Context", req.getContext());
        assertEquals("Answer", req.getAnswer());
    }

    // ========== AnswerQualityRequest equals/hashCode/toString ==========

    @Test
    void answerQualityRequest_equals_sameFields() {
        var r1 = new AnswerQualityRequest("query", "context", "answer");
        var r2 = new AnswerQualityRequest("query", "context", "answer");
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void answerQualityRequest_equals_differentQuery_notEqual() {
        var r1 = new AnswerQualityRequest("query1", "context", "answer");
        var r2 = new AnswerQualityRequest("query2", "context", "answer");
        assertNotEquals(r1, r2);
    }

    @Test
    void answerQualityRequest_equals_differentContext_notEqual() {
        var r1 = new AnswerQualityRequest("query", "context1", "answer");
        var r2 = new AnswerQualityRequest("query", "context2", "answer");
        assertNotEquals(r1, r2);
    }

    @Test
    void answerQualityRequest_equals_differentAnswer_notEqual() {
        var r1 = new AnswerQualityRequest("query", "context", "answer1");
        var r2 = new AnswerQualityRequest("query", "context", "answer2");
        assertNotEquals(r1, r2);
    }

    @Test
    void answerQualityRequest_equals_nullFields() {
        var r1 = new AnswerQualityRequest(null, null, null);
        var r2 = new AnswerQualityRequest(null, null, null);
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void answerQualityRequest_toString_containsKeyFields() {
        var req = new AnswerQualityRequest("my-query", "my-context", "my-answer");
        var str = req.toString();
        assertTrue(str.contains("my-query"));
        assertTrue(str.contains("my-context"));
        assertTrue(str.contains("my-answer"));
    }

    // ========== AnswerQualityResponse ==========

    @Test
    void answerQualityResponse_constructorAndGetters() {
        var evaluatedAt = ZonedDateTime.now();
        var resp = new AnswerQualityResponse(4, 5, 4, "Reasoning", "ACCEPT", evaluatedAt);
        assertEquals(4, resp.getGroundedness());
        assertEquals(5, resp.getRelevance());
        assertEquals(4, resp.getHelpfulness());
        assertEquals("Reasoning", resp.getReasoning());
        assertEquals("ACCEPT", resp.getRecommendation());
        assertEquals(evaluatedAt, resp.getEvaluatedAt());
    }

    @Test
    void answerQualityResponse_setters() {
        var resp = new AnswerQualityResponse();
        resp.setGroundedness(3);
        resp.setRelevance(4);
        resp.setHelpfulness(5);
        resp.setReasoning("Needs revision");
        resp.setRecommendation("REVISION");
        resp.setEvaluatedAt(null);
        assertEquals(3, resp.getGroundedness());
        assertEquals(4, resp.getRelevance());
        assertEquals(5, resp.getHelpfulness());
        assertEquals("Needs revision", resp.getReasoning());
        assertEquals("REVISION", resp.getRecommendation());
        assertNull(resp.getEvaluatedAt());
    }

    // ========== ClientErrorRequest ==========

    @Test
    void clientErrorRequest_constructorAndGetters() {
        var req = new ClientErrorRequest();
        req.setErrorType("TypeError");
        req.setErrorMessage("Cannot read property");
        req.setStackTrace("at app.js:10");
        assertEquals("TypeError", req.getErrorType());
        assertEquals("Cannot read property", req.getErrorMessage());
        assertEquals("at app.js:10", req.getStackTrace());
    }

    // ========== CollectionCreatedResponse ==========

    @Test
    void collectionCreatedResponse_constructor() {
        var resp = new CollectionCreatedResponse("Collection created", 42L, "My KB");
        assertEquals("Collection created", resp.message());
        assertEquals(42L, resp.collectionId());
        assertEquals("My KB", resp.name());
    }

    @Test
    void collectionCreatedResponse_of() {
        var resp = CollectionCreatedResponse.of(5L, "Test Collection");
        assertEquals("Collection created", resp.message());
        assertEquals(5L, resp.collectionId());
        assertEquals("Test Collection", resp.name());
    }

    // ========== CollectionDeleteResponse ==========

    @Test
    void collectionDeleteResponse_constructor() {
        var resp = new CollectionDeleteResponse("Collection deleted", 1L, 5L);
        assertEquals("Collection deleted", resp.message());
        assertEquals(1L, resp.id());
        assertEquals(5, resp.documentsUnlinked());
    }

    @Test
    void collectionDeleteResponse_of() {
        var resp = CollectionDeleteResponse.of(3L, 10);
        assertEquals("Collection deleted", resp.message());
        assertEquals(3L, resp.id());
        assertEquals(10, resp.documentsUnlinked());
    }

    // ========== CollectionImportResponse ==========

    @Test
    void collectionImportResponse_constructor() {
        var resp = new CollectionImportResponse("Import completed", 1L, 20, 5);
        assertEquals("Import completed", resp.message());
        assertEquals(1L, resp.collectionId());
        assertEquals(20, resp.imported());
        assertEquals(5, resp.skipped());
    }

    @Test
    void collectionImportResponse_of() {
        var resp = CollectionImportResponse.of(7L, 15, 3);
        assertEquals("Collection import completed", resp.message());
        assertEquals(7L, resp.collectionId());
        assertEquals(15, resp.imported());
        assertEquals(3, resp.skipped());
    }

    // ========== CollectionRestoreResponse ==========

    @Test
    void collectionRestoreResponse_constructor() {
        var resp = new CollectionRestoreResponse("Collection restored", 1L, "My KB", 10L);
        assertEquals("Collection restored", resp.message());
        assertEquals(1L, resp.collectionId());
        assertEquals("My KB", resp.name());
        assertEquals(10L, resp.documentCount());
    }

    @Test
    void collectionRestoreResponse_of() {
        var resp = CollectionRestoreResponse.of(2L, "Restored KB", 25L);
        assertEquals("Collection restored", resp.message());
        assertEquals(2L, resp.collectionId());
        assertEquals("Restored KB", resp.name());
        assertEquals(25L, resp.documentCount());
    }

    // ========== CollectionCloneResponse ==========

    @Test
    void collectionCloneResponse_constructor() {
        var resp = new CollectionCloneResponse("Clone completed", 5L, "KB (Copy)", 1L, "Original KB", 10);
        assertEquals("Clone completed", resp.message());
        assertEquals(5L, resp.clonedCollectionId());
        assertEquals("KB (Copy)", resp.clonedCollectionName());
        assertEquals(1L, resp.sourceCollectionId());
        assertEquals("Original KB", resp.sourceCollectionName());
        assertEquals(10, resp.documentsCloned());
    }

    // ========== DocumentAddedResponse ==========

    @Test
    void documentAddedResponse_constructor() {
        var resp = new DocumentAddedResponse("Document added to collection", 1L, 42L);
        assertEquals("Document added to collection", resp.message());
        assertEquals(1L, resp.collectionId());
        assertEquals(42L, resp.documentId());
    }

    @Test
    void documentAddedResponse_of() {
        var resp = DocumentAddedResponse.of(3L, 99L);
        assertEquals("Document added to collection", resp.message());
        assertEquals(3L, resp.collectionId());
        assertEquals(99L, resp.documentId());
    }

    // ========== ChatHistoryResponse ==========

    @Test
    void chatHistoryResponse_constructor() {
        var resp = new ChatHistoryResponse(1L, "session-abc", "User message", "AI response", java.util.List.of(1L, 2L), java.util.Map.of(), null);
        assertEquals(1L, resp.id());
        assertEquals("session-abc", resp.sessionId());
        assertEquals("User message", resp.userMessage());
        assertEquals("AI response", resp.aiResponse());
        assertEquals(java.util.List.of(1L, 2L), resp.relatedDocumentIds());
    }

    @Test
    void chatHistoryResponse_withNullDocIds() {
        var resp = new ChatHistoryResponse(2L, "session-xyz", "Hello", "Hi there", null, java.util.Map.of(), null);
        assertEquals(2L, resp.id());
        assertNull(resp.relatedDocumentIds());
    }

    // ========== BatchCreateAndEmbedRequest ==========

    @Test
    void batchCreateAndEmbedRequest_constructorAndGetters() {
        var req = new BatchCreateAndEmbedRequest();
        req.setCollectionId(1L);
        req.setDocuments(java.util.List.of(new DocumentRequest("Test Doc", "Content here")));
        req.setForce(true);
        assertEquals(1L, req.getCollectionId());
        assertEquals(1, req.getDocuments().size());
        assertTrue(req.isForce());
    }

    @Test
    void batchCreateAndEmbedRequest_defaults() {
        var req = new BatchCreateAndEmbedRequest();
        assertFalse(req.isForce());
    }

    // ========== BatchCreateAndEmbedResponse ==========

    @Test
    void batchCreateAndEmbedResponse_constructor() {
        var item = new BatchCreateAndEmbedResponse.DocumentResult(1L, "Doc", true, 5, null);
        var resp = new BatchCreateAndEmbedResponse(10, 8, 2, 0, java.util.List.of(item));
        assertEquals(10, resp.created());
        assertEquals(8, resp.embedded());
        assertEquals(2, resp.skipped());
        assertEquals(0, resp.failed());
        assertEquals(1, resp.results().size());
    }

    @Test
    void batchCreateAndEmbedResponse_documentResult() {
        var result = new BatchCreateAndEmbedResponse.DocumentResult(99L, "Failed Doc", false, 0, "Embedding failed");
        assertEquals(99L, result.documentId());
        assertEquals("Failed Doc", result.title());
        assertFalse(result.embedded());
        assertEquals(0, result.chunks());
        assertEquals("Embedding failed", result.error());
    }

    // ========== ApiSloComplianceResponse ==========

    @Test
    void apiSloComplianceResponse_constructor() {
        var latencyStats = new ApiSloComplianceResponse.LatencyStats(50.0, 95.0, 99.0, 10.0, 200.0, 88.8);
        var endpoint = new ApiSloComplianceResponse.EndpointSlo("rag.search.post", "GET", 500L, 98.0, 100, 98, 2, latencyStats);
        var resp = new ApiSloComplianceResponse(true, 300, java.util.List.of(endpoint));
        assertTrue(resp.enabled());
        assertEquals(300, resp.windowSeconds());
        assertEquals(1, resp.endpoints().size());
    }

    @Test
    void apiSloComplianceResponse_endpointSlo() {
        var latencyStats = new ApiSloComplianceResponse.LatencyStats(50.0, 95.0, 99.0, 10.0, 200.0, 88.8);
        var endpoint = new ApiSloComplianceResponse.EndpointSlo("rag.chat.stream", "POST", 1000L, 100.0, 200, 200, 0, latencyStats);
        assertEquals("rag.chat.stream", endpoint.endpoint());
        assertEquals("POST", endpoint.method());
        assertEquals(1000L, endpoint.thresholdMs());
        assertEquals(100.0, endpoint.compliancePercent(), 0.001);
        assertEquals(200, endpoint.requestCount());
        assertEquals(200, endpoint.sloCount());
        assertEquals(0, endpoint.breachCount());
    }

    @Test
    void documentListResponse_constructorAndGetters() {
        var summary = new DocumentSummary(1L, "Doc1", "src", "PDF", "COMPLETED",
                null, 1024L, "abc", true, null, null, null, 3L, "preview", null, null);
        var resp = new DocumentListResponse(List.of(summary), 1, 0, 20);
        assertEquals(1, resp.documents().size());
        assertEquals(1, resp.total());
        assertEquals(0, resp.offset());
        assertEquals(20, resp.limit());
    }

    @Test
    void documentSummary_allFields() {
        var ts = java.time.LocalDateTime.now();
        var doc = new DocumentSummary(42L, "Title", "https://example.com", "MARKDOWN",
                "PENDING", ts, 2048L, "hash123", false, ts, 1L, "KB", 5L,
                "preview text", "full content", Map.of("key", "val"));
        assertEquals(42L, doc.id());
        assertEquals("Title", doc.title());
        assertEquals("MARKDOWN", doc.documentType());
        assertFalse(doc.enabled());
        assertEquals(5L, doc.chunkCount());
        assertEquals("full content", doc.content());
    }

    @Test
    void searchResponse_ofFactory() {
        var result = new RetrievalResult();
        result.setDocumentId("doc-1");
        result.setChunkText("matched content");
        result.setScore(0.9);
        result.setTitle("Doc Title");
        var resp = SearchResponse.of(List.of(result), "query");
        assertEquals(1, resp.total());
        assertEquals("query", resp.query());
        assertEquals(1, resp.results().size());
        assertEquals("doc-1", resp.results().get(0).getDocumentId());
    }

    @Test
    void fireAlertRequest_constructorAndGetters() {
        var req = new FireAlertRequest("manual", "Test Alert", "msg", "WARNING", Map.of("cpu", 90));
        assertEquals("manual", req.alertType());
        assertEquals("Test Alert", req.alertName());
        assertEquals("WARNING", req.severity());
        assertEquals(90, req.metrics().get("cpu"));
    }

    @Test
    void silenceAlertRequest_constructorAndGetters() {
        var req = new SilenceAlertRequest("high-latency", 60);
        assertEquals("high-latency", req.alertKey());
        assertEquals(60, req.durationMinutes());
    }

    @Test
    void silenceScheduleRequest_gettersAndSetters() {
        var req = new SilenceScheduleRequest();
        req.setName("weekend");
        req.setAlertKey("alert1");
        req.setSilenceType("ONE_TIME");
        req.setStartTime("2026-04-10T02:00:00");
        req.setEndTime("2026-04-10T04:00:00");
        req.setDescription("maintenance");
        req.setEnabled(true);
        req.setMetadata(Map.of("a", 1));
        assertEquals("weekend", req.getName());
        assertEquals("alert1", req.getAlertKey());
        assertEquals("ONE_TIME", req.getSilenceType());
        assertTrue(req.getEnabled());
        assertEquals(1, req.getMetadata().get("a"));
    }

    @Test
    void sloConfigRequest_gettersAndSetters() {
        var req = new SloConfigRequest();
        req.setSloName("latency_p99");
        req.setSloType("LATENCY");
        req.setTargetValue(200.0);
        req.setUnit("ms");
        req.setDescription("P99 under 200ms");
        req.setEnabled(true);
        assertEquals("latency_p99", req.getSloName());
        assertEquals(200.0, req.getTargetValue());
        assertTrue(req.getEnabled());
    }

    @Test
    void fileTreeEntryResponse_record() {
        var entry = new FileTreeEntryResponse("default.md", "uuid/default.md", "file", "text/markdown", 1024);
        assertEquals("default.md", entry.name());
        assertEquals("uuid/default.md", entry.path());
        assertEquals("file", entry.type());
        assertEquals("text/markdown", entry.mimeType());
        assertEquals(1024, entry.size());
    }

    @Test
    void fileTreeEntryResponse_directory() {
        var dir = new FileTreeEntryResponse("subdir", "uuid/subdir", "directory", null, 0);
        assertEquals("directory", dir.type());
        assertNull(dir.mimeType());
    }

    @Test
    void fileTreeResponse_record() {
        var entries = List.of(
                new FileTreeEntryResponse("a.md", "p/a.md", "file", "text/markdown", 100));
        var resp = new FileTreeResponse("p/", entries, 1);
        assertEquals("p/", resp.path());
        assertEquals(1, resp.total());
        assertEquals("a.md", resp.entries().get(0).name());
    }

    @Test
    void healthResponse_ofFactory() {
        var resp = HealthResponse.of("UP", Map.of("db", "UP", "pgvector", "UP"));
        assertEquals("UP", resp.status());
        assertNotNull(resp.timestamp());
        assertEquals(2, resp.components().size());
    }

    @Test
    void componentHealthResponse_ofFactory() {
        var comp = Map.of("db", Map.<String, Object>of("status", "UP", "latency", 5));
        var resp = ComponentHealthResponse.of("DEGRADED", comp);
        assertEquals("DEGRADED", resp.status());
        assertEquals("UP", resp.components().get("db").get("status"));
    }

    @Test
    void slowQueryStatsResponse_record() {
        var records = List.of(new SlowQueryStatsResponse.SlowQueryRecordDto(1000L, 1500L, "SELECT * FROM t"));
        var resp = new SlowQueryStatsResponse(true, 1000L, 500L, 5L, 45L, records);
        assertTrue(resp.enabled());
        assertEquals(1000L, resp.thresholdMs());
        assertEquals(5, resp.slowQueryCount());
        assertEquals(1, resp.recentSlowQueries().size());
        assertEquals(1500L, resp.recentSlowQueries().get(0).durationMs());
    }

    @Test
    void ragMetricsSummary_ofFactory() {
        var summary = RagMetricsSummary.of(1000, 950, 50, 95.0, 5000, 100000);
        assertEquals(1000, summary.totalRequests());
        assertEquals(950, summary.successfulRequests());
        assertEquals(95.0, summary.successRate(), 0.01);
        assertNotNull(summary.timestamp());
    }

    // ========== RagMetricsSummary equals/hashCode/toString ==========

    @Test
    void ragMetricsSummary_equals_sameFields() {
        var ts = java.time.Instant.parse("2026-04-25T00:00:00Z");
        var a = new RagMetricsSummary(1000, 950, 50, 95.0, 5000, 100000, ts);
        var b = new RagMetricsSummary(1000, 950, 50, 95.0, 5000, 100000, ts);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void ragMetricsSummary_equals_differentSuccessRate() {
        var ts = java.time.Instant.parse("2026-04-25T00:00:00Z");
        var a = new RagMetricsSummary(1000, 950, 50, 95.0, 5000, 100000, ts);
        var b = new RagMetricsSummary(1000, 950, 50, 99.9, 5000, 100000, ts);
        assertNotEquals(a, b);
    }

    @Test
    void ragMetricsSummary_equals_differentTimestamp() {
        var ts1 = java.time.Instant.parse("2026-04-25T00:00:00Z");
        var ts2 = java.time.Instant.parse("2026-04-26T00:00:00Z");
        var a = new RagMetricsSummary(1000, 950, 50, 95.0, 5000, 100000, ts1);
        var b = new RagMetricsSummary(1000, 950, 50, 95.0, 5000, 100000, ts2);
        assertNotEquals(a, b);
    }

    @Test
    void ragMetricsSummary_toString() {
        var ts = java.time.Instant.parse("2026-04-25T00:00:00Z");
        var summary = new RagMetricsSummary(1000, 950, 50, 95.0, 5000, 100000, ts);
        var str = summary.toString();
        assertTrue(str.contains("1000"));
        assertTrue(str.contains("950"));
        assertTrue(str.contains("95.0"));
    }

    @Test
    void modelListResponse_ofFactory() {
        var resp = ModelListResponse.of(true, "openai", List.of("openai", "anthropic"),
                List.of("openai"), List.of(Map.of("name", "gpt-4o")));
        assertTrue(resp.multiModelEnabled());
        assertEquals("openai", resp.defaultProvider());
        assertEquals(2, resp.availableProviders().size());
        assertEquals(1, resp.models().size());
    }

    @Test
    void modelCompareResponse_record() {
        var result = new ModelCompareResponse.ModelCompareResult(
                "openai/gpt-4o", true, "RAG is...", 1250L, 150, 320, 470, null);
        var resp = new ModelCompareResponse("What is RAG?", List.of("openai"), List.of(result));
        assertEquals("What is RAG?", resp.query());
        assertEquals(1, resp.results().size());
        assertTrue(resp.results().get(0).success());
        assertEquals(1250L, resp.results().get(0).latencyMs());
    }

    @Test
    void modelCompareResult_failureCase() {
        var result = new ModelCompareResponse.ModelCompareResult(
                "broken", false, null, null, null, null, null, "API error");
        assertFalse(result.success());
        assertEquals("API error", result.error());
    }

    @Test
    void modelDetailResponse_ofFactory() {
        var resp = ModelDetailResponse.of(true, Map.of("provider", "openai", "model", "gpt-4o"));
        assertTrue(resp.available());
        assertEquals("openai", resp.details().get("provider"));
    }

    @Test
    void modelMetricsResponse_record() {
        var metric = new ModelMetricsResponse.ModelMetric("openai", 1000, 5, 0.005, "OpenAI GPT-4o");
        var resp = new ModelMetricsResponse(true, List.of(metric));
        assertTrue(resp.multiModelEnabled());
        assertEquals(1, resp.models().size());
        assertEquals("openai", resp.models().get(0).provider());
        assertEquals(0.005, resp.models().get(0).errorRate(), 0.001);
    }

    @Test
    void batchEmbedProgressEvent_overallPercent() {
        var event = new BatchEmbedProgressEvent(2, 10, 5L, "EMBEDDING", 5, 10, "msg", 1, 0, 1);
        assertEquals(20, event.overallPercent());
        assertEquals("EMBEDDING", event.phase());
    }

    @Test
    void batchEmbedProgressEvent_zeroDocs() {
        var event = new BatchEmbedProgressEvent(0, 0, null, "DONE", 0, 0, "done", 0, 0, 0);
        assertEquals(0, event.overallPercent());
    }

    @Test
    void embedProgressEvent_factoryMethods() {
        var preparing = EmbedProgressEvent.preparing(1L);
        assertEquals("PREPARING", preparing.phase());

        var chunking = EmbedProgressEvent.chunking(1L, 10);
        assertEquals("CHUNKING", chunking.phase());
        assertEquals(10, chunking.total());

        var embedding = EmbedProgressEvent.embedding(1L, 5, 10);
        assertEquals("EMBEDDING", embedding.phase());
        assertEquals(5, embedding.current());

        var storing = EmbedProgressEvent.storing(1L, 3, 10);
        assertEquals("STORING", storing.phase());

        var completed = EmbedProgressEvent.completed(1L, 10);
        assertEquals("COMPLETED", completed.phase());

        var failed = EmbedProgressEvent.failed(1L, "timeout");
        assertEquals("FAILED", failed.phase());
        assertTrue(failed.message().contains("timeout"));
    }

    // ========== BatchEmbedProgressEvent equals/hashCode/toString ==========

    @Test
    void batchEmbedProgressEvent_equals_sameFields() {
        var a = new BatchEmbedProgressEvent(2, 10, 5L, "EMBEDDING", 5, 10, "msg", 1, 0, 1);
        var b = new BatchEmbedProgressEvent(2, 10, 5L, "EMBEDDING", 5, 10, "msg", 1, 0, 1);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void batchEmbedProgressEvent_equals_differentPhase() {
        var a = new BatchEmbedProgressEvent(2, 10, 5L, "EMBEDDING", 5, 10, "msg", 1, 0, 1);
        var b = new BatchEmbedProgressEvent(2, 10, 5L, "COMPLETED", 5, 10, "msg", 1, 0, 1);
        assertNotEquals(a, b);
    }

    @Test
    void batchEmbedProgressEvent_equals_nullDocId() {
        var a = new BatchEmbedProgressEvent(2, 10, null, "DONE", 5, 10, "msg", 1, 0, 1);
        var b = new BatchEmbedProgressEvent(2, 10, null, "DONE", 5, 10, "msg", 1, 0, 1);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void batchEmbedProgressEvent_toString() {
        var event = new BatchEmbedProgressEvent(2, 10, 5L, "EMBEDDING", 5, 10, "msg", 1, 0, 1);
        var str = event.toString();
        assertTrue(str.contains("EMBEDDING"));
        assertTrue(str.contains("2"));
    }

    // ========== EmbedProgressEvent equals/hashCode/toString ==========

    @Test
    void embedProgressEvent_equals_sameFields() {
        var a = new EmbedProgressEvent("EMBEDDING", 5, 10, "chunk 5/10", 42L);
        var b = new EmbedProgressEvent("EMBEDDING", 5, 10, "chunk 5/10", 42L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void embedProgressEvent_equals_differentPhase() {
        var a = new EmbedProgressEvent("EMBEDDING", 5, 10, "msg", 42L);
        var b = new EmbedProgressEvent("COMPLETED", 5, 10, "msg", 42L);
        assertNotEquals(a, b);
    }

    @Test
    void embedProgressEvent_equals_nullDocumentId() {
        var a = new EmbedProgressEvent("PREPARING", 0, 0, "prep", null);
        var b = new EmbedProgressEvent("PREPARING", 0, 0, "prep", null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void embedProgressEvent_toString() {
        var event = new EmbedProgressEvent("EMBEDDING", 5, 10, "chunk 5/10", 42L);
        var str = event.toString();
        assertTrue(str.contains("EMBEDDING"));
        assertTrue(str.contains("42"));
    }

    @Test
    void pdfToRagResponse_record() {
        var resp = new PdfToRagResponse(42L, "doc_title", true, "COMPLETED", "ok", 23, "uuid-123", "uuid-123/default.md");
        assertEquals(42L, resp.documentId());
        assertTrue(resp.newlyCreated());
        assertEquals(23, resp.chunksCreated());
    }

    @Test
    void fileUploadResponse_record() {
        var result = new FileUploadResponse.FileResult("doc.txt", 1L, "Doc", true, 5, null);
        var resp = new FileUploadResponse(1, 1, 0, List.of(result));
        assertEquals(1, resp.processed());
        assertEquals(5, resp.results().get(0).chunks());
        assertNull(resp.results().get(0).error());
    }

    @Test
    void fileUploadResponse_withError() {
        var result = new FileUploadResponse.FileResult("bad.txt", null, null, false, 0, "parse error");
        var resp = new FileUploadResponse(1, 0, 1, List.of(result));
        assertEquals(1, resp.failed());
        assertEquals("parse error", resp.results().get(0).error());
    }

    @Test
    void cacheInvalidateResponse_fromCleared() {
        var resp = CacheInvalidateResponse.from(5);
        assertEquals(5, resp.cleared());
        assertEquals("Cache invalidated", resp.message());
    }

    @Test
    void cacheInvalidateResponse_fromZero() {
        var resp = CacheInvalidateResponse.from(0);
        assertEquals("No entries to clear", resp.message());
    }

    @Test
    void cacheInvalidateResponse_fromMap() {
        var resp = CacheInvalidateResponse.from(Map.of("cleared", 3, "message", "done"));
        assertEquals(3, resp.cleared());
        assertEquals("done", resp.message());
    }

    // ========== ChatRequest equals/hashCode/toString ==========

    @Test
    void chatRequest_equals_sameFields() {
        ChatRequest r1 = new ChatRequest("hello", "s1");
        r1.setMaxResults(10);
        r1.setUseHybridSearch(true);
        r1.setUseRerank(false);
        r1.setDomainId("medical");
        r1.setModel("deepseek");
        r1.setMetadata(Map.of("key", "val"));

        ChatRequest r2 = new ChatRequest("hello", "s1");
        r2.setMaxResults(10);
        r2.setUseHybridSearch(true);
        r2.setUseRerank(false);
        r2.setDomainId("medical");
        r2.setModel("deepseek");
        r2.setMetadata(Map.of("key", "val"));

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void chatRequest_equals_differentMessage_notEqual() {
        ChatRequest r1 = new ChatRequest("hello", "s1");
        ChatRequest r2 = new ChatRequest("world", "s1");
        assertNotEquals(r1, r2);
    }

    @Test
    void chatRequest_equals_scoresIgnored() {
        ChatRequest r1 = new ChatRequest("hello", "s1");
        r1.setMaxResults(10);
        r1.setUseHybridSearch(true);

        ChatRequest r2 = new ChatRequest("hello", "s1");
        r2.setMaxResults(99);
        r2.setUseHybridSearch(false);

        assertNotEquals(r1, r2);
    }

    @Test
    void chatRequest_toString_containsKeyFields() {
        ChatRequest r = new ChatRequest("hello world", "session-abc");
        r.setMaxResults(5);
        r.setUseHybridSearch(true);
        r.setUseRerank(true);
        r.setDomainId("legal");

        String str = r.toString();
        assertTrue(str.contains("hello world"));
        assertTrue(str.contains("session-abc"));
        assertTrue(str.contains("maxResults=5"));
        assertTrue(str.contains("useHybridSearch=true"));
        assertTrue(str.contains("legal"));
    }

    // ========== AnswerQualityResponse equals/hashCode/toString ==========

    @Test
    void answerQualityResponse_equals_sameFields() {
        var t = ZonedDateTime.now();
        AnswerQualityResponse r1 = new AnswerQualityResponse(4, 5, 3, "good", "ACCEPT", t);
        AnswerQualityResponse r2 = new AnswerQualityResponse(4, 5, 3, "good", "ACCEPT", t);
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void answerQualityResponse_equals_differentGroundedness_notEqual() {
        var t = ZonedDateTime.now();
        AnswerQualityResponse r1 = new AnswerQualityResponse(4, 5, 3, "good", "ACCEPT", t);
        AnswerQualityResponse r2 = new AnswerQualityResponse(3, 5, 3, "good", "ACCEPT", t);
        assertNotEquals(r1, r2);
    }

    @Test
    void answerQualityResponse_toString_containsKeyFields() {
        var t = ZonedDateTime.parse("2026-04-07T00:30:00+08:00");
        AnswerQualityResponse r = new AnswerQualityResponse(4, 5, 3, "well reasoned", "ACCEPT", t);
        String str = r.toString();
        assertTrue(str.contains("groundedness=4"));
        assertTrue(str.contains("relevance=5"));
        assertTrue(str.contains("helpfulness=3"));
        assertTrue(str.contains("ACCEPT"));
    }

    // ========== SilenceScheduleRequest equals/hashCode/toString ==========

    @Test
    void silenceScheduleRequest_equals_sameFields() {
        SilenceScheduleRequest r1 = new SilenceScheduleRequest();
        r1.setName("maintenance");
        r1.setAlertKey("high-latency");
        r1.setSilenceType("RECURRING");
        r1.setStartTime("0 0 2 * * SAT");
        r1.setEndTime("0 0 6 * * SAT");
        r1.setDescription("weekend window");
        r1.setEnabled(true);
        r1.setMetadata(Map.of("owner", "ops"));

        SilenceScheduleRequest r2 = new SilenceScheduleRequest();
        r2.setName("maintenance");
        r2.setAlertKey("high-latency");
        r2.setSilenceType("RECURRING");
        r2.setStartTime("0 0 2 * * SAT");
        r2.setEndTime("0 0 6 * * SAT");
        r2.setDescription("weekend window");
        r2.setEnabled(true);
        r2.setMetadata(Map.of("owner", "ops"));

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void silenceScheduleRequest_equals_differentName_notEqual() {
        SilenceScheduleRequest r1 = new SilenceScheduleRequest();
        r1.setName("win1");
        SilenceScheduleRequest r2 = new SilenceScheduleRequest();
        r2.setName("win2");
        assertNotEquals(r1, r2);
    }

    @Test
    void silenceScheduleRequest_toString_containsKeyFields() {
        SilenceScheduleRequest r = new SilenceScheduleRequest();
        r.setName("weekend-maintenance");
        r.setSilenceType("RECURRING");
        r.setEnabled(false);

        String str = r.toString();
        assertTrue(str.contains("weekend-maintenance"));
        assertTrue(str.contains("RECURRING"));
        assertTrue(str.contains("enabled=false"));
    }

    // ========== SloConfigRequest equals/hashCode/toString ==========

    @Test
    void sloConfigRequest_equals_sameFields() {
        SloConfigRequest r1 = new SloConfigRequest();
        r1.setSloName("latency_p99");
        r1.setSloType("LATENCY");
        r1.setTargetValue(200.0);
        r1.setUnit("ms");
        r1.setDescription("P99 under 200ms");
        r1.setEnabled(true);
        r1.setMetadata(Map.of("service", "rag"));

        SloConfigRequest r2 = new SloConfigRequest();
        r2.setSloName("latency_p99");
        r2.setSloType("LATENCY");
        r2.setTargetValue(200.0);
        r2.setUnit("ms");
        r2.setDescription("P99 under 200ms");
        r2.setEnabled(true);
        r2.setMetadata(Map.of("service", "rag"));

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void sloConfigRequest_equals_differentTargetValue_notEqual() {
        SloConfigRequest r1 = new SloConfigRequest();
        r1.setSloName("slo1");
        r1.setTargetValue(100.0);
        SloConfigRequest r2 = new SloConfigRequest();
        r2.setSloName("slo1");
        r2.setTargetValue(200.0);
        assertNotEquals(r1, r2);
    }

    @Test
    void sloConfigRequest_toString_containsKeyFields() {
        SloConfigRequest r = new SloConfigRequest();
        r.setSloName("availability_p99");
        r.setSloType("AVAILABILITY");
        r.setTargetValue(99.9);
        r.setUnit("%");

        String str = r.toString();
        assertTrue(str.contains("availability_p99"));
        assertTrue(str.contains("AVAILABILITY"));
        assertTrue(str.contains("99.9"));
    }

    // ========== ErrorResponse equals/hashCode/toString ==========

    @Test
    void errorResponse_equals_sameFields() {
        ErrorResponse r1 = new ErrorResponse();
        r1.setType("https://springairag.dev/problems/validation-failed");
        r1.setTitle("Validation Failed");
        r1.setStatus(400);
        r1.setDetail("Query must not be blank");
        r1.setInstance("/api/v1/rag/chat/ask");
        r1.setError("VALIDATION_FAILED");
        r1.setMessage("Query must not be blank");
        r1.setPath("/api/v1/rag/chat/ask");

        ErrorResponse r2 = new ErrorResponse();
        r2.setType("https://springairag.dev/problems/validation-failed");
        r2.setTitle("Validation Failed");
        r2.setStatus(400);
        r2.setDetail("Query must not be blank");
        r2.setInstance("/api/v1/rag/chat/ask");
        r2.setError("VALIDATION_FAILED");
        r2.setMessage("Query must not be blank");
        r2.setPath("/api/v1/rag/chat/ask");

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
        // timestamp intentionally excluded from equals, but same instance equals itself
        assertEquals(r1, r1);
    }

    @Test
    void errorResponse_equals_differentDetail_notEqual() {
        ErrorResponse r1 = new ErrorResponse();
        r1.setError("ERR1");
        r1.setDetail("Error one");
        ErrorResponse r2 = new ErrorResponse();
        r2.setError("ERR1");
        r2.setDetail("Error two");
        assertNotEquals(r1, r2);
    }

    @Test
    void errorResponse_toString_containsKeyFields() {
        ErrorResponse r = new ErrorResponse();
        r.setType("https://springairag.dev/problems/not-found");
        r.setTitle("Not Found");
        r.setStatus(404);
        r.setDetail("Document not found");
        r.setPath("/api/v1/rag/documents/99");

        String str = r.toString();
        assertTrue(str.contains("Not Found"));
        assertTrue(str.contains("404"));
        assertTrue(str.contains("Document not found"));
        assertTrue(str.contains("/api/v1/rag/documents/99"));
    }

    // ========== ApiKeyCreatedResponse equals/hashCode/toString ==========

    @Test
    void apiKeyCreatedResponse_equals_sameFields() {
        LocalDateTime expires = LocalDateTime.of(2027, 1, 1, 0, 0);
        ApiKeyCreatedResponse r1 = new ApiKeyCreatedResponse();
        r1.setKeyId("rag_k_abc123");
        r1.setRawKey("rag_sk_secret");
        r1.setName("Prod Server");
        r1.setExpiresAt(expires);
        r1.setWarning("Save this key now.");

        ApiKeyCreatedResponse r2 = new ApiKeyCreatedResponse();
        r2.setKeyId("rag_k_abc123");
        r2.setRawKey("rag_sk_secret");
        r2.setName("Prod Server");
        r2.setExpiresAt(expires);
        r2.setWarning("Save this key now.");

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void apiKeyCreatedResponse_equals_differentKeyId_notEqual() {
        ApiKeyCreatedResponse r1 = new ApiKeyCreatedResponse();
        r1.setKeyId("rag_k_abc");
        ApiKeyCreatedResponse r2 = new ApiKeyCreatedResponse();
        r2.setKeyId("rag_k_xyz");
        assertNotEquals(r1, r2);
    }

    @Test
    void apiKeyCreatedResponse_toString_containsKeyFields() {
        ApiKeyCreatedResponse r = new ApiKeyCreatedResponse();
        r.setKeyId("rag_k_test");
        r.setName("Test Key");
        r.setExpiresAt(LocalDateTime.of(2027, 6, 15, 12, 0));
        r.setWarning("Save now.");

        String str = r.toString();
        assertTrue(str.contains("rag_k_test"));
        assertTrue(str.contains("Test Key"));
        assertTrue(str.contains("Save now."));
        // rawKey intentionally excluded from toString (security)
        assertFalse(str.contains("rag_sk_"));
    }

    // ========== ApiKeyResponse equals/hashCode/toString ==========

    @Test
    void apiKeyResponse_equals_sameFields() {
        LocalDateTime created = LocalDateTime.of(2026, 4, 12, 3, 50);
        LocalDateTime expires = LocalDateTime.of(2027, 1, 1, 0, 0);
        ApiKeyResponse r1 = new ApiKeyResponse();
        r1.setKeyId("rag_k_abc123");
        r1.setName("Production Server");
        r1.setCreatedAt(created);
        r1.setLastUsedAt(LocalDateTime.of(2026, 4, 12, 10, 0));
        r1.setExpiresAt(expires);
        r1.setEnabled(true);
        r1.setRole("ADMIN");

        ApiKeyResponse r2 = new ApiKeyResponse();
        r2.setKeyId("rag_k_abc123");
        r2.setName("Production Server");
        r2.setCreatedAt(created);
        r2.setLastUsedAt(LocalDateTime.of(2026, 4, 12, 10, 0));
        r2.setExpiresAt(expires);
        r2.setEnabled(true);
        r2.setRole("ADMIN");

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void apiKeyResponse_equals_differentRole_notEqual() {
        ApiKeyResponse r1 = new ApiKeyResponse();
        r1.setKeyId("rag_k_abc");
        r1.setRole("ADMIN");
        ApiKeyResponse r2 = new ApiKeyResponse();
        r2.setKeyId("rag_k_abc");
        r2.setRole("NORMAL");
        assertNotEquals(r1, r2);
    }

    @Test
    void apiKeyResponse_toString_containsKeyFields() {
        ApiKeyResponse r = new ApiKeyResponse();
        r.setKeyId("rag_k_test");
        r.setName("Test Key");
        r.setEnabled(false);
        r.setRole("NORMAL");

        String str = r.toString();
        assertTrue(str.contains("rag_k_test"));
        assertTrue(str.contains("Test Key"));
        assertTrue(str.contains("NORMAL"));
    }

    // ========== BatchDocumentRequest equals/hashCode/toString ==========

    @Test
    void batchDocumentRequest_equals_sameFields() {
        DocumentRequest doc = new DocumentRequest("Title", "Content");
        List<DocumentRequest> docs = List.of(doc);

        BatchDocumentRequest r1 = new BatchDocumentRequest();
        r1.setDocuments(docs);
        r1.setEmbed(true);
        r1.setCollectionId(5L);
        r1.setForce(false);

        BatchDocumentRequest r2 = new BatchDocumentRequest();
        r2.setDocuments(docs);
        r2.setEmbed(true);
        r2.setCollectionId(5L);
        r2.setForce(false);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void batchDocumentRequest_equals_differentCollectionId_notEqual() {
        BatchDocumentRequest r1 = new BatchDocumentRequest();
        r1.setCollectionId(1L);
        BatchDocumentRequest r2 = new BatchDocumentRequest();
        r2.setCollectionId(2L);
        assertNotEquals(r1, r2);
    }

    @Test
    void batchDocumentRequest_toString_containsKeyFields() {
        BatchDocumentRequest r = new BatchDocumentRequest();
        r.setCollectionId(10L);
        r.setEmbed(true);
        r.setForce(true);

        String str = r.toString();
        assertTrue(str.contains("10"));
        assertTrue(str.contains("embed=true"));
        assertTrue(str.contains("force=true"));
    }

    // ========== CollectionRequest equals/hashCode/toString ==========

    @Test
    void collectionRequest_equals_sameFields() {
        CollectionRequest r1 = new CollectionRequest();
        r1.setName("KB1");
        r1.setDescription("Knowledge base one");
        r1.setEmbeddingModel("BAAI/bge-m3");
        r1.setDimensions(1024);
        r1.setEnabled(true);
        r1.setMetadata(Map.of("team", "engineering"));

        CollectionRequest r2 = new CollectionRequest();
        r2.setName("KB1");
        r2.setDescription("Knowledge base one");
        r2.setEmbeddingModel("BAAI/bge-m3");
        r2.setDimensions(1024);
        r2.setEnabled(true);
        r2.setMetadata(Map.of("team", "engineering"));

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void collectionRequest_equals_differentDimensions_notEqual() {
        CollectionRequest r1 = new CollectionRequest();
        r1.setDimensions(1024);
        CollectionRequest r2 = new CollectionRequest();
        r2.setDimensions(768);
        assertNotEquals(r1, r2);
    }

    @Test
    void collectionRequest_toString_containsKeyFields() {
        CollectionRequest r = new CollectionRequest();
        r.setName("MyKB");
        r.setEmbeddingModel("BAAI/bge-m3");
        r.setDimensions(1024);
        r.setEnabled(false);

        String str = r.toString();
        assertTrue(str.contains("MyKB"));
        assertTrue(str.contains("BAAI/bge-m3"));
        assertTrue(str.contains("1024"));
    }

    // ========== DocumentRequest equals/hashCode/toString ==========

    @Test
    void documentRequest_equals_sameFields() {
        DocumentRequest r1 = new DocumentRequest();
        r1.setTitle("Product Manual");
        r1.setContent("How to use the product.");
        r1.setSource("manual-upload");
        r1.setDocumentType("markdown");
        r1.setMetadata(Map.of("version", "1.0"));
        r1.setCollectionId(1L);

        DocumentRequest r2 = new DocumentRequest();
        r2.setTitle("Product Manual");
        r2.setContent("How to use the product.");
        r2.setSource("manual-upload");
        r2.setDocumentType("markdown");
        r2.setMetadata(Map.of("version", "1.0"));
        r2.setCollectionId(1L);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void documentRequest_equals_differentTitle_notEqual() {
        DocumentRequest r1 = new DocumentRequest();
        r1.setTitle("Title A");
        DocumentRequest r2 = new DocumentRequest();
        r2.setTitle("Title B");
        assertNotEquals(r1, r2);
    }

    @Test
    void documentRequest_toString_containsKeyFields() {
        DocumentRequest r = new DocumentRequest();
        r.setTitle("Short Title");
        r.setContent("Very long content that should be truncated in toString output.");
        r.setSource("upload");
        r.setDocumentType("pdf");

        String str = r.toString();
        assertTrue(str.contains("Short Title"));
        assertTrue(str.contains("upload"));
        assertTrue(str.contains("pdf"));
    }

    // ========== FeedbackRequest equals/hashCode/toString ==========

    @Test
    void feedbackRequest_equals_sameFields() {
        FeedbackRequest r1 = new FeedbackRequest();
        r1.setSessionId("sess-001");
        r1.setQuery("How to configure RAG?");
        r1.setFeedbackType("THUMBS_UP");
        r1.setRating(5);
        r1.setComment("Great answer!");
        r1.setRetrievedDocumentIds(List.of(1L, 2L));
        r1.setSelectedDocumentIds(List.of(1L));
        r1.setDwellTimeMs(5000L);

        FeedbackRequest r2 = new FeedbackRequest();
        r2.setSessionId("sess-001");
        r2.setQuery("How to configure RAG?");
        r2.setFeedbackType("THUMBS_UP");
        r2.setRating(5);
        r2.setComment("Great answer!");
        r2.setRetrievedDocumentIds(List.of(1L, 2L));
        r2.setSelectedDocumentIds(List.of(1L));
        r2.setDwellTimeMs(5000L);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void feedbackRequest_equals_differentRating_notEqual() {
        FeedbackRequest r1 = new FeedbackRequest();
        r1.setRating(5);
        FeedbackRequest r2 = new FeedbackRequest();
        r2.setRating(3);
        assertNotEquals(r1, r2);
    }

    @Test
    void feedbackRequest_toString_containsKeyFields() {
        FeedbackRequest r = new FeedbackRequest();
        r.setSessionId("sess-xyz");
        r.setFeedbackType("THUMBS_DOWN");
        r.setRating(2);

        String str = r.toString();
        assertTrue(str.contains("sess-xyz"));
        assertTrue(str.contains("THUMBS_DOWN"));
        assertTrue(str.contains("2"));
    }

    // ========== SearchRequest equals/hashCode/toString ==========

    @Test
    void searchRequest_equals_sameFields() {
        RetrievalConfig config = new RetrievalConfig();
        SearchRequest r1 = new SearchRequest();
        r1.setQuery("What is Spring AI?");
        r1.setDocumentIds(List.of(1L, 2L));
        r1.setCollectionIds(List.of(5L));
        r1.setConfig(config);

        SearchRequest r2 = new SearchRequest();
        r2.setQuery("What is Spring AI?");
        r2.setDocumentIds(List.of(1L, 2L));
        r2.setCollectionIds(List.of(5L));
        r2.setConfig(config);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void searchRequest_equals_differentQuery_notEqual() {
        SearchRequest r1 = new SearchRequest();
        r1.setQuery("Query A");
        SearchRequest r2 = new SearchRequest();
        r2.setQuery("Query B");
        assertNotEquals(r1, r2);
    }

    @Test
    void searchRequest_toString_containsKeyFields() {
        SearchRequest r = new SearchRequest();
        r.setQuery("vector search");
        r.setCollectionIds(List.of(1L, 2L));

        String str = r.toString();
        assertTrue(str.contains("vector search"));
        assertTrue(str.contains("[1")); // List toString shows [1, 2]
    }

    // ========== ClientErrorRequest equals/hashCode/toString ==========

    @Test
    void clientErrorRequest_equals_sameFields() {
        ClientErrorRequest r1 = new ClientErrorRequest();
        r1.setErrorType("Error");
        r1.setErrorMessage("Cannot read properties of undefined");
        r1.setStackTrace("TypeError at line 42");
        r1.setPageUrl("/webui/chat");
        r1.setSessionId("sess-abc");
        r1.setUserId("user-123");

        ClientErrorRequest r2 = new ClientErrorRequest();
        r2.setErrorType("Error");
        r2.setErrorMessage("Cannot read properties of undefined");
        r2.setStackTrace("TypeError at line 42");
        r2.setPageUrl("/webui/chat");
        r2.setSessionId("sess-abc");
        r2.setUserId("user-123");

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void clientErrorRequest_equals_differentErrorType_notEqual() {
        ClientErrorRequest r1 = new ClientErrorRequest();
        r1.setErrorType("Error");
        ClientErrorRequest r2 = new ClientErrorRequest();
        r2.setErrorType("TypeError");
        assertNotEquals(r1, r2);
    }

    @Test
    void clientErrorRequest_toString_containsKeyFields() {
        ClientErrorRequest r = new ClientErrorRequest();
        r.setErrorType("ReferenceError");
        r.setErrorMessage("x is not defined");
        r.setPageUrl("/webui/search");
        r.setSessionId("sess-999");
        r.setUserId("admin");

        String str = r.toString();
        assertTrue(str.contains("ReferenceError"));
        assertTrue(str.contains("x is not defined"));
        assertTrue(str.contains("/webui/search"));
        assertTrue(str.contains("sess-999"));
        assertTrue(str.contains("admin"));
    }

    // ========== ApiKeyCreateRequest equals/hashCode/toString ==========

    @Test
    void apiKeyCreateRequest_equals_sameFields() {
        LocalDateTime expires = LocalDateTime.of(2027, 12, 31, 23, 59);
        ApiKeyCreateRequest r1 = new ApiKeyCreateRequest();
        r1.setName("Admin Key");
        r1.setExpiresAt(expires);

        ApiKeyCreateRequest r2 = new ApiKeyCreateRequest();
        r2.setName("Admin Key");
        r2.setExpiresAt(expires);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void apiKeyCreateRequest_equals_differentName_notEqual() {
        ApiKeyCreateRequest r1 = new ApiKeyCreateRequest();
        r1.setName("Key A");
        ApiKeyCreateRequest r2 = new ApiKeyCreateRequest();
        r2.setName("Key B");
        assertNotEquals(r1, r2);
    }

    @Test
    void apiKeyCreateRequest_toString_containsKeyFields() {
        ApiKeyCreateRequest r = new ApiKeyCreateRequest();
        r.setName("Read-Only Key");
        r.setExpiresAt(LocalDateTime.of(2028, 1, 1, 0, 0));

        String str = r.toString();
        assertTrue(str.contains("Read-Only Key"));
        assertTrue(str.contains("2028"));
    }

    // ========== BatchCreateAndEmbedRequest equals/hashCode/toString ==========

    @Test
    void batchCreateAndEmbedRequest_equals_sameFields() {
        DocumentRequest doc = new DocumentRequest("T", "C");
        List<DocumentRequest> docs = List.of(doc);

        BatchCreateAndEmbedRequest r1 = new BatchCreateAndEmbedRequest();
        r1.setCollectionId(7L);
        r1.setDocuments(docs);
        r1.setForce(true);

        BatchCreateAndEmbedRequest r2 = new BatchCreateAndEmbedRequest();
        r2.setCollectionId(7L);
        r2.setDocuments(docs);
        r2.setForce(true);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void batchCreateAndEmbedRequest_equals_differentForce_notEqual() {
        BatchCreateAndEmbedRequest r1 = new BatchCreateAndEmbedRequest();
        r1.setCollectionId(1L);
        r1.setForce(false);
        BatchCreateAndEmbedRequest r2 = new BatchCreateAndEmbedRequest();
        r2.setCollectionId(1L);
        r2.setForce(true);
        assertNotEquals(r1, r2);
    }

    @Test
    void batchCreateAndEmbedRequest_toString_containsKeyFields() {
        BatchCreateAndEmbedRequest r = new BatchCreateAndEmbedRequest();
        r.setCollectionId(15L);
        r.setForce(true);

        String str = r.toString();
        assertTrue(str.contains("15"));
        assertTrue(str.contains("force=true"));
    }

    // ========== EvaluateRequest equals/hashCode/toString ==========

    @Test
    void evaluateRequest_equals_sameFields() {
        EvaluateRequest r1 = new EvaluateRequest();
        r1.setQuery("How does RAG work?");
        r1.setRetrievedDocIds(List.of(1L, 2L, 3L));
        r1.setRelevantDocIds(List.of(1L, 3L));
        r1.setEvaluationMethod("AUTO");
        r1.setEvaluatorId("eval-1");

        EvaluateRequest r2 = new EvaluateRequest();
        r2.setQuery("How does RAG work?");
        r2.setRetrievedDocIds(List.of(1L, 2L, 3L));
        r2.setRelevantDocIds(List.of(1L, 3L));
        r2.setEvaluationMethod("AUTO");
        r2.setEvaluatorId("eval-1");

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void evaluateRequest_equals_differentEvaluationMethod_notEqual() {
        EvaluateRequest r1 = new EvaluateRequest();
        r1.setEvaluationMethod("AUTO");
        EvaluateRequest r2 = new EvaluateRequest();
        r2.setEvaluationMethod("MANUAL");
        assertNotEquals(r1, r2);
    }

    @Test
    void evaluateRequest_toString_containsKeyFields() {
        EvaluateRequest r = new EvaluateRequest();
        r.setQuery("test query");
        r.setRetrievedDocIds(List.of(1L, 2L));
        r.setRelevantDocIds(List.of(1L));
        r.setEvaluationMethod("AUTO");
        r.setEvaluatorId("auto-eval");

        String str = r.toString();
        assertTrue(str.contains("test query"));
        assertTrue(str.contains("AUTO"));
        assertTrue(str.contains("auto-eval"));
    }
}
