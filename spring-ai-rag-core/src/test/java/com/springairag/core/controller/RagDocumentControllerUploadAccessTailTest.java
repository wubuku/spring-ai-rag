package com.springairag.core.controller;

import com.springairag.api.dto.BatchDeleteItem;
import com.springairag.api.dto.BatchDeleteResponse;
import com.springairag.api.dto.BatchDeleteSummary;
import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentUpdateRequest;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.dto.FileUploadResponse;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentMutationService;
import com.springairag.core.service.DocumentVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagDocumentController 上传与访问守卫长尾（Batch 474，JaCoCo
 * 驱动）：processUploadedFile 矩阵（非文本类型拒绝 / 空内容拒绝 /
 * mutation 服务异常 best-effort / 正常创建 + 幂等键按文件下标派
 * 生）、受限 API Key 的单白名单自动解析与多白名单强制指定、
 * requireDocumentAccess(List) 的受限拒绝 / 缺失文档容忍 /
 * 无限制早退，以及外部服务与 mutation 服务缺省时的 ISE 守卫。
 */
class RagDocumentControllerUploadAccessTailTest {

    private RagDocumentRepository documentRepository;
    private BatchDocumentService batchDocumentService;
    private DocumentMutationService documentMutationService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        batchDocumentService = mock(BatchDocumentService.class);
        documentMutationService = mock(DocumentMutationService.class);
        controller = new RagDocumentController(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                mock(DocumentEmbedService.class),
                batchDocumentService,
                mock(DocumentVersionService.class),
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                mock(AuditLogService.class));
        controller.setDocumentMutationService(documentMutationService);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 以受限 API Key（allowedCollectionIds）伪造请求上下文。 */
    private void restrictTo(String allowedCollectionIds) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/documents");
        RagApiKey key = new RagApiKey();
        key.setRole(ApiKeyRole.NORMAL);
        key.setAllowedCollectionIds(allowedCollectionIds);
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_KEY_ENTITY,
                key);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    private RagDocument document(long id, long collectionId) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setCollectionId(collectionId);
        return document;
    }

    private DocumentMutationService.CreatedLocal createdLocal() {
        DocumentMutationResponse mutation = new DocumentMutationResponse(
                41L, "CREATED", 1L, 1, true, false, false,
                "ASYNC_QUEUED", UUID.randomUUID(), null, null);
        return new DocumentMutationService.CreatedLocal(
                document(41L, 7L), mutation);
    }

    private FileUploadResponse upload(
            MultipartFile[] files, Long collectionId, String idempotencyKey) {
        return controller.uploadAndEmbed(
                files, collectionId, null, false, null, idempotencyKey)
                .getBody();
    }

    // ── processUploadedFile 矩阵 ──────────────────────────────────

    @Test
    void uploadValidTextFileCreatesViaMutationService() {
        when(documentMutationService.createLocal(
                any(), any(), any(), anyBoolean(), anyString(),
                any(), any(), any(), any()))
                .thenReturn(createdLocal());
        MockMultipartFile file = new MockMultipartFile(
                "files", "manual.txt", "text/plain",
                "hello world".getBytes());

        FileUploadResponse response = upload(
                new MultipartFile[]{file}, 7L, " idem-1 ");

        assertEquals(1, response.success());
        FileUploadResponse.FileResult result = response.results().getFirst();
        assertEquals("manual.txt", result.filename());
        assertEquals(41L, result.documentId());
        assertEquals("manual", result.title());
        assertTrue(result.embedded());
        assertNull(result.error());
        ArgumentCaptor<String> idempotencyCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(documentMutationService).createLocal(
                any(), eq(7L), any(), eq(false), eq("FILE_UPLOAD"),
                idempotencyCaptor.capture(), eq("manual.txt"),
                any(), any());
        assertEquals("idem-1:0", idempotencyCaptor.getValue());
    }

    @Test
    void blankIdempotencyKeyStaysNullPerFile() {
        when(documentMutationService.createLocal(
                any(), any(), any(), anyBoolean(), anyString(),
                any(), any(), any(), any()))
                .thenReturn(createdLocal());
        MockMultipartFile file = new MockMultipartFile(
                "files", "a.txt", "text/plain", "content".getBytes());

        upload(new MultipartFile[]{file}, 7L, "   ");

        verify(documentMutationService).createLocal(
                any(), eq(7L), any(), eq(false), eq("FILE_UPLOAD"),
                isNull(), eq("a.txt"), any(), any());
    }

    @Test
    void nonTextFileIsRejectedByValidation() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("report.pdf");
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.isEmpty()).thenReturn(false);
        try {
            when(file.getBytes()).thenThrow(new IOException("binary"));
        } catch (IOException ignored) {
            // stubbing 声明的受检异常，不会发生
        }

        FileUploadResponse response = upload(
                new MultipartFile[]{file}, 7L, null);

        FileUploadResponse.FileResult result = response.results().getFirst();
        assertNull(result.documentId());
        assertTrue(result.error().startsWith("Unsupported file type"));
        verify(documentMutationService, never()).createLocal(
                any(), any(), any(), anyBoolean(), anyString(),
                any(), any(), any(), any());
    }

    @Test
    void blankTextFileIsRejectedAsEmptyContent() {
        MockMultipartFile file = new MockMultipartFile(
                "files", "note.txt", "text/plain", "   ".getBytes());

        FileUploadResponse response = upload(
                new MultipartFile[]{file}, 7L, null);

        assertEquals("File content is empty",
                response.results().getFirst().error());
    }

    @Test
    void mutationFailureIsBestEffortResult() {
        when(documentMutationService.createLocal(
                any(), any(), any(), anyBoolean(), anyString(),
                any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("store down"));
        MockMultipartFile file = new MockMultipartFile(
                "files", "doc.md", "text/plain", "content".getBytes());

        FileUploadResponse response = upload(
                new MultipartFile[]{file}, 7L, null);

        FileUploadResponse.FileResult result = response.results().getFirst();
        assertEquals(1, response.failed());
        assertTrue(result.error().startsWith("Processing failed:"));
    }

    // ── 受限 API Key 的 collection 解析 ───────────────────────────

    @Test
    void restrictedKeyWithSingleAllowedCollectionAutoResolves() {
        restrictTo("7");
        when(documentMutationService.createLocal(
                any(), any(), any(), anyBoolean(), anyString(),
                any(), any(), any(), any()))
                .thenReturn(createdLocal());
        MockMultipartFile file = new MockMultipartFile(
                "files", "doc.txt", "text/plain", "content".getBytes());

        upload(new MultipartFile[]{file}, null, null);

        verify(documentMutationService).createLocal(
                any(), eq(7L), any(), eq(false), eq("FILE_UPLOAD"),
                any(), any(), any(), any());
    }

    @Test
    void restrictedKeyWithMultipleAllowedCollectionsRequiresExplicitId() {
        restrictTo("7,9");
        MockMultipartFile file = new MockMultipartFile(
                "files", "doc.txt", "text/plain", "content".getBytes());

        assertThrows(SecurityException.class,
                () -> upload(new MultipartFile[]{file}, null, null));
    }

    // ── requireDocumentAccess(List) ───────────────────────────────

    private void stubBatchDelete() {
        when(batchDocumentService.batchDeleteDocuments(anyList()))
                .thenReturn(new BatchDeleteResponse(
                        List.of(new BatchDeleteItem(1L, "DELETED")),
                        new BatchDeleteSummary(1, 1, 0)));
    }

    @Test
    void restrictedBatchDeleteRejectsForeignCollection() {
        restrictTo("7");
        when(documentRepository.findAllById(anyList()))
                .thenReturn(List.of(document(1L, 7L), document(2L, 9L)));

        assertThrows(SecurityException.class,
                () -> controller.batchDeleteDocuments(Map.of("ids", List.of(1L, 2L))));

        verify(batchDocumentService, never()).batchDeleteDocuments(anyList());
    }

    @Test
    void batchDeleteToleratesMissingDocuments() {
        restrictTo("7");
        stubBatchDelete();
        // id=99 无行：缺失文档不阻断删除。
        when(documentRepository.findAllById(anyList()))
                .thenReturn(List.of(document(1L, 7L)));

        BatchDeleteResponse response =
                controller.batchDeleteDocuments(Map.of("ids", List.of(1L, 99L))).getBody();

        assertNotNull(response);
        assertEquals(1, response.summary().deleted());
    }

    @Test
    void unrestrictedBatchDeleteSkipsAccessLookup() {
        stubBatchDelete();

        BatchDeleteResponse response =
                controller.batchDeleteDocuments(Map.of("ids", List.of(1L, 2L))).getBody();

        assertNotNull(response);
        verify(documentRepository, never()).findAllById(anyList());
    }

    // ── 可选依赖缺省守卫 ─────────────────────────────────────────

    @Test
    void updateDocumentWithoutMutationServiceIsRejected() {
        RagDocumentController bare = new RagDocumentController(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                mock(DocumentEmbedService.class),
                batchDocumentService,
                mock(DocumentVersionService.class),
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                mock(AuditLogService.class));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> bare.updateDocument(
                        1L, new DocumentUpdateRequest()));

        assertEquals("Document mutation service is not available",
                error.getMessage());
    }

    @Test
    void upsertExternalWithoutServiceIsRejected() {
        RagDocumentController bare = new RagDocumentController(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                mock(DocumentEmbedService.class),
                batchDocumentService,
                mock(DocumentVersionService.class),
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                mock(AuditLogService.class));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> bare.upsertExternalDocument(
                        new ExternalDocumentUpsertRequest()));

        assertEquals("External document service is not available",
                error.getMessage());
    }
}
