package com.springairag.core.service;

import com.springairag.api.dto.BatchEmbedProgressEvent;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DocumentEmbedService Unit Tests
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
    @DisplayName("embedDocument: chunk -> embed -> store")
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
    @DisplayName("embedDocument: throws when document not found")
    void embedDocument_notFound_throws() {
        when(documentRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(DocumentNotFoundException.class, () -> service.embedDocument(999L));
    }

    @Test
    @DisplayName("embedDocument: throws when content is empty")
    void embedDocument_emptyContent_throws() {
        RagDocument doc = createDocument(1L, "");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        assertThrows(IllegalArgumentException.class, () -> service.embedDocument(1L));
    }

    @Test
    @DisplayName("embedDocument: content too short returns skip message")
    void embedDocument_contentTooShort_returnsSkipped() {
        RagDocument doc = createDocument(1L, "太短");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

        Map<String, Object> output = service.embedDocument(1L);

        assertEquals(0, output.get("chunksCreated"));
        assertTrue(((String) output.get("message")).toLowerCase().contains("short"));
        verifyNoInteractions(embeddingBatchService, jdbcTemplate);
    }

    // ==================== embedDocumentViaVectorStore ====================

    @Test
    @DisplayName("embedDocumentViaVectorStore: happy path")
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
    @DisplayName("embedDocumentViaVectorStore: throws when VectorStore not configured")
    void embedDocumentViaVectorStore_noVectorStore_throws() {
        DocumentEmbedService noStoreService = new DocumentEmbedService(
                documentRepository, embeddingRepository, embeddingBatchService,
                jdbcTemplate, null, new RagProperties());
        assertThrows(IllegalStateException.class,
                () -> noStoreService.embedDocumentViaVectorStore(1L));
    }

    @Test
    @DisplayName("embedDocumentViaVectorStore: throws when document not found")
    void embedDocumentViaVectorStore_notFound_throws() {
        when(documentRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(DocumentNotFoundException.class,
                () -> service.embedDocumentViaVectorStore(99L));
    }

    @Test
    @DisplayName("embedDocumentViaVectorStore: throws when content is empty")
    void embedDocumentViaVectorStore_emptyContent_throws() {
        RagDocument doc = createDocument(1L, null);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        assertThrows(IllegalArgumentException.class,
                () -> service.embedDocumentViaVectorStore(1L));
    }

    @Test
    @DisplayName("embedDocumentViaVectorStore: returns skip when content too short")
    void embedDocumentViaVectorStore_contentTooShort_returnsSkipped() {
        RagDocument doc = createDocument(1L, "短");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

        Map<String, Object> output = service.embedDocumentViaVectorStore(1L);

        assertEquals(0, output.get("chunksCreated"));
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("embedDocumentViaVectorStore: title stored in embedding metadata")
    void embedDocumentViaVectorStore_titleStoredInMetadata() {
        RagDocument doc = createDocument(2L, "测试标题内容。".repeat(50));
        doc.setTitle("My Test Document Title");
        when(documentRepository.findById(2L)).thenReturn(Optional.of(doc));

        service.embedDocumentViaVectorStore(2L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        List<Document> capturedDocs = captor.getValue();
        assertFalse(capturedDocs.isEmpty());
        Document firstDoc = capturedDocs.get(0);
        assertEquals("My Test Document Title", firstDoc.getMetadata().get("title"));
        assertEquals("2", firstDoc.getMetadata().get("documentId"));
    }

    // ==================== batchEmbedDocuments ====================

    @Test
    @DisplayName("batchEmbedDocuments: all documents embed successfully")
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
    @DisplayName("batchEmbedDocuments: missing documents counted as skipped")
    void batchEmbedDocuments_notFound_skipped() {
        when(documentRepository.findById(1L)).thenReturn(Optional.empty());

        Map<String, Object> output = service.batchEmbedDocuments(List.of(1L));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) output.get("summary");
        assertEquals(0, summary.get("success"));
        assertEquals(1, summary.get("skipped"));
    }

    @Test
    @DisplayName("batchEmbedDocuments: empty content counted as skipped")
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
    @DisplayName("batchEmbedDocuments: embedding error counted as failed")
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
    @DisplayName("batchEmbedDocuments: throws when exceeding 50 document limit")
    void batchEmbedDocuments_exceedsLimit_throws() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 51).boxed().toList();
        assertThrows(IllegalArgumentException.class,
                () -> service.batchEmbedDocuments(ids));
    }

    @Test
    @DisplayName("batchEmbedDocuments: result order matches input order")
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
    @DisplayName("isVectorStoreAvailable: returns true when VectorStore present")
    void isVectorStoreAvailable_withStore() {
        assertTrue(service.isVectorStoreAvailable());
    }

    @Test
    @DisplayName("isVectorStoreAvailable: returns false when VectorStore absent")
    void isVectorStoreAvailable_withoutStore() {
        DocumentEmbedService noStoreService = new DocumentEmbedService(
                documentRepository, embeddingRepository, embeddingBatchService,
                jdbcTemplate, null, new RagProperties());
        assertFalse(noStoreService.isVectorStoreAvailable());
    }

    // ==================== Content Hash Cache ====================

    @Test
    @DisplayName("embed cache: content unchanged (status=COMPLETED, same hash, has embeddings) -> cache hit")
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
        // should not call embedding API
        verifyNoInteractions(embeddingBatchService);
    }

    @Test
    @DisplayName("embed cache: content changed -> re-embed")
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
    @DisplayName("embed cache: embeddedContentHash is null -> re-embed")
    void embedDocument_nullEmbeddedHash_reEmbeds() {
        RagDocument doc = createDocumentWithHash(1L, "内容。".repeat(50), "hash");
        doc.setProcessingStatus("COMPLETED");
        // embeddedContentHash not set
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
    @DisplayName("embed cache: updates embeddedContentHash after embedding")
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
    @DisplayName("embed cache: VectorStore path content unchanged -> cache hit")
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
    @DisplayName("embed cache: force=true skips hash check, forces re-embed")
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

    // ==================== embedDocumentWithProgress ====================

    @Test
    @DisplayName("embedDocumentWithProgress: null callback does not throw (maybeEmit null-safe)")
    void embedDocumentWithProgress_nullCallback_noException() {
        RagDocument doc = createDocument(1L, "这是第一段内容。这是第二段内容。".repeat(50));
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

        float[] vec = new float[3];
        EmbeddingBatchService.EmbeddingResult result =
                new EmbeddingBatchService.EmbeddingResult("text", vec, null);
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenReturn(List.of(result));

        // null callback should not throw NPE
        Map<String, Object> output = service.embedDocumentWithProgress(1L, false, null);

        assertEquals("COMPLETED", output.get("status"));
    }

    @Test
    @DisplayName("embedDocumentWithProgress: null documentId throws IllegalArgumentException")
    void embedDocumentWithProgress_nullDocumentId_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.embedDocumentWithProgress(null, false, null));
    }

    @Test
    @DisplayName("batchEmbedDocuments: null documentIds throws IllegalArgumentException")
    void batchEmbedDocuments_nullList_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.batchEmbedDocuments(null));
    }

    @Test
    @DisplayName("embedDocumentWithProgress: cache hit with chunks=null does not throw")
    void embedDocumentWithProgress_cacheHit_chunksNull_noException() {
        RagDocument doc = createDocumentWithHash(1L, "短内容", "hash123");
        doc.setProcessingStatus("COMPLETED");
        doc.setEmbeddedContentHash("hash123");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(embeddingRepository.countByDocumentId(1L)).thenReturn(2L);

        // Cache hit: embeddedContentHash matches contentHash, returns early (chunks=null)
        java.util.List<String> phases = new java.util.ArrayList<>();
        Map<String, Object> output = service.embedDocumentWithProgress(1L, false, event -> phases.add(event.phase()));

        assertEquals("CACHED", output.get("status"));
        assertEquals(List.of("PREPARING", "COMPLETED"), phases,
                "cache hit should trigger PREPARING + COMPLETED: " + phases);
    }

    @Test
    @DisplayName("embedDocumentWithProgress: progress callback fires each phase correctly")
    void embedDocumentWithProgress_progressCallback_allPhases() {
        // Force re-embed: force=true bypasses content hash cache check
        RagDocument doc = createDocumentWithHash(1L, "这是一段很长的文档内容，用于测试嵌入进度回调是否正确触发各个阶段。".repeat(10), "forcehash");
        doc.setProcessingStatus("COMPLETED");
        doc.setEmbeddedContentHash("otherhash"); // mismatch -> force re-embed
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(documentRepository.save(any())).thenReturn(doc);

        float[] vec = new float[1024]; // BGE-M3 dimensions
        // mock returns 1 embedding, chunker also produces 1 chunk
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenReturn(List.of(new EmbeddingBatchService.EmbeddingResult("chunk1", vec, null)));

        java.util.List<String> phases = new java.util.ArrayList<>();
        service.embedDocumentWithProgress(1L, true, event -> phases.add(event.phase()));

        assertTrue(phases.contains("PREPARING"), "should contain PREPARING: " + phases);
        assertTrue(phases.contains("CHUNKING"), "should contain CHUNKING: " + phases);
        assertTrue(phases.contains("EMBEDDING"), "should contain EMBEDDING: " + phases);
        assertTrue(phases.contains("STORING"), "should contain STORING: " + phases);
        assertTrue(phases.contains("COMPLETED"), "should contain COMPLETED: " + phases);
        // EMBEDDING phase should fire once (1 chunk)
        assertEquals(1, phases.stream().filter(p -> p.equals("EMBEDDING")).count(),
                "EMBEDDING should fire once: " + phases);
    }

    // ==================== batchEmbedDocumentsWithProgress ====================

    @Test
    @DisplayName("batchEmbedDocumentsWithProgress: 3 docs all cache hit, counters accumulate correctly")
    void batchWithProgress_allCached_countersCorrect() {
        List<Long> ids = List.of(1L, 2L, 3L);
        for (Long id : ids) {
            RagDocument doc = createDocumentWithHash(id, "内容" + id, "hash" + id);
            doc.setProcessingStatus("COMPLETED");
            doc.setEmbeddedContentHash("hash" + id);
            when(documentRepository.findById(id)).thenReturn(Optional.of(doc));
            when(embeddingRepository.countByDocumentId(id)).thenReturn(3L); // cache hit
        }

        List<BatchEmbedProgressEvent> events = new java.util.ArrayList<>();
        Map<String, Object> result = service.batchEmbedDocumentsWithProgress(ids, events::add);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(3, summary.get("cached"));
        assertEquals(0, summary.get("success"));
        assertEquals(0, summary.get("failed"));
        assertEquals(0, summary.get("skipped"));
        assertEquals(3, summary.get("total"));
        // PREPARING + CACHED per doc = 6 events
        assertEquals(6, events.size(), "3 docs × 2 events each: " + events);
    }

    @Test
    @DisplayName("batchEmbedDocumentsWithProgress: mixed statuses (COMPLETED/CACHED/SKIPPED)")
    void batchWithProgress_mixedStatuses_countersCorrect() {
        // doc1: NOT_FOUND (no mock for findById(1L)) → status unset → SKIPPED via default
        // doc2: CACHED
        RagDocument doc2 = createDocumentWithHash(2L, "cached content that is long enough to chunk", "h2");
        doc2.setProcessingStatus("COMPLETED");
        doc2.setEmbeddedContentHash("h2");
        when(documentRepository.findById(2L)).thenReturn(Optional.of(doc2));
        when(embeddingRepository.countByDocumentId(2L)).thenReturn(5L);
        // doc3: COMPLETED (must exceed minChunkSize=100, use ~200 chars)
        RagDocument doc3 = createDocumentWithHash(3L, "third doc content that is definitely long enough to meet the minimum chunk size requirement of at least 100 characters. Adding more content here to ensure it is well above the threshold.", "h3");
        doc3.setProcessingStatus("COMPLETED");
        doc3.setEmbeddedContentHash("h3");
        when(documentRepository.findById(3L)).thenReturn(Optional.of(doc3));
        when(embeddingRepository.countByDocumentId(3L)).thenReturn(0L);
        float[] vec = new float[1024];
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenReturn(List.of(new EmbeddingBatchService.EmbeddingResult("c", vec, null)));

        List<BatchEmbedProgressEvent> events = new java.util.ArrayList<>();
        Map<String, Object> result = service.batchEmbedDocumentsWithProgress(List.of(1L, 2L, 3L), events::add);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, summary.get("success"), "doc3 COMPLETED: " + summary);
        assertEquals(0, summary.get("failed"));
        assertEquals(1, summary.get("cached"), "doc2 CACHED: " + summary);
        assertEquals(1, summary.get("skipped"), "doc1 NOT_FOUND: " + summary);

        // Verify phases
        List<String> phases = events.stream().map(BatchEmbedProgressEvent::phase).toList();
        assertTrue(phases.contains("CACHED"), "should contain CACHED: " + phases);
        assertTrue(phases.contains("COMPLETED"), "should contain COMPLETED: " + phases);
        assertTrue(phases.contains("SKIPPED"), "should contain SKIPPED: " + phases);
    }

    @Test
    @DisplayName("batchEmbedDocumentsWithProgress: null callback does not throw (maybeEmit null-safe)")
    void batchWithProgress_nullCallback_noException() {
        RagDocument doc = createDocumentWithHash(1L, "内容", "hash1");
        doc.setProcessingStatus("COMPLETED");
        doc.setEmbeddedContentHash("hash1");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(embeddingRepository.countByDocumentId(1L)).thenReturn(3L);

        // Should not throw
        Map<String, Object> result = service.batchEmbedDocumentsWithProgress(List.of(1L), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, summary.get("total"));
    }

    @Test
    @DisplayName("batchEmbedDocumentsWithProgress: single document returns correct batch result")
    void batchWithProgress_singleDoc_buildBatchResultCorrect() {
        RagDocument doc = createDocumentWithHash(1L, "single", "h1");
        doc.setProcessingStatus("COMPLETED");
        doc.setEmbeddedContentHash("h1");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(embeddingRepository.countByDocumentId(1L)).thenReturn(3L);

        Map<String, Object> result = service.batchEmbedDocumentsWithProgress(List.of(1L), null);

        assertNotNull(result.get("results"));
        assertNotNull(result.get("summary"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, summary.get("total"));
    }
}
