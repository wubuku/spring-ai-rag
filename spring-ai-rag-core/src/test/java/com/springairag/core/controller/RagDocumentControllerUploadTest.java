package com.springairag.core.controller;

import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.FileUploadResponse;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.service.extract.DocumentParsingCorpus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.springairag.core.service.ExternalDocumentService;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.DocumentRelocationService;
import com.springairag.core.service.DocumentMutationService;
import com.springairag.core.service.DocumentQueryService;

/**
 * uploadAndEmbed 批量上传语义：空文件 400 占位结果、逐文件成功/
 * 失败计数与结果装配（batch 管道）、非文本类型拒绝、空内容拒绝。
 */
class RagDocumentControllerUploadTest {

    private static final long COLLECTION_ID = 7L;

    private BatchDocumentService batchDocumentService;
    private com.springairag.core.repository.RagDocumentRepository documentRepository;
    private com.springairag.core.service.DocumentMutationService documentMutationService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        documentRepository =
                mock(com.springairag.core.repository.RagDocumentRepository.class);
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        RagCollectionRepository collectionRepository =
                mock(RagCollectionRepository.class);
        DocumentEmbedService documentEmbedService =
                mock(DocumentEmbedService.class);
        batchDocumentService = mock(BatchDocumentService.class);
        documentMutationService =
                mock(com.springairag.core.service.DocumentMutationService.class);
        DocumentVersionService documentVersionService =
                mock(DocumentVersionService.class);
        EmbeddingProfileProvider embeddingProfileProvider =
                mock(EmbeddingProfileProvider.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        controller = new RagDocumentController(
                new DocumentQueryService(documentRepository, collectionRepository, embeddingRepository),
                documentEmbedService,
                batchDocumentService,
                documentVersionService,
                embeddingProfileProvider,
                new CollectionIdentityResolver(collectionRepository),
                auditLogService,
                documentMutationService,

                mock(ExternalDocumentService.class),


                mock(DocumentDerivationDescriptorProvider.class),



                mock(DocumentRelocationService.class));
        // Batch 847：上传已无条件走变更层。
    }

    private MockMultipartFile txtFile(String name, String content) {
        return new MockMultipartFile(
                "files", name, "text/plain", content.getBytes());
    }

    @Test
    void emptyFileArrayReturnsBadRequestWithPlaceholderResult() {
        ResponseEntity<FileUploadResponse> response =
                controller.uploadAndEmbed(
                        new MultipartFile[0], COLLECTION_ID, null, false, null, null);

        assertEquals(400, response.getStatusCode().value());
        FileUploadResponse body = response.getBody();
        assertEquals(0, body.processed());
        assertEquals(0, body.success());
        assertEquals(0, body.failed());
        assertEquals("No file uploaded",
                body.results().get(0).error());
    }

    @Test
    void mixedSuccessAndFailureFilesAreCountedPerResult() {
        UUID jobId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        // Batch 847：逐个文件交给变更层，失败是变更层抛出来的，
        // controller 把它收成该文件的一条失败结果。
        com.springairag.core.entity.RagDocument created =
                new com.springairag.core.entity.RagDocument();
        created.setId(41L);
        com.springairag.api.dto.DocumentMutationResponse okMutation =
                new com.springairag.api.dto.DocumentMutationResponse(
                        41L, "CREATED", 1L, 1, true, false, false,
                        "ASYNC_QUEUED", jobId, batchId, null);
        when(documentMutationService.createLocal(
                any(), any(), any(), anyBoolean(), anyString(), any(), any(), any(), any()))
                .thenReturn(new com.springairag.core.service.DocumentMutationService.CreatedLocal(
                        created, okMutation))
                .thenThrow(new IllegalStateException("boom"));

        ResponseEntity<FileUploadResponse> response = controller.uploadAndEmbed(
                new MultipartFile[]{
                        txtFile("good.txt", "good content"),
                        txtFile("bad.txt", "bad content")},
                COLLECTION_ID, null, false, null, null);

        assertEquals(200, response.getStatusCode().value());
        FileUploadResponse body = response.getBody();
        assertEquals(2, body.processed());
        assertEquals(1, body.success());
        assertEquals(1, body.failed());

        FileUploadResponse.FileResult good = body.results().get(0);
        assertEquals("good.txt", good.filename());
        assertEquals(41L, good.documentId());
        assertEquals("good", good.title());
        assertNull(good.error());
        assertEquals("ASYNC_QUEUED", good.embeddingAction());
        assertEquals(jobId, good.embeddingJobId());

        FileUploadResponse.FileResult bad = body.results().get(1);
        assertEquals("bad.txt", bad.filename());
        assertNull(bad.documentId());
        // 失败原因由 uploadAndEmbed 的兜底 catch 统一包装。
        assertEquals("Processing failed: boom", bad.error());
        // 变更层失败时不该再落回批量写入那条已删除的路径。
        verify(batchDocumentService, never()).batchCreateDocuments(
                anyList(), anyBoolean(), any(), anyBoolean(), any());
    }

    @Test
    void nonTextExtensionIsRejectedAsUnsupportedType() throws Exception {
        // 拒绝分支在 getBytes() 抛异常时触发（非文本且不可读）。
        MultipartFile png = mock(MultipartFile.class);
        when(png.getOriginalFilename()).thenReturn("pic.png");
        when(png.getContentType()).thenReturn("image/png");
        when(png.isEmpty()).thenReturn(false);
        when(png.getBytes())
                .thenThrow(new java.io.IOException("not readable"));

        ResponseEntity<FileUploadResponse> response = controller.uploadAndEmbed(
                new MultipartFile[]{png}, COLLECTION_ID, null, false, null, null);

        assertEquals(200, response.getStatusCode().value());
        FileUploadResponse body = response.getBody();
        assertEquals(1, body.processed());
        assertEquals(0, body.success());
        assertEquals(1, body.failed());
        String error = body.results().get(0).error();
        assertTrue(error != null
                && error.contains("Unsupported file type: image/png"));
    }

    @Test
    void blankTextFileIsRejectedAsEmptyContent() {
        ResponseEntity<FileUploadResponse> response = controller.uploadAndEmbed(
                new MultipartFile[]{txtFile("blank.txt", "   ")},
                COLLECTION_ID, null, false, null, null);

        assertEquals(200, response.getStatusCode().value());
        FileUploadResponse body = response.getBody();
        assertEquals(1, body.failed());
        assertTrue(body.results().get(0).error().contains("No extractable text"));
    }

    @Test
    void gbkTextFileIsDecodedBeforeStoring() {
        stubSuccessfulCreate();
        MultipartFile gbk = new MockMultipartFile(
                "files", "gbk.txt", "text/plain", DocumentParsingCorpus.gbkText());

        ResponseEntity<FileUploadResponse> response = controller.uploadAndEmbed(
                new MultipartFile[]{gbk}, COLLECTION_ID, null, false, null, null);

        assertEquals(1, response.getBody().success());
        DocumentRequest captured = captureSingleRequest();
        assertEquals("中文编码测试：你好，世界。", captured.getContent());
        assertEquals("gbk", captured.getTitle());
    }

    @Test
    void htmlNoiseIsRemovedBeforeStoring() {
        stubSuccessfulCreate();
        MultipartFile html = new MockMultipartFile(
                "files", "page.html", "text/html", DocumentParsingCorpus.htmlWithNoise());

        ResponseEntity<FileUploadResponse> response = controller.uploadAndEmbed(
                new MultipartFile[]{html}, COLLECTION_ID, null, false, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().success());
        DocumentRequest captured = captureSingleRequest();
        assertTrue(captured.getContent().contains("这是可见段落。"));
        assertFalse(captured.getContent().contains("IGNORE_ME"));
        assertFalse(captured.getContent().contains("SECRET_NOISE"));
    }

    @Test
    void docxTableStructureIsPreservedBeforeStoring() throws Exception {
        stubSuccessfulCreate();
        // DOCX 必须从真实二进制 ZIP 读取，构造器声明 IOException。
        MultipartFile docx = new MockMultipartFile(
                "files", "corpus.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                DocumentParsingCorpus.docx());

        ResponseEntity<FileUploadResponse> response = controller.uploadAndEmbed(
                new MultipartFile[]{docx}, COLLECTION_ID, null, false, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().success());
        DocumentRequest captured = captureSingleRequest();
        assertTrue(captured.getContent().contains("| 姓名 | 年龄 |"));
        assertTrue(captured.getContent().contains("| 张三 | 30 |"));
    }

    @Test
    void fakeExtensionIsRejectedWithExplicitError() {
        MultipartFile fakeMarkdown = new MockMultipartFile(
                "files", "fake.md", "text/markdown", DocumentParsingCorpus.pngBytes());

        ResponseEntity<FileUploadResponse> response = controller.uploadAndEmbed(
                new MultipartFile[]{fakeMarkdown}, COLLECTION_ID, null, false, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().failed());
        String error = response.getBody().results().get(0).error();
        assertTrue(error.contains("binary"), "unexpected error: " + error);
        verify(documentMutationService, never()).createLocal(
                any(), any(), any(), anyBoolean(), anyString(), any(), any(), any(), any());
    }

    @Test
    void zeroByteFileIsRejectedWithoutMutation() {
        MultipartFile empty = new MockMultipartFile(
                "files", "empty.md", "text/markdown", new byte[0]);

        ResponseEntity<FileUploadResponse> response = controller.uploadAndEmbed(
                new MultipartFile[]{empty}, COLLECTION_ID, null, false, null, null);

        assertEquals(1, response.getBody().failed());
        assertTrue(response.getBody().results().get(0).error().contains("empty"));
        verify(documentMutationService, never()).createLocal(
                any(), any(), any(), anyBoolean(), anyString(), any(), any(), any(), any());
    }

    private void stubSuccessfulCreate() {
        com.springairag.core.entity.RagDocument created =
                new com.springairag.core.entity.RagDocument();
        created.setId(41L);
        com.springairag.api.dto.DocumentMutationResponse okMutation =
                new com.springairag.api.dto.DocumentMutationResponse(
                        41L, "CREATED", 1L, 1, true, false, false,
                        "ASYNC_QUEUED", UUID.randomUUID(), UUID.randomUUID(), null);
        when(documentMutationService.createLocal(
                any(), any(), any(), anyBoolean(), anyString(), any(), any(), any(), any()))
                .thenReturn(new com.springairag.core.service.DocumentMutationService.CreatedLocal(
                        created, okMutation));
    }

    private DocumentRequest captureSingleRequest() {
        ArgumentCaptor<DocumentRequest> captor = ArgumentCaptor.forClass(DocumentRequest.class);
        verify(documentMutationService).createLocal(
                captor.capture(), any(), any(), anyBoolean(), anyString(),
                any(), any(), any(), any());
        return captor.getValue();
    }
}
