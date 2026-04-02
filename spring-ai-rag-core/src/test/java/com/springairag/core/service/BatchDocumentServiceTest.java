package com.springairag.core.service;

import com.springairag.api.dto.DocumentRequest;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BatchDocumentService 单元测试
 */
class BatchDocumentServiceTest {

    private RagDocumentRepository documentRepository;
    private RagEmbeddingRepository embeddingRepository;
    private BatchDocumentService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        service = new BatchDocumentService(documentRepository, embeddingRepository);
    }

    private DocumentRequest createRequest(String title, String content) {
        DocumentRequest req = new DocumentRequest();
        req.setTitle(title);
        req.setContent(content);
        req.setSource("test");
        return req;
    }

    private RagDocument createSavedDoc(Long id, String title, String contentHash) {
        RagDocument doc = new RagDocument();
        doc.setId(id);
        doc.setTitle(title);
        doc.setContentHash(contentHash);
        return doc;
    }

    // ==================== batchCreateDocuments ====================

    @Test
    @DisplayName("batchCreateDocuments: 正常创建文档")
    void batchCreateDocuments_created() {
        DocumentRequest req = createRequest("标题1", "内容1");
        RagDocument savedDoc = createSavedDoc(1L, "标题1", null);
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of());
        when(documentRepository.save(any(RagDocument.class))).thenReturn(savedDoc);

        Map<String, Object> output = service.batchCreateDocuments(List.of(req));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) output.get("summary");
        assertEquals(1, summary.get("total"));
        assertEquals(1, summary.get("created"));
        assertEquals(0, summary.get("duplicated"));
        assertEquals(0, summary.get("failed"));

        verify(documentRepository).save(any(RagDocument.class));
    }

    @Test
    @DisplayName("batchCreateDocuments: 内容重复检测")
    void batchCreateDocuments_duplicate() {
        String content = "重复内容";
        DocumentRequest req = createRequest("标题", content);
        RagDocument existing = createSavedDoc(99L, "原标题", BatchDocumentService.computeSha256(content));
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of(existing));

        Map<String, Object> output = service.batchCreateDocuments(List.of(req));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) output.get("summary");
        assertEquals(1, summary.get("duplicated"));
        assertEquals(0, summary.get("created"));

        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("batchCreateDocuments: 多个文档混合结果")
    void batchCreateDocuments_mixed() {
        DocumentRequest req1 = createRequest("新文档", "新内容");
        DocumentRequest req2 = createRequest("重复文档", "已有内容");

        RagDocument saved = createSavedDoc(1L, "新文档", null);
        RagDocument existing = createSavedDoc(99L, "重复文档", "hash");

        when(documentRepository.findByContentHash(BatchDocumentService.computeSha256("新内容")))
                .thenReturn(List.of());
        when(documentRepository.findByContentHash(BatchDocumentService.computeSha256("已有内容")))
                .thenReturn(List.of(existing));
        when(documentRepository.save(any(RagDocument.class))).thenReturn(saved);

        Map<String, Object> output = service.batchCreateDocuments(List.of(req1, req2));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) output.get("summary");
        assertEquals(2, summary.get("total"));
        assertEquals(1, summary.get("created"));
        assertEquals(1, summary.get("duplicated"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
        assertEquals("CREATED", results.get(0).get("status"));
        assertEquals("DUPLICATE", results.get(1).get("status"));
    }

    @Test
    @DisplayName("batchCreateDocuments: 异常算 failed 继续处理")
    void batchCreateDocuments_exceptionContinues() {
        DocumentRequest req1 = createRequest("bad", "内容1");
        DocumentRequest req2 = createRequest("good", "内容2");

        RagDocument saved = createSavedDoc(2L, "good", null);
        when(documentRepository.findByContentHash(anyString()))
                .thenThrow(new RuntimeException("DB error"))
                .thenReturn(List.of());
        when(documentRepository.save(any(RagDocument.class))).thenReturn(saved);

        Map<String, Object> output = service.batchCreateDocuments(List.of(req1, req2));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) output.get("summary");
        assertEquals(1, summary.get("failed"));
        assertEquals(1, summary.get("created"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
        assertEquals("FAILED", results.get(0).get("status"));
        assertEquals("CREATED", results.get(1).get("status"));
    }

    // ==================== batchDeleteDocuments ====================

    @Test
    @DisplayName("batchDeleteDocuments: 正常删除")
    void batchDeleteDocuments_success() {
        when(documentRepository.existsById(1L)).thenReturn(true);
        when(documentRepository.existsById(2L)).thenReturn(true);

        Map<String, Object> output = service.batchDeleteDocuments(List.of(1L, 2L));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) output.get("summary");
        assertEquals(2, summary.get("total"));
        assertEquals(2, summary.get("deleted"));
        assertEquals(0, summary.get("notFound"));

        verify(embeddingRepository).deleteByDocumentIdIn(List.of(1L, 2L));
        verify(documentRepository).deleteById(1L);
        verify(documentRepository).deleteById(2L);
    }

    @Test
    @DisplayName("batchDeleteDocuments: 文档不存在算 notFound")
    void batchDeleteDocuments_notFound() {
        when(documentRepository.existsById(99L)).thenReturn(false);

        Map<String, Object> output = service.batchDeleteDocuments(List.of(99L));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) output.get("summary");
        assertEquals(1, summary.get("notFound"));
        assertEquals(0, summary.get("deleted"));

        verify(documentRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("batchDeleteDocuments: 混合删除结果")
    void batchDeleteDocuments_mixed() {
        when(documentRepository.existsById(1L)).thenReturn(true);
        when(documentRepository.existsById(2L)).thenReturn(false);

        Map<String, Object> output = service.batchDeleteDocuments(List.of(1L, 2L));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) output.get("summary");
        assertEquals(1, summary.get("deleted"));
        assertEquals(1, summary.get("notFound"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
        assertEquals("DELETED", results.get(0).get("status"));
        assertEquals("NOT_FOUND", results.get(1).get("status"));
    }

    @Test
    @DisplayName("batchDeleteDocuments: 超过 100 条限制抛异常")
    void batchDeleteDocuments_exceedsLimit_throws() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();
        assertThrows(IllegalArgumentException.class, () -> service.batchDeleteDocuments(ids));
    }

    // ==================== computeSha256 ====================

    @Test
    @DisplayName("computeSha256: 相同内容产生相同哈希")
    void computeSha256_sameContent_sameHash() {
        String h1 = BatchDocumentService.computeSha256("hello");
        String h2 = BatchDocumentService.computeSha256("hello");
        assertEquals(h1, h2);
    }

    @Test
    @DisplayName("computeSha256: 不同内容产生不同哈希")
    void computeSha256_differentContent_differentHash() {
        String h1 = BatchDocumentService.computeSha256("hello");
        String h2 = BatchDocumentService.computeSha256("world");
        assertNotEquals(h1, h2);
    }

    @Test
    @DisplayName("computeSha256: 返回 64 字符十六进制字符串")
    void computeSha256_returnsHexString() {
        String hash = BatchDocumentService.computeSha256("test");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"));
    }
}
