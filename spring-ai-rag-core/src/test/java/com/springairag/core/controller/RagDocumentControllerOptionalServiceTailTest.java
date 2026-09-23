package com.springairag.core.controller;

import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentVersionRestoreRequest;
import com.springairag.api.dto.ExternalDocumentRelocateRequest;
import com.springairag.api.dto.ExternalDocumentRelocateResponse;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentRelocationService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.service.ExternalDocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagDocumentController 可选服务委托长尾（Batch 577，JaCoCo 驱
 * 动）：relocateExternalDocument 服务缺失 ISE 与正常委托、get
 * ExternalDocument 服务缺失 ISE 与委托、restoreVersion 委托。
 */
class RagDocumentControllerOptionalServiceTailTest {

    private DocumentRelocationService relocationService;
    private ExternalDocumentService externalDocumentService;
    private DocumentVersionService documentVersionService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        relocationService = mock(DocumentRelocationService.class);
        externalDocumentService = mock(ExternalDocumentService.class);
        documentVersionService = mock(DocumentVersionService.class);
        controller = new RagDocumentController(
                mock(RagDocumentRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                mock(DocumentEmbedService.class),
                mock(BatchDocumentService.class),
                documentVersionService,
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                null);
        controller.setDocumentRelocationService(relocationService);
        controller.setExternalDocumentService(externalDocumentService);
        controller.setDocumentMutationService(
                mock(com.springairag.core.service.DocumentMutationService.class));
    }

    @Test
    void relocateWithoutServiceSurfacesIllegalState() {
        var bare = new RagDocumentController(
                mock(RagDocumentRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                mock(DocumentEmbedService.class),
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                null);

        var error = assertThrows(IllegalStateException.class,
                () -> bare.relocateExternalDocument(
                        new ExternalDocumentRelocateRequest(
                                "src-col", "dst-col", "crm", "ext-1", "r1"),
                        "idem-key"));
        assertEquals("Document relocation service is unavailable",
                error.getMessage());
    }

    @Test
    void relocateDelegatesToRelocationService() {
        var request = new ExternalDocumentRelocateRequest(
                "src-col", "dst-col", "crm", "ext-1", "r1");
        var expected = new ExternalDocumentRelocateResponse(
                5L, "src-col", "dst-col", "crm", "ext-1", "r2",
                "RELOCATED", 4L, 3, true, "PRESERVED", null);
        when(relocationService.relocate(request, "idem-key"))
                .thenReturn(expected);

        ResponseEntity<ExternalDocumentRelocateResponse> response =
                controller.relocateExternalDocument(request, "idem-key");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
    }

    @Test
    void getExternalWithoutServiceSurfacesIllegalState() {
        var bare = new RagDocumentController(
                mock(RagDocumentRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                mock(DocumentEmbedService.class),
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                null);

        assertThrows(IllegalStateException.class,
                () -> bare.getExternalDocument("kb", "crm", "ext-1"));
    }

    @Test
    void getExternalDelegatesToExternalService() throws Exception {
        var detail = mock(com.springairag.api.dto.DocumentDetailResponse.class);
        when(externalDocumentService.getByExternalIdentity(
                "kb", "default", "ext-1")).thenReturn(detail);

        ResponseEntity<?> response = controller.getExternalDocument(
                "kb", "default", "ext-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(detail, response.getBody());
    }

    @Test
    void restoreVersionDelegatesToMutationService() {
        var mutationService = mock(
                com.springairag.core.service.DocumentMutationService.class);
        controller.setDocumentMutationService(mutationService);
        var request = new DocumentVersionRestoreRequest(2L, null, null);
        var mutation = new DocumentMutationResponse(
                9L, "UPDATED", 5L, 3, true, false, false,
                "NONE", null, null, null);
        when(mutationService.restoreLocalFromVersion(9L, 3, request))
                .thenReturn(mutation);

        ResponseEntity<DocumentMutationResponse> response =
                controller.restoreVersion(9L, 3, request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(mutation, response.getBody());
    }
}
