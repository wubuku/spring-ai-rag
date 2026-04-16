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
}
