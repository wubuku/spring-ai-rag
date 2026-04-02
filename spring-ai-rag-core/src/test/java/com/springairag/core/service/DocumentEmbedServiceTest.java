package com.springairag.core.service;

import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DocumentEmbedService 单元测试
 */
class DocumentEmbedServiceTest {

    private RagDocumentRepository documentRepository;
    private RagEmbeddingRepository embeddingRepository;
    private EmbeddingBatchService embeddingBatchService;
    private JdbcTemplate jdbcTemplate;
    private VectorStore vectorStore;
    private DocumentEmbedService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        embeddingBatchService = mock(EmbeddingBatchService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        vectorStore = mock(VectorStore.class);
        service = new DocumentEmbedService(
                documentRepository, embeddingRepository, embeddingBatchService,
                jdbcTemplate, vectorStore, new RagProperties());
    }

    private RagDocument createDocument(Long id, String content) {
        RagDocument doc = new RagDocument();
        doc.setId(id);
        doc.setContent(content);
        return doc;
    }

    private RagDocument createDocumentWithHash(Long id, String content, String contentHash) {
        RagDocument doc = createDocument(id, content);
        doc.setContentHash(contentHash);
        return doc;
    }

    // ==================== embedDocument ====================

    @Test
    @DisplayName("embedDocument: 正常分块→嵌入→存储")
    void embedDocument_success() {
        RagDocument doc = createDocument(1L, "这是第一段内容。这是第二段内容。".repeat(50));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

        float[] vec = new float[3];
        EmbeddingBatchService.EmbeddingResult result =
                new EmbeddingBatchService.EmbeddingResult("text", vec, null);
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenReturn(List.of(result));

        Map<String, Object> output = service.embedDocument(1L);

        assertEquals(1L, output.get("documentId"));
        assertEquals("COMPLETED", output.get("status"));
        assertTrue((int) output.get("chunksCreated") > 0);
        assertTrue((int) output.get("embeddingsStored") > 0);

        verify(embeddingRepository).deleteByDocumentId(1L);
        verify(jdbcTemplate, atLeastOnce()).update(anyString(), any(), any(), any(), any(), any(), any());
        verify(documentRepository, times(2)).save(doc);
        assertEquals("COMPLETED", doc.getProcessingStatus());
    }

    @Test
    @DisplayName("embedDocument: 文档不存在抛异常")
    void embedDocument_notFound_throws() {
        when(documentRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(DocumentNotFoundException.class, () -> service.embedDocument(999L));
    }

    @Test
    @DisplayName("embedDocument: 内容为空抛异常")
    void embedDocument_emptyContent_throws() {
        RagDocument doc = createDocument(1L, "");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        assertThrows(IllegalArgumentException.class, () -> service.embedDocument(1L));
    }

    @Test
    @DisplayName("embedDocument: 内容太短无需分块返回提示")
    void embedDocument_contentTooShort_returnsSkipped() {
        RagDocument doc = createDocument(1L, "太短");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

        Map<String, Object> output = service.embedDocument(1L);

        assertEquals(0, output.get("chunksCreated"));
        assertTrue(((String) output.get("message")).contains("太短"));
        verifyNoInteractions(embeddingBatchService, jdbcTemplate);
    }

    // ==================== embedDocumentViaVectorStore ====================

    @Test
    @DisplayName("embedDocumentViaVectorStore: 正常路径")
    void embedDocumentViaVectorStore_success() {
        RagDocument doc = createDocument(2L, "向量存储测试内容。".repeat(50));
        when(documentRepository.findById(2L)).thenReturn(Optional.of(doc));

        Map<String, Object> output = service.embedDocumentViaVectorStore(2L);

        assertEquals(2L, output.get("documentId"));
        assertEquals("COMPLETED", output.get("status"));
        assertTrue((int) output.get("chunksCreated") > 0);
        assertEquals("rag_vector_store", output.get("storageTable"));

        verify(vectorStore).add(anyList());
        verify(documentRepository, times(2)).save(doc);
    }

    @Test
    @DisplayName("embedDocumentViaVectorStore: VectorStore 未配置抛异常")
    void embedDocumentViaVectorStore_noVectorStore_throws() {
        DocumentEmbedService noStoreService = new DocumentEmbedService(
                documentRepository, embeddingRepository, embeddingBatchService,
                jdbcTemplate, null, new RagProperties());
        assertThrows(IllegalStateException.class,
                () -> noStoreService.embedDocumentViaVectorStore(1L));
    }

    @Test
    @DisplayName("embedDocumentViaVectorStore: 文档不存在抛异常")
    void embedDocumentViaVectorStore_notFound_throws() {
        when(documentRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(DocumentNotFoundException.class,
                () -> service.embedDocumentViaVectorStore(99L));
    }

    @Test
    @DisplayName("embedDocumentViaVectorStore: 内容为空抛异常")
    void embedDocumentViaVectorStore_emptyContent_throws() {
        RagDocument doc = createDocument(1L, null);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        assertThrows(IllegalArgumentException.class,
                () -> service.embedDocumentViaVectorStore(1L));
    }

    @Test
    @DisplayName("embedDocumentViaVectorStore: 内容太短返回提示")
    void embedDocumentViaVectorStore_contentTooShort_returnsSkipped() {
        RagDocument doc = createDocument(1L, "短");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

        Map<String, Object> output = service.embedDocumentViaVectorStore(1L);

        assertEquals(0, output.get("chunksCreated"));
        verifyNoInteractions(vectorStore);
    }

    // ==================== batchEmbedDocuments ====================

    @Test
    @DisplayName("batchEmbedDocuments: 批量嵌入全部成功")
    void batchEmbedDocuments_allSuccess() {
        RagDocument doc1 = createDocument(1L, "文档1内容。".repeat(50));
        RagDocument doc2 = createDocument(2L, "文档2内容。".repeat(50));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc1));
        when(documentRepository.findById(2L)).thenReturn(Optional.of(doc2));

        float[] vec = new float[3];
        EmbeddingBatchService.EmbeddingResult embResult =
                new EmbeddingBatchService.EmbeddingResult("text", vec, null);
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenReturn(List.of(embResult));

        Map<String, Object> output = service.batchEmbedDocuments(List.of(1L, 2L));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) output.get("summary");
        assertEquals(2, summary.get("total"));
        assertEquals(2, summary.get("success"));
        assertEquals(0, summary.get("failed"));
        assertEquals(0, summary.get("skipped"));
    }

    @Test
    @DisplayName("batchEmbedDocuments: 文档不存在算 skipped")
    void batchEmbedDocuments_notFound_skipped() {
        when(documentRepository.findById(1L)).thenReturn(Optional.empty());

        Map<String, Object> output = service.batchEmbedDocuments(List.of(1L));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) output.get("summary");
        assertEquals(0, summary.get("success"));
        assertEquals(1, summary.get("skipped"));
    }

    @Test
    @DisplayName("batchEmbedDocuments: 内容为空算 skipped")
    void batchEmbedDocuments_emptyContent_skipped() {
        RagDocument doc = createDocument(1L, "");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

        Map<String, Object> output = service.batchEmbedDocuments(List.of(1L));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) output.get("summary");
        assertEquals(1, summary.get("skipped"));
        assertEquals(0, summary.get("success"));
    }

    @Test
    @DisplayName("batchEmbedDocuments: 嵌入异常算 failed")
    void batchEmbedDocuments_exceptionFailed() {
        RagDocument doc = createDocument(1L, "有内容的文档。".repeat(50));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenThrow(new RuntimeException("API 挂了"));

        Map<String, Object> output = service.batchEmbedDocuments(List.of(1L));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) output.get("summary");
        assertEquals(1, summary.get("failed"));
        assertEquals(0, summary.get("success"));
    }

    @Test
    @DisplayName("batchEmbedDocuments: 超过 50 条限制抛异常")
    void batchEmbedDocuments_exceedsLimit_throws() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 51).boxed().toList();
        assertThrows(IllegalArgumentException.class,
                () -> service.batchEmbedDocuments(ids));
    }

    @Test
    @DisplayName("batchEmbedDocuments: 结果列表顺序与输入一致")
    void batchEmbedDocuments_resultOrderPreserved() {
        RagDocument doc1 = createDocument(10L, "内容1。".repeat(50));
        RagDocument doc2 = createDocument(20L, "内容2。".repeat(50));
        when(documentRepository.findById(10L)).thenReturn(Optional.of(doc1));
        when(documentRepository.findById(20L)).thenReturn(Optional.empty());

        float[] vec = new float[3];
        EmbeddingBatchService.EmbeddingResult embResult =
                new EmbeddingBatchService.EmbeddingResult("text", vec, null);
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenReturn(List.of(embResult));

        Map<String, Object> output = service.batchEmbedDocuments(List.of(10L, 20L));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
        assertEquals(2, results.size());
        assertEquals(10L, results.get(0).get("documentId"));
        assertEquals("COMPLETED", results.get(0).get("status"));
        assertEquals(20L, results.get(1).get("documentId"));
        assertEquals("NOT_FOUND", results.get(1).get("status"));
    }

    // ==================== isVectorStoreAvailable ====================

    @Test
    @DisplayName("isVectorStoreAvailable: 有 VectorStore 返回 true")
    void isVectorStoreAvailable_withStore() {
        assertTrue(service.isVectorStoreAvailable());
    }

    @Test
    @DisplayName("isVectorStoreAvailable: 无 VectorStore 返回 false")
    void isVectorStoreAvailable_withoutStore() {
        DocumentEmbedService noStoreService = new DocumentEmbedService(
                documentRepository, embeddingRepository, embeddingBatchService,
                jdbcTemplate, null, new RagProperties());
        assertFalse(noStoreService.isVectorStoreAvailable());
    }

    // ==================== 内容哈希缓存 ====================

    @Test
    @DisplayName("嵌入缓存: 内容未变更(status=COMPLETED, 同哈希, 有嵌入) → 命中缓存")
    void embedDocument_contentUnchanged_cacheHit() {
        String hash = "abc123";
        RagDocument doc = createDocumentWithHash(1L, "固定内容。".repeat(50), hash);
        doc.setProcessingStatus("COMPLETED");
        doc.setEmbeddedContentHash(hash);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(embeddingRepository.countByDocumentId(1L)).thenReturn(5L);

        Map<String, Object> output = service.embedDocument(1L);

        assertEquals("CACHED", output.get("status"));
        assertEquals(true, output.get("cached"));
        assertEquals(5L, output.get("embeddingsStored"));
        // 不应调用嵌入 API
        verifyNoInteractions(embeddingBatchService);
    }

    @Test
    @DisplayName("嵌入缓存: 内容已变更 → 重新嵌入")
    void embedDocument_contentChanged_reEmbeds() {
        RagDocument doc = createDocumentWithHash(1L, "新内容。".repeat(50), "new_hash");
        doc.setProcessingStatus("COMPLETED");
        doc.setEmbeddedContentHash("old_hash");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

        float[] vec = new float[3];
        EmbeddingBatchService.EmbeddingResult result =
                new EmbeddingBatchService.EmbeddingResult("text", vec, null);
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenReturn(List.of(result));

        Map<String, Object> output = service.embedDocument(1L);

        assertEquals("COMPLETED", output.get("status"));
        assertTrue((int) output.get("chunksCreated") > 0);
        verify(embeddingBatchService).createEmbeddingsBatch(anyList());
    }

    @Test
    @DisplayName("嵌入缓存: embeddedContentHash 为 null → 重新嵌入")
    void embedDocument_nullEmbeddedHash_reEmbeds() {
        RagDocument doc = createDocumentWithHash(1L, "内容。".repeat(50), "hash");
        doc.setProcessingStatus("COMPLETED");
        // embeddedContentHash 未设置
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

        float[] vec = new float[3];
        EmbeddingBatchService.EmbeddingResult result =
                new EmbeddingBatchService.EmbeddingResult("text", vec, null);
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenReturn(List.of(result));

        Map<String, Object> output = service.embedDocument(1L);

        assertEquals("COMPLETED", output.get("status"));
        verify(embeddingBatchService).createEmbeddingsBatch(anyList());
    }

    @Test
    @DisplayName("嵌入缓存: 嵌入完成后更新 embeddedContentHash")
    void embedDocument_updatesEmbeddedHash() {
        String hash = "test_hash";
        RagDocument doc = createDocumentWithHash(1L, "测试内容。".repeat(50), hash);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

        float[] vec = new float[3];
        EmbeddingBatchService.EmbeddingResult result =
                new EmbeddingBatchService.EmbeddingResult("text", vec, null);
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenReturn(List.of(result));

        service.embedDocument(1L);

        assertEquals("COMPLETED", doc.getProcessingStatus());
        assertEquals(hash, doc.getEmbeddedContentHash());
        verify(documentRepository, atLeastOnce()).save(doc);
    }

    @Test
    @DisplayName("嵌入缓存: VectorStore 路径内容未变更 → 命中缓存")
    void embedDocumentViaVectorStore_contentUnchanged_cacheHit() {
        String hash = "vs_hash";
        RagDocument doc = createDocumentWithHash(2L, "内容。".repeat(50), hash);
        doc.setProcessingStatus("COMPLETED");
        doc.setEmbeddedContentHash(hash);
        when(documentRepository.findById(2L)).thenReturn(Optional.of(doc));
        when(embeddingRepository.countByDocumentId(2L)).thenReturn(3L);

        Map<String, Object> output = service.embedDocumentViaVectorStore(2L);

        assertEquals("CACHED", output.get("status"));
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("嵌入缓存: force=true 跳过哈希检查，强制重嵌入")
    void embedDocument_force_skipsCache() {
        String hash = "same_hash";
        RagDocument doc = createDocumentWithHash(1L, "内容。".repeat(50), hash);
        doc.setProcessingStatus("COMPLETED");
        doc.setEmbeddedContentHash(hash);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(embeddingRepository.countByDocumentId(1L)).thenReturn(5L);

        float[] vec = new float[3];
        EmbeddingBatchService.EmbeddingResult result =
                new EmbeddingBatchService.EmbeddingResult("text", vec, null);
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenReturn(List.of(result));

        Map<String, Object> output = service.embedDocument(1L, true);

        assertEquals("COMPLETED", output.get("status"));
        verify(embeddingBatchService).createEmbeddingsBatch(anyList());
    }
}
