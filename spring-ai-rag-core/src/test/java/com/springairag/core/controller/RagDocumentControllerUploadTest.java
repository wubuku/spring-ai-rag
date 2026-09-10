package com.springairag.core.controller;

import com.springairag.api.dto.BatchCreateResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * uploadAndEmbed 批量上传语义：空文件 400 占位结果、逐文件成功/
 * 失败计数与结果装配（batch 管道）、非文本类型拒绝、空内容拒绝。
 */
class RagDocumentControllerUploadTest {

    private static final long COLLECTION_ID = 7L;

    private BatchDocumentService batchDocumentService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        com.springairag.core.repository.RagDocumentRepository documentRepository =
                mock(com.springairag.core.repository.RagDocumentRepository.class);
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        RagCollectionRepository collectionRepository =
                mock(RagCollectionRepository.class);
        DocumentEmbedService documentEmbedService =
                mock(DocumentEmbedService.class);
        batchDocumentService = mock(BatchDocumentService.class);
        DocumentVersionService documentVersionService =
                mock(DocumentVersionService.class);
        EmbeddingProfileProvider embeddingProfileProvider =
                mock(EmbeddingProfileProvider.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        controller = new RagDocumentController(
                documentRepository,
                embeddingRepository,
                collectionRepository,
                documentEmbedService,
                batchDocumentService,
                documentVersionService,
                embeddingProfileProvider,
                auditLogService);
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
        BatchCreateResponse ok = new BatchCreateResponse(1, 0, 0, List.of(
                new BatchCreateResponse.DocumentResult(
                        41L, "good", true, null,
                        "ASYNC_QUEUED", jobId, batchId)));
        BatchCreateResponse fail = new BatchCreateResponse(0, 0, 1, List.of(
                new BatchCreateResponse.DocumentResult(
                        null, "bad", false, "boom")));
        when(batchDocumentService.batchCreateDocuments(
                anyList(), eq(true), eq(COLLECTION_ID), eq(false), isNull()))
                .thenReturn(ok, fail);

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
        assertEquals("boom", bad.error());
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
        assertEquals("File content is empty",
                body.results().get(0).error());
    }
}
