package com.springairag.core.controller;

import com.springairag.api.dto.DocumentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.springairag.core.retrieval.EmbeddingBatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagDocumentController 单元测试
 */
class RagDocumentControllerTest {

    private JdbcTemplate jdbcTemplate;
    private EmbeddingBatchService embeddingBatchService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        embeddingBatchService = mock(EmbeddingBatchService.class);
        controller = new RagDocumentController(jdbcTemplate, embeddingBatchService);
    }

    @Test
    void createDocument_returnsId() {
        doReturn(42L).when(jdbcTemplate).queryForObject(anyString(), eq(Long.class), any(Object[].class));

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
        Map<String, Object> docRow = new HashMap<>();
        docRow.put("id", 1L);
        docRow.put("title", "文档标题");
        docRow.put("content", "内容");
        docRow.put("processing_status", "COMPLETED");

        when(jdbcTemplate.queryForList(anyString(), eq(1L)))
                .thenReturn(List.of(docRow));
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM rag_embeddings WHERE document_id = ?"),
                eq(Integer.class), eq(1L)))
                .thenReturn(5);

        ResponseEntity<Map<String, Object>> response = controller.getDocument(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(5, response.getBody().get("embeddingCount"));
    }

    @Test
    void getDocument_notFound() {
        when(jdbcTemplate.queryForList(anyString(), eq(999L)))
                .thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.getDocument(999L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void deleteDocument_found() {
        when(jdbcTemplate.update(eq("DELETE FROM rag_embeddings WHERE document_id = ?"), eq(1L)))
                .thenReturn(3);
        when(jdbcTemplate.update(eq("DELETE FROM rag_documents WHERE id = ?"), eq(1L)))
                .thenReturn(1);

        ResponseEntity<Map<String, String>> response = controller.deleteDocument(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("文档已删除", response.getBody().get("message"));
        assertEquals("3", response.getBody().get("embeddingsRemoved"));
    }

    @Test
    void deleteDocument_notFound() {
        // Use doReturn to avoid ambiguous overload between update(String, PSS) and update(String, Object...)
        doReturn(0).when(jdbcTemplate).update(anyString(), any(Object[].class));

        ResponseEntity<Map<String, String>> response = controller.deleteDocument(999L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void listDocuments_returnsPaginated() {
        List<Map<String, Object>> docs = List.of(
                Map.of("id", 1L, "title", "文档1"),
                Map.of("id", 2L, "title", "文档2")
        );

        when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0)))
                .thenReturn(docs);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM rag_documents"), eq(Integer.class)))
                .thenReturn(2);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(0, 20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().get("total"));
        List<?> resultDocs = (List<?>) response.getBody().get("documents");
        assertEquals(2, resultDocs.size());
    }

    @Test
    void embedDocument_found() {
        // Mock document content query
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", 1L);
        doc.put("content", "Spring Boot 是一个用于快速构建 Spring 应用的框架。它提供了自动配置和嵌入式服务器等功能。");
        when(jdbcTemplate.queryForList(
                eq("SELECT id, content FROM rag_documents WHERE id = ?"), eq(1L)))
                .thenReturn(List.of(doc));

        // Mock embedding batch service
        when(embeddingBatchService.createEmbeddingsBatch(anyList()))
                .thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.embedDocument(1L);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void embedDocument_notFound() {
        when(jdbcTemplate.queryForList(
                eq("SELECT id, content FROM rag_documents WHERE id = ?"), eq(999L)))
                .thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.embedDocument(999L);

        assertEquals(404, response.getStatusCode().value());
    }
}
