package com.springairag.core.controller;

import com.springairag.api.dto.DocumentRequest;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.DocumentEmbedService;
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
    private DocumentEmbedService documentEmbedService;
    private BatchDocumentService batchDocumentService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        batchDocumentService = mock(BatchDocumentService.class);
        controller = new RagDocumentController(documentRepository, embeddingRepository, documentEmbedService, batchDocumentService);
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
        assertEquals(5L, response.getBody().get("embeddingCount"));
        assertEquals("文档标题", response.getBody().get("title"));
    }

    @Test
    void getDocument_notFound() {
        when(documentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class, () -> controller.getDocument(999L));
    }

    @Test
    void deleteDocument_found() {
        RagDocument doc = createDoc(1L, "文档", "内容");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(embeddingRepository.countByDocumentId(1L)).thenReturn(3L);

        ResponseEntity<Map<String, String>> response = controller.deleteDocument(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("文档已删除", response.getBody().get("message"));
        assertEquals("3", response.getBody().get("embeddingsRemoved"));
        verify(embeddingRepository).deleteByDocumentId(1L);
        verify(documentRepository).deleteById(1L);
    }

    @Test
    void deleteDocument_notFound() {
        when(documentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class, () -> controller.deleteDocument(999L));
    }

    @Test
    void listDocuments_returnsPaginated() {
        List<RagDocument> docs = List.of(
                createDoc(1L, "文档1", "内容1"),
                createDoc(2L, "文档2", "内容2")
        );
        Page<RagDocument> page = new PageImpl<>(docs, PageRequest.of(0, 20), 2);
        when(documentRepository.searchDocuments(isNull(), isNull(), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(page);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(0, 20, null, null, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2L, response.getBody().get("total"));
        List<?> resultDocs = (List<?>) response.getBody().get("documents");
        assertEquals(2, resultDocs.size());
    }

    @Test
    void listDocuments_filterByTitle() {
        List<RagDocument> docs = List.of(createDoc(1L, "Spring AI 入门", "内容"));
        Page<RagDocument> page = new PageImpl<>(docs, PageRequest.of(0, 20), 1);
        when(documentRepository.searchDocuments(eq("Spring"), isNull(), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(page);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(0, 20, "Spring", null, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, response.getBody().get("total"));
        List<?> resultDocs = (List<?>) response.getBody().get("documents");
        assertEquals(1, resultDocs.size());
    }

    @Test
    void listDocuments_filterByDocumentType() {
        List<RagDocument> docs = List.of(createDoc(1L, "文档", "内容", "markdown", "COMPLETED"));
        Page<RagDocument> page = new PageImpl<>(docs, PageRequest.of(0, 20), 1);
        when(documentRepository.searchDocuments(isNull(), eq("markdown"), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(page);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(0, 20, null, "markdown", null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, response.getBody().get("total"));
    }

    @Test
    void listDocuments_filterByStatus() {
        List<RagDocument> docs = List.of(createDoc(1L, "文档", "内容", "txt", "PENDING"));
        Page<RagDocument> page = new PageImpl<>(docs, PageRequest.of(0, 20), 1);
        when(documentRepository.searchDocuments(isNull(), isNull(), eq("PENDING"), isNull(), any(PageRequest.class)))
                .thenReturn(page);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(0, 20, null, null, "PENDING", null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, response.getBody().get("total"));
    }

    @Test
    void listDocuments_combinedFilters() {
        List<RagDocument> docs = List.of(createDoc(1L, "Spring Boot 教程", "内容", "markdown", "COMPLETED"));
        Page<RagDocument> page = new PageImpl<>(docs, PageRequest.of(0, 20), 1);
        when(documentRepository.searchDocuments(eq("Spring"), eq("markdown"), eq("COMPLETED"), isNull(), any(PageRequest.class)))
                .thenReturn(page);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(0, 20, "Spring", "markdown", "COMPLETED", null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, response.getBody().get("total"));
    }

    @Test
    void listDocuments_emptyResult() {
        Page<RagDocument> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(documentRepository.searchDocuments(eq("不存在的标题"), isNull(), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(page);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(0, 20, "不存在的标题", null, null, null);

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

        ResponseEntity<Map<String, Object>> response = controller.embedDocument(1L, false);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("COMPLETED", response.getBody().get("status"));
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
                .thenThrow(new IllegalArgumentException("文档内容为空: documentId=1"));

        ResponseEntity<Map<String, Object>> response = controller.embedDocument(1L, false);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("文档内容为空: documentId=1", response.getBody().get("error"));
    }

    @Test
    void embedDocumentViaVectorStore_success() {
        when(documentEmbedService.embedDocumentViaVectorStore(1L, false)).thenReturn(Map.of(
                "message", "嵌入向量生成完成（VectorStore 路径）",
                "documentId", 1L,
                "chunksCreated", 5,
                "embeddingsStored", 5,
                "storageTable", "rag_vector_store",
                "status", "COMPLETED"
        ));

        ResponseEntity<Map<String, Object>> response = controller.embedDocumentViaVectorStore(1L, false);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(5, response.getBody().get("chunksCreated"));
        assertEquals("rag_vector_store", response.getBody().get("storageTable"));
        assertEquals("COMPLETED", response.getBody().get("status"));
    }

    @Test
    void embedDocumentViaVectorStore_noVectorStore_returns400() {
        when(documentEmbedService.embedDocumentViaVectorStore(1L, false))
                .thenThrow(new IllegalStateException("VectorStore 未配置，请使用 embedDocument 方法"));

        ResponseEntity<Map<String, Object>> response = controller.embedDocumentViaVectorStore(1L, false);

        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().get("error").toString().contains("VectorStore 未配置"));
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
                .thenThrow(new IllegalArgumentException("文档内容为空"));

        ResponseEntity<Map<String, Object>> response = controller.embedDocumentViaVectorStore(1L, false);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("文档内容为空", response.getBody().get("error"));
    }

    // ==================== 批量操作测试 ====================

    @Test
    void batchCreateDocuments_allNew() {
        var docs = List.of(
                new DocumentRequest("文档1", "内容1"),
                new DocumentRequest("文档2", "内容2")
        );
        when(batchDocumentService.batchCreateDocuments(docs)).thenReturn(Map.of(
                "results", List.of(
                        Map.of("index", 0, "title", "文档1", "status", "CREATED", "id", 1L),
                        Map.of("index", 1, "title", "文档2", "status", "CREATED", "id", 2L)
                ),
                "summary", Map.of("total", 2, "created", 2, "duplicated", 0, "failed", 0)
        ));

        var req = new com.springairag.api.dto.BatchDocumentRequest(docs);
        ResponseEntity<Map<String, Object>> response = controller.batchCreateDocuments(req);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> summary = (Map<?, ?>) response.getBody().get("summary");
        assertEquals(2, summary.get("total"));
        assertEquals(2, summary.get("created"));
    }

    @Test
    void batchCreateDocuments_withDuplicates() {
        var docs = List.of(
                new DocumentRequest("新文档", "新内容"),
                new DocumentRequest("重复标题", "重复内容")
        );
        when(batchDocumentService.batchCreateDocuments(docs)).thenReturn(Map.of(
                "results", List.of(
                        Map.of("index", 0, "title", "新文档", "status", "CREATED", "id", 20L),
                        Map.of("index", 1, "title", "重复标题", "status", "DUPLICATE", "id", 10L)
                ),
                "summary", Map.of("total", 2, "created", 1, "duplicated", 1, "failed", 0)
        ));

        var req = new com.springairag.api.dto.BatchDocumentRequest(docs);
        ResponseEntity<Map<String, Object>> response = controller.batchCreateDocuments(req);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> summary = (Map<?, ?>) response.getBody().get("summary");
        assertEquals(1, summary.get("created"));
        assertEquals(1, summary.get("duplicated"));
    }

    @Test
    void batchCreateDocuments_withException() {
        var docs = List.of(new DocumentRequest("文档", "内容"));
        when(batchDocumentService.batchCreateDocuments(docs)).thenReturn(Map.of(
                "results", List.of(Map.of("index", 0, "title", "文档", "status", "FAILED", "error", "DB error")),
                "summary", Map.of("total", 1, "created", 0, "duplicated", 0, "failed", 1)
        ));

        var req = new com.springairag.api.dto.BatchDocumentRequest(docs);
        ResponseEntity<Map<String, Object>> response = controller.batchCreateDocuments(req);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> summary = (Map<?, ?>) response.getBody().get("summary");
        assertEquals(1, summary.get("failed"));
    }

    @Test
    void batchDeleteDocuments_success() {
        when(batchDocumentService.batchDeleteDocuments(List.of(1L, 2L))).thenReturn(Map.of(
                "results", List.of(
                        Map.of("id", 1L, "status", "DELETED"),
                        Map.of("id", 2L, "status", "DELETED")
                ),
                "summary", Map.of("total", 2, "deleted", 2, "notFound", 0)
        ));

        ResponseEntity<Map<String, Object>> response = controller.batchDeleteDocuments(
                Map.of("ids", List.of(1L, 2L)));

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> summary = (Map<?, ?>) response.getBody().get("summary");
        assertEquals(2, summary.get("deleted"));
    }

    @Test
    void batchDeleteDocuments_someNotFound() {
        when(batchDocumentService.batchDeleteDocuments(List.of(1L, 999L))).thenReturn(Map.of(
                "results", List.of(
                        Map.of("id", 1L, "status", "DELETED"),
                        Map.of("id", 999L, "status", "NOT_FOUND")
                ),
                "summary", Map.of("total", 2, "deleted", 1, "notFound", 1)
        ));

        ResponseEntity<Map<String, Object>> response = controller.batchDeleteDocuments(
                Map.of("ids", List.of(1L, 999L)));

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> summary = (Map<?, ?>) response.getBody().get("summary");
        assertEquals(1, summary.get("deleted"));
        assertEquals(1, summary.get("notFound"));
    }

    @Test
    void batchDeleteDocuments_emptyIds_returns400() {
        ResponseEntity<Map<String, Object>> response = controller.batchDeleteDocuments(
                Map.of("ids", List.of()));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void batchDeleteDocuments_tooManyIds_returns400() {
        when(batchDocumentService.batchDeleteDocuments(anyList()))
                .thenThrow(new IllegalArgumentException("单次批量删除不超过 100 条"));

        List<Long> manyIds = new ArrayList<>();
        for (int i = 0; i < 101; i++) manyIds.add((long) i);

        ResponseEntity<Map<String, Object>> response = controller.batchDeleteDocuments(
                Map.of("ids", manyIds));

        assertEquals(400, response.getStatusCode().value());
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
        ResponseEntity<Map<String, Object>> response = controller.batchEmbedDocuments(
                Map.of("ids", List.of()));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void batchEmbedDocuments_tooManyIds_returns400() {
        when(documentEmbedService.batchEmbedDocuments(anyList()))
                .thenThrow(new IllegalArgumentException("单次批量嵌入不超过 50 条（避免 API 限流）"));

        List<Long> manyIds = new ArrayList<>();
        for (int i = 0; i < 51; i++) manyIds.add((long) i);

        ResponseEntity<Map<String, Object>> response = controller.batchEmbedDocuments(
                Map.of("ids", manyIds));

        assertEquals(400, response.getStatusCode().value());
    }
}
