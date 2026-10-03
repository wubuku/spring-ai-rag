package com.springairag.core.controller;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.service.PdfImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * RagDocumentController 上传长尾（Batch 713，JaCoCo 驱动）：4 参
 * uploadAndEmbed 便捷重载委托（1188）、原始文件名缺失时归一为
 * "unnamed"（1197，校验阶段即拒绝故无需导入桩）。
 */
class RagDocumentControllerUploadTailTest {

    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        EmbeddingProfileProvider profileProvider =
                mock(EmbeddingProfileProvider.class);
        org.mockito.Mockito.when(profileProvider.getActiveProfile())
                .thenReturn(new EmbeddingProfile(
                        1L, "test-profile", "test", "test-model", "v1",
                        1024, "COSINE", "PROVIDER_DEFAULT", true));
        controller = new RagDocumentController(
                mock(RagDocumentRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                mock(DocumentEmbedService.class),
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                profileProvider,
                null);
    }

    @Test
    void uploadAndEmbedOverloadRejectsEmptyFileArray() {
        ResponseEntity<?> response = controller.uploadAndEmbed(
                new MockMultipartFile[0], 7L, null, false, null, null);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void uploadNormalizesMissingOriginalFilenameBeforeValidation() {
        MockMultipartFile nameless = new MockMultipartFile(
                "file", null, "text/plain",
                "plain body".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ResponseEntity<?> response = controller.uploadAndEmbed(
                new MockMultipartFile[]{nameless}, null, null, false, null, null);

        assertEquals(200, response.getStatusCode().value());
        var body = (com.springairag.api.dto.FileUploadResponse) response.getBody();
        assertEquals(1, body.results().size());
        assertEquals("unnamed", body.results().get(0).filename());
        assertTrue(body.results().get(0).error() != null
                || !body.results().get(0).embedded());
    }
}
