package com.springairag.core.controller;

import com.springairag.api.dto.DocumentRequest;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private EmbeddingBatchService embeddingBatchService;
    private JdbcTemplate jdbcTemplate;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        embeddingBatchService = mock(EmbeddingBatchService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        controller = new RagDocumentController(documentRepository, embeddingRepository, embeddingBatchService, jdbcTemplate);
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

    @Test
    void createDocument_returnsId() {
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

        ResponseEntity<Map<String, Object>> response = controller.getDocument(999L);

        assertEquals(404, response.getStatusCode().value());
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

        ResponseEntity<Map<String, String>> response = controller.deleteDocument(999L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void listDocuments_returnsPaginated() {
        List<RagDocument> docs = List.of(
                createDoc(1L, "文档1", "内容1"),
                createDoc(2L, "文档2", "内容2")
        );
        Page<RagDocument> page = new PageImpl<>(docs, PageRequest.of(0, 20), 2);
        when(documentRepository.findAll(any(PageRequest.class))).thenReturn(page);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(0, 20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2L, response.getBody().get("total"));
        List<?> resultDocs = (List<?>) response.getBody().get("documents");
        assertEquals(2, resultDocs.size());
    }

    @Test
    void embedDocument_found() {
        RagDocument doc = createDoc(1L, "文档",
                "Spring Boot 是一个用于快速构建 Spring 应用的框架。它提供了自动配置和嵌入式服务器等功能。");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(RagDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(embeddingBatchService.createEmbeddingsBatch(anyList())).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.embedDocument(1L);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void embedDocument_notFound() {
        when(documentRepository.findById(999L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.embedDocument(999L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void embedDocument_emptyContent_returns400() {
        RagDocument doc = createDoc(1L, "空文档", "");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

        ResponseEntity<Map<String, Object>> response = controller.embedDocument(1L);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("文档内容为空", response.getBody().get("error"));
    }
}
