package com.springairag.core.service;

import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量创建的协调器路径（Batch 338）：documentMutationService 在
 * 位时逐文档委托 createLocal， CREATED→新建 / 非 CREATED→已存在
 * 的映射，幂等键透传，单文档失败隔离不中止批次。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BatchDocumentCoordinatorTest {

    @Mock RagDocumentRepository documentRepository;
    @Mock RagEmbeddingRepository embeddingRepository;
    @Mock DocumentEmbedService documentEmbedService;
    @Mock DocumentMutationService documentMutationService;

    private BatchDocumentService service;

    @BeforeEach
    void setUp() {
        service = new BatchDocumentService(
                documentRepository, embeddingRepository, documentEmbedService);
        service.setDocumentMutationService(documentMutationService);
        // ASYNC 策略要求持久化嵌入任务可用。
        service.setDispatchService(mock(EmbeddingDispatchService.class));
    }

    private DocumentRequest request(String title) {
        DocumentRequest request = new DocumentRequest();
        request.setTitle(title);
        request.setContent("content of " + title);
        return request;
    }

    private DocumentMutationResponse mutation(
            long documentId, String action) {
        return new DocumentMutationResponse(
                documentId, action, 1L, 1, true, false, false,
                EmbeddingAction.ASYNC_QUEUED.name(),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                null);
    }

    private void stubCreate(long documentId, String action) {
        RagDocument document = new RagDocument();
        document.setId(documentId);
        document.setTitle("t");
        when(documentMutationService.createLocal(
                any(), any(), any(), anyBoolean(), anyString(),
                any(), any(), any(), any()))
                .thenReturn(new DocumentMutationService.CreatedLocal(
                        document,
                        mutation(documentId, action)));
    }

    @Test
    void coordinatorDelegatesPerDocumentAndMapsCreatedResult() {
        when(documentMutationService.createLocal(
                any(), any(), any(), anyBoolean(), anyString(),
                any(), any(), any(), any()))
                .thenReturn(new DocumentMutationService.CreatedLocal(
                        document(7L),
                        mutation(7L, "CREATED")));

        BatchCreateResponse response = service.batchCreateDocuments(
                List.of(request("Doc A")), true, 5L, false,
                EmbeddingPolicy.ASYNC, "idem-key");

        assertEquals(1, response.created());
        assertEquals(7L, response.results().getFirst().documentId());
        assertEquals(true, response.results().getFirst().newlyCreated());
        assertEquals("ASYNC_QUEUED",
                response.results().getFirst().embeddingAction());
        assertNull(response.results().getFirst().error());
        // 委托参数：集合回退到默认、来源 BATCH_CREATE、幂等键派生为 key:index。
        verify(documentMutationService).createLocal(
                any(), eq(5L), eq(EmbeddingPolicy.ASYNC), eq(false),
                eq("BATCH_CREATE"), eq("idem-key:0"),
                eq(null), eq(null), eq(null));
        // 协调器路径不直接落库/嵌入。
        verify(documentRepository, never()).saveAllAndFlush(anyList());
        verify(documentEmbedService, never()).batchEmbedDocuments(
                anyList());
    }

    @Test
    void duplicateActionMapsToSkippedEntry() {
        stubCreate(8L, "DUPLICATE");

        BatchCreateResponse response = service.batchCreateDocuments(
                List.of(request("Doc B")), true, 5L, false,
                EmbeddingPolicy.SKIP, null);

        assertEquals(0, response.created());
        assertEquals(1, response.results().size());
        assertEquals(false, response.results().getFirst().newlyCreated());
        assertNull(response.results().getFirst().error());
    }

    @Test
    void coordinatorFailureIsIsolatedPerDocument() {
        when(documentMutationService.createLocal(
                any(), any(), any(), anyBoolean(), anyString(),
                any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("first fails"))
                .thenReturn(new DocumentMutationService.CreatedLocal(
                        document(9L),
                        mutation(9L, "CREATED")));

        BatchCreateResponse response = service.batchCreateDocuments(
                List.of(request("Bad"), request("Good")), true, 5L, false,
                EmbeddingPolicy.SKIP, null);

        // 单文档失败不中止批次：第二文档照常创建。
        assertEquals(1, response.created());
        assertEquals(1, response.failed());
        assertNull(response.results().get(0).documentId());
        assertEquals("Bad", response.results().get(0).title());
        assertEquals(9L, response.results().get(1).documentId());
    }

    @Test
    void documentLevelCollectionIdOverridesBatchDefault() {
        when(documentMutationService.createLocal(
                any(), any(), any(), anyBoolean(), anyString(),
                any(), any(), any(), any()))
                .thenReturn(new DocumentMutationService.CreatedLocal(
                        document(12L),
                        mutation(12L, "CREATED")));
        DocumentRequest scoped = request("Scoped");
        scoped.setCollectionId(9L);

        service.batchCreateDocuments(
                List.of(scoped), true, 5L, false,
                EmbeddingPolicy.ASYNC, null);

        // 文档级集合覆盖批量默认（5L 未被使用）。
        verify(documentMutationService).createLocal(
                any(), eq(9L),
                eq(EmbeddingPolicy.ASYNC), eq(false),
                eq("BATCH_CREATE"), eq(null),
                eq(null), eq(null), eq(null));
    }

    private RagDocument document(long id) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setTitle("Doc");
        document.setEnabled(true);
        return document;
    }
}
