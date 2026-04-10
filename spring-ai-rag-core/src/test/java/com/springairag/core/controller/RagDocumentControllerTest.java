package com.springairag.core.controller;

import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.BatchDeleteItem;
import com.springairag.api.dto.BatchDeleteResponse;
import com.springairag.api.dto.BatchDeleteSummary;
import com.springairag.api.dto.DocumentDeleteResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.ReembedMissingResponse;
import com.springairag.api.dto.ReembedResultResponse;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagDocumentController 单元测试
 */
class RagDocumentControllerTest {

    private RagDocumentRepository documentRepository;
    private RagEmbeddingRepository embeddingRepository;
    private RagCollectionRepository collectionRepository;
    private DocumentEmbedService documentEmbedService;
    private BatchDocumentService batchDocumentService;
    private DocumentVersionService documentVersionService;
    private AuditLogService auditLogService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        collectionRepository = mock(RagCollectionRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        batchDocumentService = mock(BatchDocumentService.class);
        documentVersionService = mock(DocumentVersionService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new RagDocumentController(documentRepository, embeddingRepository, collectionRepository, documentEmbedService, batchDocumentService, documentVersionService, auditLogService);

        // Default mock behavior for documentToMap calls
        when(embeddingRepository.countByDocumentId(anyLong())).thenReturn(0L);
        when(collectionRepository.findById(any())).thenReturn(Optional.empty());
    }

    private RagDocument createDoc(Long id, String title, String content) {
        RagDocument doc = new RagDocument();
        doc.setId(id);
        doc.setTitle(title);
        doc.setContent(content);
        doc.setSource("unit-test");
        doc.setDocumentType("txt");
        doc.setEnabled(true);
        doc.setProcessingStatus("COMPLETED");
        doc.setCreatedAt(LocalDateTime.now());
        return doc;
    }

    private RagDocument createDoc(Long id, String title, String content, String documentType, String processingStatus) {
        RagDocument doc = createDoc(id, title, content);
        doc.setDocumentType(documentType);
        doc.setProcessingStatus(processingStatus);
        return doc;
    }

    @Test
    void createDocument_returnsId() {
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of());
        when(documentRepository.save(any(RagDocument.class))).thenAnswer(inv -> {
            RagDocument doc = inv.getArgument(0);
            doc.setId(42L);
            return doc;
        });

        DocumentRequest req = new DocumentRequest();
        req.setTitle("测试文档");
        req.setContent("这是测试内容");
        req.setSource("unit-test");

        ResponseEntity<Map<String, Object>> response = controller.createDocument(req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(42L, response.getBody().get("id"));
        assertEquals("测试文档", response.getBody().get("title"));
        assertEquals("CREATED", response.getBody().get("status"));
        assertNotNull(response.getBody().get("contentHash"));
    }

    @Test
    void createDocument_duplicateContent_returnsExisting() {
        RagDocument existing = createDoc(10L, "已有文档", "重复内容");
        String hash = BatchDocumentService.computeSha256("重复内容");

        when(documentRepository.findByContentHash(hash)).thenReturn(List.of(existing));

        DocumentRequest req = new DocumentRequest();
        req.setTitle("新标题");
        req.setContent("重复内容");

        ResponseEntity<Map<String, Object>> response = controller.createDocument(req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(10L, response.getBody().get("id"));
        assertEquals("DUPLICATE", response.getBody().get("status"));
        assertEquals(10L, response.getBody().get("existingDocumentId"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void computeSha256_sameContent_sameHash() {
        String hash1 = BatchDocumentService.computeSha256("hello");
        String hash2 = BatchDocumentService.computeSha256("hello");
        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length()); // SHA-256 = 32 bytes = 64 hex chars
    }

    @Test
    void computeSha256_differentContent_differentHash() {
        String hash1 = BatchDocumentService.computeSha256("hello");
        String hash2 = BatchDocumentService.computeSha256("world");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void getDocument_found() {
        RagDocument doc = createDoc(1L, "文档标题", "内容");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(embeddingRepository.countByDocumentId(1L)).thenReturn(5L);

        ResponseEntity<Map<String, Object>> response = controller.getDocument(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(5L, response.getBody().get("chunkCount"));
        assertEquals("文档标题", response.getBody().get("title"));
    }

    @Test
    void getDocument_notFound() {
        when(documentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class, () -> controller.getDocument(999L));
    }

    @Test
    void deleteDocument_found() {
        when(batchDocumentService.deleteDocument(1L)).thenReturn(
                new DocumentDeleteResponse("Document deleted", 1L, 3L));

        ResponseEntity<DocumentDeleteResponse> response = controller.deleteDocument(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Document deleted", response.getBody().message());
        assertEquals(3L, response.getBody().embeddingsRemoved());
        verify(batchDocumentService).deleteDocument(1L);
    }

    @Test
    void deleteDocument_notFound() {
        when(batchDocumentService.deleteDocument(999L))
                .thenThrow(new DocumentNotFoundException(999L));

        assertThrows(DocumentNotFoundException.class, () -> controller.deleteDocument(999L));
    }

    @Test
    void listDocuments_returnsPaginated() {
        List<RagDocument> docs = List.of(
                createDoc(1L, "文档1", "内容1"),
                createDoc(2L, "文档2", "内容2")
        );
        Page<RagDocument> page = new PageImpl<>(docs, PageRequest.of(0, 20), 2);
        when(documentRepository.searchDocuments(isNull(), isNull(), isNull(), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(page);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(0, 20, null, null, null, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2L, response.getBody().get("total"));
        List<?> resultDocs = (List<?>) response.getBody().get("documents");
        assertEquals(2, resultDocs.size());
    }

    @Test
    void listDocuments_filterByTitle() {
        List<RagDocument> docs = List.of(createDoc(1L, "Spring AI 入门", "内容"));
        Page<RagDocument> page = new PageImpl<>(docs, PageRequest.of(0, 20), 1);
        when(documentRepository.searchDocuments(eq("Spring"), isNull(), isNull(), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(page);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(0, 20, "Spring", null, null, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, response.getBody().get("total"));
        List<?> resultDocs = (List<?>) response.getBody().get("documents");
        assertEquals(1, resultDocs.size());
    }

    @Test
    void listDocuments_filterByDocumentType() {
        List<RagDocument> docs = List.of(createDoc(1L, "文档", "内容", "markdown", "COMPLETED"));
        Page<RagDocument> page = new PageImpl<>(docs, PageRequest.of(0, 20), 1);
        when(documentRepository.searchDocuments(isNull(), eq("markdown"), isNull(), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(page);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(0, 20, null, "markdown", null, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, response.getBody().get("total"));
    }

    @Test
    void listDocuments_filterByStatus() {
        List<RagDocument> docs = List.of(createDoc(1L, "文档", "内容", "txt", "PENDING"));
        Page<RagDocument> page = new PageImpl<>(docs, PageRequest.of(0, 20), 1);
        when(documentRepository.searchDocuments(isNull(), isNull(), eq("PENDING"), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(page);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(0, 20, null, null, "PENDING", null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, response.getBody().get("total"));
    }

    @Test
    void listDocuments_combinedFilters() {
        List<RagDocument> docs = List.of(createDoc(1L, "Spring Boot 教程", "内容", "markdown", "COMPLETED"));
        Page<RagDocument> page = new PageImpl<>(docs, PageRequest.of(0, 20), 1);
        when(documentRepository.searchDocuments(eq("Spring"), eq("markdown"), eq("COMPLETED"), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(page);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(0, 20, "Spring", "markdown", "COMPLETED", null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, response.getBody().get("total"));
    }

    @Test
    void listDocuments_emptyResult() {
        Page<RagDocument> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(documentRepository.searchDocuments(eq("不存在的标题"), isNull(), isNull(), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(page);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(0, 20, "不存在的标题", null, null, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0L, response.getBody().get("total"));
        List<?> resultDocs = (List<?>) response.getBody().get("documents");
        assertEquals(0, resultDocs.size());
    }

    @Test
    void getDocumentStats_returnsCounts() {
        List<Object[]> statusCounts = List.of(
                new Object[]{"COMPLETED", 10L},
                new Object[]{"PENDING", 3L},
                new Object[]{"FAILED", 1L}
        );
        when(documentRepository.countByProcessingStatus()).thenReturn(statusCounts);

        ResponseEntity<Map<String, Object>> response = controller.getDocumentStats();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(14L, response.getBody().get("total"));
        Map<?, ?> byStatus = (Map<?, ?>) response.getBody().get("byStatus");
        assertEquals(10L, byStatus.get("COMPLETED"));
        assertEquals(3L, byStatus.get("PENDING"));
        assertEquals(1L, byStatus.get("FAILED"));
    }

    @Test
    void getDocumentStats_empty() {
        when(documentRepository.countByProcessingStatus()).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.getDocumentStats();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0L, response.getBody().get("total"));
        Map<?, ?> byStatus = (Map<?, ?>) response.getBody().get("byStatus");
        assertTrue(byStatus.isEmpty());
    }

    @Test
    void embedDocument_found() {
        when(documentEmbedService.embedDocument(1L, false)).thenReturn(Map.of(
                "message", "嵌入向量生成完成",
                "documentId", 1L,
                "chunksCreated", 3,
                "embeddingsStored", 3,
                "status", "COMPLETED"
        ));

        ResponseEntity<?> response = controller.embedDocument(1L, false);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("COMPLETED", body.get("status"));
    }

    @Test
    void embedDocument_notFound() {
        when(documentEmbedService.embedDocument(999L, false))
                .thenThrow(new DocumentNotFoundException(999L));

        assertThrows(DocumentNotFoundException.class, () -> controller.embedDocument(999L, false));
    }

    @Test
    void embedDocument_emptyContent_returns400() {
        when(documentEmbedService.embedDocument(1L, false))
                .thenThrow(new IllegalArgumentException("Content is empty: documentId=1"));

        ResponseEntity<?> response = controller.embedDocument(1L, false);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Content is empty: documentId=1", ((ErrorResponse) response.getBody()).getDetail());
    }

    @Test
    void embedDocumentViaVectorStore_success() {
        when(documentEmbedService.embedDocumentViaVectorStore(1L, false)).thenReturn(Map.of(
                "message", "VectorStore embed completed",
                "documentId", 1L,
                "chunksCreated", 5,
                "embeddingsStored", 5,
                "storageTable", "rag_vector_store",
                "status", "COMPLETED"
        ));

        ResponseEntity<?> response = controller.embedDocumentViaVectorStore(1L, false);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(5, body.get("chunksCreated"));
        assertEquals("rag_vector_store", body.get("storageTable"));
        assertEquals("COMPLETED", body.get("status"));
    }

    @Test
    void embedDocumentViaVectorStore_noVectorStore_returns400() {
        when(documentEmbedService.embedDocumentViaVectorStore(1L, false))
                .thenThrow(new IllegalStateException("VectorStore not configured, use embedDocument instead"));

        ResponseEntity<?> response = controller.embedDocumentViaVectorStore(1L, false);

        assertEquals(400, response.getStatusCode().value());
        assertTrue(((ErrorResponse) response.getBody()).getDetail().contains("VectorStore not configured"));
    }

    @Test
    void embedDocumentViaVectorStore_notFound() {
        when(documentEmbedService.embedDocumentViaVectorStore(999L, false))
                .thenThrow(new DocumentNotFoundException(999L));

        assertThrows(DocumentNotFoundException.class, () -> controller.embedDocumentViaVectorStore(999L, false));
    }

    @Test
    void embedDocumentViaVectorStore_emptyContent_returns400() {
        when(documentEmbedService.embedDocumentViaVectorStore(1L, false))
                .thenThrow(new IllegalArgumentException("Content is empty"));

        ResponseEntity<?> response = controller.embedDocumentViaVectorStore(1L, false);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Content is empty", ((ErrorResponse) response.getBody()).getDetail());
    }

    // ==================== 批量操作测试 ====================

    @Test
    void batchCreateDocuments_allNew() {
        var docs = List.of(
                new DocumentRequest("文档1", "内容1"),
                new DocumentRequest("文档2", "内容2")
        );
        var svcResponse = new BatchCreateResponse(2, 0, 0, List.of(
                new BatchCreateResponse.DocumentResult(1L, "文档1", true, null),
                new BatchCreateResponse.DocumentResult(2L, "文档2", true, null)
        ));
        when(batchDocumentService.batchCreateDocuments(eq(docs), eq(false), isNull(), eq(false)))
                .thenReturn(svcResponse);

        var req = new com.springairag.api.dto.BatchDocumentRequest(docs);
        ResponseEntity<BatchCreateResponse> response = controller.batchCreateDocuments(req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().created());
        assertEquals(0, response.getBody().skipped());
        assertEquals(0, response.getBody().failed());
    }

    @Test
    void batchCreateDocuments_withDuplicates() {
        var docs = List.of(
                new DocumentRequest("新文档", "新内容"),
                new DocumentRequest("重复标题", "重复内容")
        );
        var svcResponse = new BatchCreateResponse(1, 1, 0, List.of(
                new BatchCreateResponse.DocumentResult(20L, "新文档", true, null),
                new BatchCreateResponse.DocumentResult(10L, "重复标题", false, null)
        ));
        when(batchDocumentService.batchCreateDocuments(eq(docs), eq(false), isNull(), eq(false)))
                .thenReturn(svcResponse);

        var req = new com.springairag.api.dto.BatchDocumentRequest(docs);
        ResponseEntity<BatchCreateResponse> response = controller.batchCreateDocuments(req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().created());
        assertEquals(1, response.getBody().skipped());
        assertEquals(0, response.getBody().failed());
    }

    @Test
    void batchCreateDocuments_withException() {
        var docs = List.of(new DocumentRequest("文档", "内容"));
        var svcResponse = new BatchCreateResponse(0, 0, 1, List.of(
                new BatchCreateResponse.DocumentResult(null, "文档", false, "DB error")
        ));
        when(batchDocumentService.batchCreateDocuments(eq(docs), eq(false), isNull(), eq(false)))
                .thenReturn(svcResponse);

        var req = new com.springairag.api.dto.BatchDocumentRequest(docs);
        ResponseEntity<BatchCreateResponse> response = controller.batchCreateDocuments(req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().failed());
    }

    @Test
    void batchDeleteDocuments_success() {
        when(batchDocumentService.batchDeleteDocuments(List.of(1L, 2L))).thenReturn(
                new BatchDeleteResponse(
                        List.of(new BatchDeleteItem(1L, "DELETED"), new BatchDeleteItem(2L, "DELETED")),
                        new BatchDeleteSummary(2, 2, 0)));

        ResponseEntity<BatchDeleteResponse> response = controller.batchDeleteDocuments(
                Map.of("ids", List.of(1L, 2L)));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().summary().deleted());
    }

    @Test
    void batchDeleteDocuments_someNotFound() {
        when(batchDocumentService.batchDeleteDocuments(List.of(1L, 999L))).thenReturn(
                new BatchDeleteResponse(
                        List.of(new BatchDeleteItem(1L, "DELETED"), new BatchDeleteItem(999L, "NOT_FOUND")),
                        new BatchDeleteSummary(2, 1, 1)));

        ResponseEntity<BatchDeleteResponse> response = controller.batchDeleteDocuments(
                Map.of("ids", List.of(1L, 999L)));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().summary().deleted());
        assertEquals(1, response.getBody().summary().notFound());
    }

    @Test
    void batchDeleteDocuments_emptyIds_returns400() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.batchDeleteDocuments(Map.of("ids", List.of())));
    }

    @Test
    void batchDeleteDocuments_tooManyIds_returns400() {
        when(batchDocumentService.batchDeleteDocuments(anyList()))
                .thenThrow(new IllegalArgumentException("单次批量删除不超过 100 条"));

        List<Long> manyIds = new ArrayList<>();
        for (int i = 0; i < 101; i++) manyIds.add((long) i);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> controller.batchDeleteDocuments(Map.of("ids", manyIds)));

        assertEquals("单次批量删除不超过 100 条", thrown.getMessage());
    }

    @Test
    void batchEmbedDocuments_success() {
        when(documentEmbedService.batchEmbedDocuments(List.of(1L, 2L))).thenReturn(Map.of(
                "results", List.of(
                        Map.of("documentId", 1L, "status", "COMPLETED", "chunksCreated", 5, "embeddingsStored", 5),
                        Map.of("documentId", 2L, "status", "COMPLETED", "chunksCreated", 3, "embeddingsStored", 3)
                ),
                "summary", Map.of("total", 2, "success", 2, "failed", 0, "skipped", 0)
        ));

        ResponseEntity<Map<String, Object>> response = controller.batchEmbedDocuments(
                Map.of("ids", List.of(1L, 2L)));

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> summary = (Map<?, ?>) response.getBody().get("summary");
        assertEquals(2, summary.get("total"));
        assertEquals(2, summary.get("success"));
    }

    @Test
    void batchEmbedDocuments_notFound_skipped() {
        when(documentEmbedService.batchEmbedDocuments(List.of(999L))).thenReturn(Map.of(
                "results", List.of(Map.of("documentId", 999L, "status", "NOT_FOUND")),
                "summary", Map.of("total", 1, "success", 0, "failed", 0, "skipped", 1)
        ));

        ResponseEntity<Map<String, Object>> response = controller.batchEmbedDocuments(
                Map.of("ids", List.of(999L)));

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> summary = (Map<?, ?>) response.getBody().get("summary");
        assertEquals(1, summary.get("skipped"));
    }

    @Test
    void batchEmbedDocuments_emptyContent_skipped() {
        when(documentEmbedService.batchEmbedDocuments(List.of(1L))).thenReturn(Map.of(
                "results", List.of(Map.of("documentId", 1L, "status", "SKIPPED", "reason", "内容为空")),
                "summary", Map.of("total", 1, "success", 0, "failed", 0, "skipped", 1)
        ));

        ResponseEntity<Map<String, Object>> response = controller.batchEmbedDocuments(
                Map.of("ids", List.of(1L)));

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> summary = (Map<?, ?>) response.getBody().get("summary");
        assertEquals(1, summary.get("skipped"));
    }

    @Test
    void batchEmbedDocuments_emptyIds_returns400() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.batchEmbedDocuments(Map.of("ids", List.of())));
    }

    @Test
    void batchEmbedDocuments_tooManyIds_returns400() {
        // Controller 限制 50 条，超过则抛 IllegalArgumentException（服务层不再单独处理）
        List<Long> manyIds = new ArrayList<>();
        for (int i = 0; i < 51; i++) manyIds.add((long) i);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> controller.batchEmbedDocuments(Map.of("ids", manyIds)));

        assertEquals("Batch embedding limited to 50 documents per request (API rate limit)", thrown.getMessage());
    }

    // ==================== 版本历史 ====================

    @Test
    void getVersionHistory_documentNotFound_returns404() {
        when(documentRepository.existsById(999L)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.getVersionHistory(999L, 0, 20);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getVersionHistory_returnsPaginatedVersions() {
        when(documentRepository.existsById(1L)).thenReturn(true);
        com.springairag.core.entity.RagDocumentVersion v1 = new com.springairag.core.entity.RagDocumentVersion();
        v1.setId(1L);
        v1.setDocumentId(1L);
        v1.setVersionNumber(1);
        v1.setContentHash("abc123");
        v1.setChangeType("CREATE");
        v1.setSize(100L);
        Page<com.springairag.core.entity.RagDocumentVersion> page = new PageImpl<>(List.of(v1));
        when(documentVersionService.getVersionHistory(eq(1L), any(PageRequest.class))).thenReturn(page);

        ResponseEntity<Map<String, Object>> response = controller.getVersionHistory(1L, 0, 20);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(1L, body.get("documentId"));
        assertEquals(1L, body.get("totalVersions"));
        List<?> versions = (List<?>) body.get("versions");
        assertEquals(1, versions.size());
    }

    @Test
    void getVersion_found_returnsVersion() {
        com.springairag.core.entity.RagDocumentVersion v1 = new com.springairag.core.entity.RagDocumentVersion();
        v1.setId(1L);
        v1.setDocumentId(1L);
        v1.setVersionNumber(2);
        v1.setContentHash("def456");
        v1.setContentSnapshot("内容快照");
        v1.setChangeType("UPDATE");
        v1.setSize(200L);
        when(documentVersionService.getVersion(1L, 2)).thenReturn(Optional.of(v1));

        ResponseEntity<Map<String, Object>> response = controller.getVersion(1L, 2);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.get("versionNumber"));
        assertEquals("内容快照", body.get("contentSnapshot"));
    }

    @Test
    void getVersion_notFound_returns404() {
        when(documentVersionService.getVersion(1L, 99)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.getVersion(1L, 99);

        assertEquals(404, response.getStatusCode().value());
    }

    // --- reembedMissing tests ---

    @Test
    void reembedMissing_noDocuments_returnsEmptyResults() {
        when(documentRepository.findDocumentsWithoutEmbeddings()).thenReturn(List.of());

        ResponseEntity<ReembedMissingResponse> response = controller.reembedMissing(false);

        assertEquals(200, response.getStatusCode().value());
        ReembedMissingResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(0, body.total());
        assertEquals(0, body.success());
        assertEquals(0, body.failed());
        assertTrue(body.results().isEmpty());
    }

    @Test
    void reembedMissing_allSucceed_returnsCorrectCounts() {
        RagDocument doc1 = createDoc(1L, "Doc One", "content one");
        RagDocument doc2 = createDoc(2L, "Doc Two", "content two");
        when(documentRepository.findDocumentsWithoutEmbeddings()).thenReturn(List.of(doc1, doc2));
        when(documentEmbedService.embedDocument(1L, false))
                .thenReturn(Map.of("status", "COMPLETED", "chunksCreated", 5, "message", "done"));
        when(documentEmbedService.embedDocument(2L, false))
                .thenReturn(Map.of("status", "COMPLETED", "chunksCreated", 3, "message", "done"));

        ResponseEntity<ReembedMissingResponse> response = controller.reembedMissing(false);

        assertEquals(200, response.getStatusCode().value());
        ReembedMissingResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.total());
        assertEquals(2, body.success());
        assertEquals(0, body.failed());
        assertEquals(2, body.results().size());

        ReembedResultResponse r1 = body.results().get(0);
        assertEquals(1L, r1.documentId());
        assertEquals("Doc One", r1.title());
        assertEquals("COMPLETED", r1.status());
        assertEquals(5, r1.chunks());

        ReembedResultResponse r2 = body.results().get(1);
        assertEquals(2L, r2.documentId());
        assertEquals("COMPLETED", r2.status());
        assertEquals(3, r2.chunks());
    }

    @Test
    void reembedMissing_oneSucceedsOneFails_returnsMixedCounts() {
        RagDocument doc1 = createDoc(1L, "Good Doc", "content");
        RagDocument doc2 = createDoc(2L, "Bad Doc", "content");
        when(documentRepository.findDocumentsWithoutEmbeddings()).thenReturn(List.of(doc1, doc2));
        when(documentEmbedService.embedDocument(1L, false))
                .thenReturn(Map.of("status", "COMPLETED", "chunksCreated", 5, "message", "done"));
        when(documentEmbedService.embedDocument(2L, false))
                .thenThrow(new RuntimeException("Embedding service unavailable"));

        ResponseEntity<ReembedMissingResponse> response = controller.reembedMissing(false);

        assertEquals(200, response.getStatusCode().value());
        ReembedMissingResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.total());
        assertEquals(1, body.success());
        assertEquals(1, body.failed());

        ReembedResultResponse failed = body.results().stream()
                .filter(r -> "error".equals(r.status())).findFirst().orElseThrow();
        assertEquals(2L, failed.documentId());
        assertEquals("Bad Doc", failed.title());
        assertEquals("error", failed.status());
        assertEquals(0, failed.chunks());
        assertEquals("Embedding service unavailable", failed.message());
    }

    @Test
    void reembedMissing_forceFlag_passesThroughToService() {
        RagDocument doc = createDoc(1L, "Force Doc", "content");
        when(documentRepository.findDocumentsWithoutEmbeddings()).thenReturn(List.of(doc));
        when(documentEmbedService.embedDocument(1L, true))
                .thenReturn(Map.of("status", "COMPLETED", "chunksCreated", 5, "message", "force re-embed"));

        ResponseEntity<ReembedMissingResponse> response = controller.reembedMissing(true);

        assertEquals(200, response.getStatusCode().value());
        verify(documentEmbedService).embedDocument(1L, true);
        assertEquals("COMPLETED", response.getBody().results().get(0).status());
    }
}
