package com.springairag.core.service;

import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.BatchDeleteItem;
import com.springairag.api.dto.BatchDeleteResponse;
import com.springairag.api.dto.DocumentDeleteResponse;
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
    private DocumentEmbedService documentEmbedService;
    private BatchDocumentService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        service = new BatchDocumentService(documentRepository, embeddingRepository, documentEmbedService);
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

    // ==================== batchCreateDocuments (embed=false) ====================

    @Test
    @DisplayName("batchCreateDocuments: 正常创建文档（不嵌入）")
    void batchCreateDocuments_created() {
        DocumentRequest req = createRequest("标题1", "内容1");
        RagDocument savedDoc = createSavedDoc(1L, "标题1", null);
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of());
        when(documentRepository.save(any(RagDocument.class))).thenReturn(savedDoc);

        BatchCreateResponse output = service.batchCreateDocuments(List.of(req));

        assertEquals(1, output.created());
        assertEquals(0, output.skipped());
        assertEquals(0, output.failed());
        assertEquals(1, output.results().size());
        assertEquals(1L, output.results().getFirst().documentId());
        assertTrue(output.results().getFirst().newlyCreated());

        verify(documentRepository).save(any(RagDocument.class));
    }

    @Test
    @DisplayName("batchCreateDocuments: 内容重复检测")
    void batchCreateDocuments_duplicate() {
        String content = "重复内容";
        DocumentRequest req = createRequest("标题", content);
        RagDocument existing = createSavedDoc(99L, "原标题", BatchDocumentService.computeSha256(content));
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of(existing));

        BatchCreateResponse output = service.batchCreateDocuments(List.of(req));

        assertEquals(0, output.created());
        assertEquals(1, output.skipped());
        assertEquals(0, output.failed());
        assertEquals(99L, output.results().getFirst().documentId());
        assertFalse(output.results().getFirst().newlyCreated());

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

        BatchCreateResponse output = service.batchCreateDocuments(List.of(req1, req2));

        assertEquals(1, output.created());
        assertEquals(1, output.skipped());
        assertEquals(0, output.failed());

        BatchCreateResponse.DocumentResult r1 = output.results().get(0);
        assertTrue(r1.newlyCreated());
        assertNull(r1.error());

        BatchCreateResponse.DocumentResult r2 = output.results().get(1);
        assertFalse(r2.newlyCreated());
        assertNull(r2.error());
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

        BatchCreateResponse output = service.batchCreateDocuments(List.of(req1, req2));

        assertEquals(1, output.created());
        assertEquals(0, output.skipped());
        assertEquals(1, output.failed());

        BatchCreateResponse.DocumentResult r1 = output.results().get(0);
        assertNull(r1.documentId());
        assertNotNull(r1.error());

        BatchCreateResponse.DocumentResult r2 = output.results().get(1);
        assertEquals(2L, r2.documentId());
        assertNull(r2.error());
    }

    // ==================== batchCreateDocuments (embed=true) ====================

    @Test
    @DisplayName("batchCreateDocuments: embed=true 时创建后自动嵌入")
    void batchCreateDocuments_withEmbed_success() {
        DocumentRequest req = createRequest("标题", "内容");
        RagDocument savedDoc = createSavedDoc(1L, "标题", null);
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of());
        when(documentRepository.save(any(RagDocument.class))).thenReturn(savedDoc);
        when(documentEmbedService.embedDocument(1L, false))
                .thenReturn(Map.of("status", "COMPLETED", "chunksCreated", 3));

        BatchCreateResponse output = service.batchCreateDocuments(List.of(req), true, null, false);

        assertEquals(1, output.created());
        assertEquals(0, output.failed());
        assertEquals(1L, output.results().getFirst().documentId());
        verify(documentEmbedService).embedDocument(1L, false);
    }

    @Test
    @DisplayName("batchCreateDocuments: embed=true 时已存在文档跳过嵌入")
    void batchCreateDocuments_withEmbed_existingSkipped() {
        String content = "已有内容";
        DocumentRequest req = createRequest("标题", content);
        RagDocument existing = createSavedDoc(99L, "标题", BatchDocumentService.computeSha256(content));
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of(existing));

        // embed=true 但文档已存在（newlyCreated=false）→ 跳过嵌入
        BatchCreateResponse output = service.batchCreateDocuments(List.of(req), true, null, false);

        assertEquals(0, output.created());
        assertEquals(1, output.skipped());
        verify(documentEmbedService, never()).embedDocument(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("batchCreateDocuments: embed=true + force=true 时强制重嵌入")
    void batchCreateDocuments_withEmbedAndForce() {
        String content = "已有内容";
        DocumentRequest req = createRequest("标题", content);
        RagDocument existing = createSavedDoc(99L, "标题", BatchDocumentService.computeSha256(content));
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of(existing));
        when(documentEmbedService.embedDocument(99L, true))
                .thenReturn(Map.of("status", "COMPLETED", "chunksCreated", 2));

        BatchCreateResponse output = service.batchCreateDocuments(List.of(req), true, null, true);

        // newlyCreated=false 但 force=true → embed 仍执行，结果算 skipped（已存在但被处理）
        assertEquals(0, output.created());
        assertEquals(1, output.skipped());
        assertEquals(0, output.failed());
        verify(documentEmbedService).embedDocument(99L, true);
    }

    @Test
    @DisplayName("batchCreateDocuments: embed=true 时嵌入失败算 failed")
    void batchCreateDocuments_withEmbed_embedFails() {
        DocumentRequest req = createRequest("标题", "内容");
        RagDocument savedDoc = createSavedDoc(1L, "标题", null);
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of());
        when(documentRepository.save(any(RagDocument.class))).thenReturn(savedDoc);
        when(documentEmbedService.embedDocument(1L, false))
                .thenReturn(Map.of("status", "FAILED", "error", "API timeout"));

        BatchCreateResponse output = service.batchCreateDocuments(List.of(req), true, null, false);

        assertEquals(0, output.created());
        assertEquals(0, output.skipped());
        assertEquals(1, output.failed());
        assertNotNull(output.results().getFirst().error());
        assertTrue(output.results().getFirst().error().contains("Embedding failed"));
    }

    // ==================== deleteDocument ====================

    @Test
    @DisplayName("deleteDocument: 正常删除")
    void deleteDocument_success() {
        when(documentRepository.existsById(1L)).thenReturn(true);
        when(embeddingRepository.countByDocumentId(1L)).thenReturn(5L);

        DocumentDeleteResponse result = service.deleteDocument(1L);

        assertEquals("Document deleted", result.message());
        assertEquals(1L, result.id());
        assertEquals(5L, result.embeddingsRemoved());
        verify(embeddingRepository).deleteByDocumentId(1L);
        verify(documentRepository).deleteById(1L);
    }

    @Test
    @DisplayName("deleteDocument: 文档不存在抛异常")
    void deleteDocument_notFound() {
        when(documentRepository.existsById(99L)).thenReturn(false);

        assertThrows(com.springairag.core.exception.DocumentNotFoundException.class,
                () -> service.deleteDocument(99L));
        verify(documentRepository, never()).deleteById(any());
    }

    // ==================== batchDeleteDocuments ====================

    @Test
    @DisplayName("batchDeleteDocuments: 正常删除")
    void batchDeleteDocuments_success() {
        when(documentRepository.existsById(1L)).thenReturn(true);
        when(documentRepository.existsById(2L)).thenReturn(true);

        BatchDeleteResponse output = service.batchDeleteDocuments(List.of(1L, 2L));

        assertEquals(2, output.summary().total());
        assertEquals(2, output.summary().deleted());
        assertEquals(0, output.summary().notFound());

        verify(embeddingRepository).deleteByDocumentIdIn(List.of(1L, 2L));
        verify(documentRepository).deleteById(1L);
        verify(documentRepository).deleteById(2L);
    }

    @Test
    @DisplayName("batchDeleteDocuments: 文档不存在算 notFound")
    void batchDeleteDocuments_notFound() {
        when(documentRepository.existsById(99L)).thenReturn(false);

        BatchDeleteResponse output = service.batchDeleteDocuments(List.of(99L));

        assertEquals(1, output.summary().notFound());
        assertEquals(0, output.summary().deleted());

        verify(documentRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("batchDeleteDocuments: 混合删除结果")
    void batchDeleteDocuments_mixed() {
        when(documentRepository.existsById(1L)).thenReturn(true);
        when(documentRepository.existsById(2L)).thenReturn(false);

        BatchDeleteResponse output = service.batchDeleteDocuments(List.of(1L, 2L));

        assertEquals(1, output.summary().deleted());
        assertEquals(1, output.summary().notFound());

        BatchDeleteItem r1 = output.results().get(0);
        assertEquals(1L, r1.id());
        assertEquals("DELETED", r1.status());

        BatchDeleteItem r2 = output.results().get(1);
        assertEquals(2L, r2.id());
        assertEquals("NOT_FOUND", r2.status());
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
