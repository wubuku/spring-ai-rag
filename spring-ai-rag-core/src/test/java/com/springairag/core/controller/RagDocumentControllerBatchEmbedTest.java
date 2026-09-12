package com.springairag.core.controller;

import com.springairag.api.dto.BatchCreateAndEmbedRequest;
import com.springairag.api.dto.BatchCreateAndEmbedResponse;
import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.BatchEmbedResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * batchEmbedDocuments 的 ASYNC/SYNC 双路与废弃
 * batchCreateAndEmbed 映射（Batch 316）：ASYNC 逐文档入队与汇
 * 总、jobs 禁用拒绝、SYNC 原始结果映射与审计、ids 守卫、
 * legacy 批建嵌响应换算与集合作用域归一。
 */
class RagDocumentControllerBatchEmbedTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            7L, "test-profile", "test", "test-model", "v1",
            1024, "COSINE", "PROVIDER_DEFAULT", true);

    private RagDocumentRepository documentRepository;
    private DocumentEmbedService documentEmbedService;
    private BatchDocumentService batchDocumentService;
    private EmbeddingDispatchService dispatchService;
    private AuditLogService auditLogService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        documentRepository = mock(RagDocumentRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        batchDocumentService = mock(BatchDocumentService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        auditLogService = mock(AuditLogService.class);
        EmbeddingProfileProvider profileProvider =
                mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(PROFILE);
        controller = new RagDocumentController(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                documentEmbedService,
                batchDocumentService,
                mock(DocumentVersionService.class),
                profileProvider,
                auditLogService);
        controller.setDispatchService(dispatchService);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private RagDocument document(long id) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setTitle("Doc " + id);
        document.setEnabled(true);
        document.setProcessingStatus("COMPLETED");
        return document;
    }

    private EmbeddingDispatchService.Result queuedResult(long id) {
        return new EmbeddingDispatchService.Result(
                EmbeddingAction.ASYNC_QUEUED, "QUEUED", "bge-m3",
                UUID.randomUUID(), null, null);
    }

    // ── ASYNC 批量嵌入 ──────────────────────────────────────────────

    @Test
    void asyncPolicyQueuesEveryDocument() {
        when(documentRepository.findAllById(List.of(1L, 2L)))
                .thenReturn(List.of(document(1L), document(2L)));
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), eq(false), eq(false),
                eq("BATCH_EMBED")))
                .thenAnswer(invocation -> queuedResult(
                        invocation.getArgument(0, RagDocument.class).getId()));

        ResponseEntity<BatchEmbedResponse> response = controller
                .batchEmbedDocuments(Map.of("ids", List.of(1L, 2L)),
                        EmbeddingPolicy.ASYNC);

        assertEquals(200, response.getStatusCode().value());
        BatchEmbedResponse body = response.getBody();
        assertEquals(2, body.results().size());
        assertEquals("QUEUED", body.results().get(0).status());
        assertEquals("ASYNC_QUEUED", body.results().get(0).reason());
        assertEquals(2, body.summary().total());
        assertEquals(2, body.summary().success());
        assertEquals(0, body.summary().skipped());
        verify(dispatchService, org.mockito.Mockito.times(2))
                .enqueueInCurrentTransaction(
                        any(RagDocument.class), eq(false), eq(false),
                        eq("BATCH_EMBED"));
    }

    @Test
    void asyncPolicyRequiresJobsEnabled() {
        controller.setDispatchService(null);

        RagException error = assertThrows(RagException.class,
                () -> controller.batchEmbedDocuments(
                        Map.of("ids", List.of(1L)), EmbeddingPolicy.ASYNC));

        assertEquals(ErrorCode.EMBEDDING_JOBS_DISABLED,
                error.getErrorCodeEnum());
    }

    // ── SYNC 批量嵌入（缺省策略）────────────────────────────────────

    @Test
    void defaultPolicyRunsSyncEmbeddingWithAudit() {
        Map<String, Object> raw = Map.of(
                "results", List.of(
                        Map.of("documentId", 1L, "status", "SUCCESS",
                                "chunksCreated", 3, "embeddingsStored", 3),
                        Map.of("documentId", 2L, "status", "FAILED",
                                "error", "boom")),
                "summary", Map.of(
                        "total", 2, "success", 1, "cached", 0,
                        "failed", 1, "skipped", 0));
        when(documentEmbedService.batchEmbedDocuments(List.of(1L, 2L)))
                .thenReturn(raw);

        ResponseEntity<BatchEmbedResponse> response = controller
                .batchEmbedDocuments(Map.of("ids", List.of(1L, 2L)), null);

        assertEquals(200, response.getStatusCode().value());
        BatchEmbedResponse body = response.getBody();
        assertEquals("SUCCESS", body.results().get(0).status());
        assertEquals(3, body.results().get(0).chunksCreated());
        assertEquals("FAILED", body.results().get(1).status());
        assertEquals("boom", body.results().get(1).error());
        assertEquals(1, body.summary().failed());
        // SYNC 路径写批量嵌入审计。
        verify(auditLogService).logCreate(
                eq(AuditLogService.ENTITY_EMBED_CACHE),
                eq("batch:2"), any(), any());
    }

    @Test
    void batchEmbedValidatesIdList() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.batchEmbedDocuments(
                        Map.of("ids", List.of()), null));
        assertThrows(IllegalArgumentException.class,
                () -> controller.batchEmbedDocuments(
                        new java.util.HashMap<>(Map.of("ids", List.of())),
                        null));
        assertThrows(IllegalArgumentException.class,
                () -> controller.batchEmbedDocuments(
                        Map.of("ids", range(51)), null));
    }

    private List<Long> range(int size) {
        return java.util.stream.LongStream.rangeClosed(1, size)
                .boxed().toList();
    }

    // ── 废弃 batchCreateAndEmbed ────────────────────────────────────

    @Test
    void batchCreateAndEmbedMapsLegacyResponse() {
        BatchCreateAndEmbedRequest request =
                new BatchCreateAndEmbedRequest();
        request.setCollectionId(5L);
        request.setDocuments(List.of(request("Doc A"), request("Doc B")));
        request.setForce(true);
        when(batchDocumentService.batchCreateDocuments(
                anyList(), eq(true), eq(5L), eq(true)))
                .thenReturn(new BatchCreateResponse(2, 1, 0, List.of(
                        new BatchCreateResponse.DocumentResult(
                                11L, "Doc A", true, null),
                        new BatchCreateResponse.DocumentResult(
                                12L, "Doc B", false, null))));

        ResponseEntity<?> response = controller.batchCreateAndEmbed(request);

        assertEquals(200, response.getStatusCode().value());
        BatchCreateAndEmbedResponse body =
                (BatchCreateAndEmbedResponse) response.getBody();
        assertEquals(2, body.created());
        assertEquals(2, body.embedded());
        assertEquals(1, body.skipped());
        assertEquals(0, body.failed());
        assertEquals(11L, body.results().get(0).documentId());
        // r.newlyCreated() 映射到 legacy 的 embedded 字段。
        assertEquals(true, body.results().get(0).embedded());
        assertEquals(0, body.results().get(0).chunks());
        assertEquals(12L, body.results().get(1).documentId());
    }

    @Test
    void batchCreateAndEmbedNormalizesDocumentCollectionScopes() {
        BatchCreateAndEmbedRequest request =
                new BatchCreateAndEmbedRequest();
        request.setCollectionId(5L);
        DocumentRequest scoped = request("Doc C");
        scoped.setCollectionId(9L);
        DocumentRequest unscoped = request("Doc D");
        request.setDocuments(List.of(scoped, unscoped));
        when(batchDocumentService.batchCreateDocuments(
                anyList(), eq(true), eq(5L), anyBoolean()))
                .thenReturn(new BatchCreateResponse(0, 0, 0, List.of()));

        controller.batchCreateAndEmbed(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentRequest>> docs = ArgumentCaptor.forClass(
                (Class<List<DocumentRequest>>) (Class) List.class);
        verify(batchDocumentService).batchCreateDocuments(
                docs.capture(), eq(true), eq(5L), eq(false));
        // 文档自带 collectionId 保留；无作用域文档落到请求级默认，键被清除。
        assertEquals(9L, docs.getValue().get(0).getCollectionId());
        assertEquals(5L, docs.getValue().get(1).getCollectionId());
        assertEquals(5L, request.getCollectionId());
    }

    private DocumentRequest request(String title) {
        DocumentRequest request = new DocumentRequest();
        request.setTitle(title);
        request.setContent("body of " + title);
        return request;
    }
}
