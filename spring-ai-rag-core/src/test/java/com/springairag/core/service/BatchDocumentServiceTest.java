package com.springairag.core.service;

import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.BatchDeleteItem;
import com.springairag.api.dto.BatchDeleteResponse;
import com.springairag.api.dto.DocumentDeleteResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.EmbeddingPolicy;
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
 * BatchDocumentService unit tests
 */
class BatchDocumentServiceTest {

    private RagDocumentRepository documentRepository;
    private RagEmbeddingRepository embeddingRepository;
    private DocumentMutationService mutationService;
    private BatchDocumentService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        mutationService = mock(DocumentMutationService.class);
        // Batch 832：单条创建只有 createLocal 一条通道（legacy 内联落库已删）。
        service = new BatchDocumentService(documentRepository, embeddingRepository,
        mutationService);
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
    @DisplayName("batchCreateDocuments: creates document without embedding")
    void batchCreateDocuments_created() {
        DocumentRequest req = createRequest("标题1", "内容1");
        BatchDocumentMutationFixture.stubCreates(mutationService);

        BatchCreateResponse output = service.batchCreateDocuments(List.of(req));

        assertEquals(1, output.created());
        assertEquals(0, output.skipped());
        assertEquals(0, output.failed());
        assertEquals(1, output.results().size());
        assertEquals(1L, output.results().getFirst().documentId());
        assertTrue(output.results().getFirst().newlyCreated());

        // 落库由协作者做：服务交出请求，自己不再 save。
        verify(mutationService).createLocal(
                any(), isNull(), any(), anyBoolean(), eq("BATCH_CREATE"),
                isNull(), any(), any(), any());
        verify(documentRepository, never()).save(any(RagDocument.class));
    }

    @Test
    @DisplayName("batchCreateDocuments: detects duplicate content hash")
    void batchCreateDocuments_duplicate() {
        DocumentRequest req = createRequest("标题", "重复内容");
        // 去重由协作者判定：它回报 UNCHANGED，服务据此记 skipped。
        BatchDocumentMutationFixture.stubAction(
                mutationService, Map.of("标题", "UNCHANGED"));

        BatchCreateResponse output = service.batchCreateDocuments(List.of(req));

        assertEquals(0, output.created());
        assertEquals(1, output.skipped());
        assertEquals(0, output.failed());
        assertEquals(1L, output.results().getFirst().documentId());
        assertFalse(output.results().getFirst().newlyCreated());
    }

    @Test
    @DisplayName("batchCreateDocuments: mixed results with multiple documents")
    void batchCreateDocuments_mixed() {
        DocumentRequest req1 = createRequest("新文档", "新内容");
        DocumentRequest req2 = createRequest("重复文档", "已有内容");

        BatchDocumentMutationFixture.stubAction(
                mutationService, Map.of("重复文档", "UNCHANGED"));

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
    @DisplayName("batchCreateDocuments: exceptions counted as failed, processing continues")
    void batchCreateDocuments_exceptionContinues() {
        DocumentRequest req1 = createRequest("bad", "内容1");
        DocumentRequest req2 = createRequest("good", "内容2");

        BatchDocumentMutationFixture.stubFailureFor(
                mutationService, "bad", "DB error");

        BatchCreateResponse output = service.batchCreateDocuments(List.of(req1, req2));

        assertEquals(1, output.created());
        assertEquals(0, output.skipped());
        assertEquals(1, output.failed());

        BatchCreateResponse.DocumentResult r1 = output.results().get(0);
        assertNull(r1.documentId());
        assertNotNull(r1.error());

        BatchCreateResponse.DocumentResult r2 = output.results().get(1);
        assertEquals(1L, r2.documentId());
        assertNull(r2.error());
    }

    // ==================== batchCreateDocuments (embed=true) ====================

    @Test
    @DisplayName("batchCreateDocuments: auto-embeds after creation when embed=true")
    void batchCreateDocuments_withEmbed_success() {
        DocumentRequest req = createRequest("标题", "内容");
        BatchDocumentMutationFixture.stubCreates(mutationService);

        BatchCreateResponse output = service.batchCreateDocuments(List.of(req), true, null, false);

        assertEquals(1, output.created());
        assertEquals(0, output.failed());
        assertEquals(1L, output.results().getFirst().documentId());
        // 嵌入由协作者按 SYNC 策略执行，动作从它的响应里回传。
        assertEquals("SYNC_COMPLETED",
                output.results().getFirst().embeddingAction());
        verify(mutationService).createLocal(
                any(), isNull(), eq(EmbeddingPolicy.SYNC), anyBoolean(),
                eq("BATCH_CREATE"), isNull(), any(), any(), any());
    }

    @Test
    @DisplayName("batchCreateDocuments: skips embedding when document already exists with embed=true")
    void batchCreateDocuments_withEmbed_existingSkipped() {
        DocumentRequest req = createRequest("标题", "已有内容");
        BatchDocumentMutationFixture.stubAction(
                mutationService, Map.of("标题", "UNCHANGED"));

        // embed=true 但文档已存在（协作者回报 UNCHANGED）→ 记 skipped
        BatchCreateResponse output = service.batchCreateDocuments(List.of(req), true, null, false);

        assertEquals(0, output.created());
        assertEquals(1, output.skipped());
    }

    @Test
    @DisplayName("batchCreateDocuments: forces re-embedding when embed=true and force=true")
    void batchCreateDocuments_withEmbedAndForce() {
        DocumentRequest req = createRequest("标题", "已有内容");
        BatchDocumentMutationFixture.stubAction(
                mutationService, Map.of("标题", "UNCHANGED"));

        BatchCreateResponse output = service.batchCreateDocuments(List.of(req), true, null, true);

        // 已存在但 force=true → 仍算 skipped（已存在，只是被重新处理）
        assertEquals(0, output.created());
        assertEquals(1, output.skipped());
        assertEquals(0, output.failed());
        // force 是交给协作者的第 4 个独立参数。
        verify(mutationService).createLocal(
                any(), isNull(), eq(EmbeddingPolicy.SYNC), eq(true),
                eq("BATCH_CREATE"), isNull(), any(), any(), any());
    }

    /**
     * Batch 832：嵌入由 {@code DocumentMutationService} 按策略执行，失败时
     * 它**抛异常**而不是回一个 FAILED 状态——旧版本里嵌入是本服务自己调的，
     * 才有"读状态字符串、拼 'Embedding failed'"那段代码。异常由
     * {@code createSingleDocumentSafely} 兜住，记成失败项而不是中止整批。
     */
    @Test
    @DisplayName("batchCreateDocuments: counts embedding failure as failed when embed=true")
    void batchCreateDocuments_withEmbed_embedFails() {
        DocumentRequest req = createRequest("标题", "内容");
        BatchDocumentMutationFixture.stubFailureFor(
                mutationService, "标题", "Embedding failed: API timeout");

        BatchCreateResponse output = service.batchCreateDocuments(List.of(req), true, null, false);

        assertEquals(0, output.created());
        assertEquals(0, output.skipped());
        assertEquals(1, output.failed());
        assertNotNull(output.results().getFirst().error());
        assertTrue(output.results().getFirst().error().contains("Embedding failed"));
    }

    // ==================== deleteDocument ====================

    @Test
    @DisplayName("deleteDocument: deletes document successfully")
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
    @DisplayName("deleteDocument: throws when document not found")
    void deleteDocument_notFound() {
        when(documentRepository.existsById(99L)).thenReturn(false);

        assertThrows(com.springairag.core.exception.DocumentNotFoundException.class,
                () -> service.deleteDocument(99L));
        verify(documentRepository, never()).deleteById(any());
    }

    // ==================== batchDeleteDocuments ====================

    /**
     * Batch 832：删除改由 {@code DocumentMutationService.hardDeleteLocal} 执行。
     *
     * <p>旧版本这里有一条"协作者缺席就直接 {@code deleteByDocumentId} +
     * {@code deleteById}"的回落——它<b>不校验文档 revision</b>，等于绕过了
     * 乐观锁。该分支在运行的应用里走不到，已删除。
     *
     * <p>顺带把乐观锁版本钉住：一条文档带 revision=7，删它时必须把这个版本
     * 交给协作者，由协作者做并发检查。
     */
    @Test
    @DisplayName("batchDeleteDocuments: deletes multiple documents through the mutation service")
    void batchDeleteDocuments_success() {
        RagDocument first = createSavedDoc(1L, "first", "hash-1");
        first.setDocumentRevision(7L);
        RagDocument second = createSavedDoc(2L, "second", "hash-2");
        when(documentRepository.findAllById(List.of(1L, 2L)))
                .thenReturn(List.of(first, second));
        when(documentRepository.findById(1L)).thenReturn(java.util.Optional.of(first));
        when(documentRepository.findById(2L)).thenReturn(java.util.Optional.of(second));

        BatchDeleteResponse output = service.batchDeleteDocuments(List.of(1L, 2L));

        assertEquals(2, output.summary().total());
        assertEquals(2, output.summary().deleted());
        assertEquals(0, output.summary().notFound());

        // 带 revision 的按 7 传；未带 revision 的缺省为 1。
        verify(mutationService).hardDeleteLocal(eq(1L), eq(7L));
        verify(mutationService).hardDeleteLocal(eq(2L), eq(1L));
        // 服务不再自己删向量、不再自己 deleteById。
        verify(embeddingRepository, never()).deleteByDocumentId(anyLong());
        verify(documentRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("batchDeleteDocuments: non-existent IDs counted as notFound")
    void batchDeleteDocuments_notFound() {
        when(documentRepository.findAllById(List.of(99L))).thenReturn(List.of());
        when(documentRepository.findById(99L)).thenReturn(java.util.Optional.empty());

        BatchDeleteResponse output = service.batchDeleteDocuments(List.of(99L));

        assertEquals(1, output.summary().notFound());
        assertEquals(0, output.summary().deleted());

        verify(documentRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("batchDeleteDocuments: mixed results with some deleted and some not found")
    void batchDeleteDocuments_mixed() {
        RagDocument first = createSavedDoc(1L, "first", "hash-1");
        when(documentRepository.findAllById(List.of(1L, 2L)))
                .thenReturn(List.of(first));
        when(documentRepository.findById(1L)).thenReturn(java.util.Optional.of(first));
        when(documentRepository.findById(2L)).thenReturn(java.util.Optional.empty());

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
    @DisplayName("batchDeleteDocuments: throws when exceeding 100-item limit")
    void batchDeleteDocuments_exceedsLimit_throws() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();
        assertThrows(IllegalArgumentException.class, () -> service.batchDeleteDocuments(ids));
    }

    // ==================== Null Safety ====================

    @Test
    @DisplayName("batchCreateDocuments: throws IllegalArgumentException when requests is null")
    void batchCreateDocuments_nullRequests_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.batchCreateDocuments((List<DocumentRequest>) null));
    }

    @Test
    @DisplayName("batchCreateDocuments: throws IllegalArgumentException when requests is null (overload)")
    void batchCreateDocuments_nullRequests_overload_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.batchCreateDocuments(null, true, null, false));
    }

    @Test
    @DisplayName("batchDeleteDocuments: throws IllegalArgumentException when ids is null")
    void batchDeleteDocuments_nullIds_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.batchDeleteDocuments(null));
    }

    // ==================== computeSha256 ====================

    @Test
    @DisplayName("computeSha256: same content produces same hash")
    void computeSha256_sameContent_sameHash() {
        String h1 = BatchDocumentService.computeSha256("hello");
        String h2 = BatchDocumentService.computeSha256("hello");
        assertEquals(h1, h2);
    }

    @Test
    @DisplayName("computeSha256: different content produces different hash")
    void computeSha256_differentContent_differentHash() {
        String h1 = BatchDocumentService.computeSha256("hello");
        String h2 = BatchDocumentService.computeSha256("world");
        assertNotEquals(h1, h2);
    }

    @Test
    @DisplayName("computeSha256: returns 64-character hexadecimal string")
    void computeSha256_returnsHexString() {
        String hash = BatchDocumentService.computeSha256("test");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"));
    }
}
