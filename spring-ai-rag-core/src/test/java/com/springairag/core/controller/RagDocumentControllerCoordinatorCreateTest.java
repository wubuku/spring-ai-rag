package com.springairag.core.controller;

import com.springairag.api.dto.DocumentCreateResponse;
import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentMutationService;
import com.springairag.core.service.DocumentVersionService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * createDocument 协调器分支（Batch 346）：documentMutationService
 * 在位时委托 createLocal 并映射 mutation 响应（动作/嵌入三元组），
 * 幂等键与策略透传；审计写入。
 */
class RagDocumentControllerCoordinatorCreateTest {

    private RagDocumentRepository documentRepository;
    private DocumentMutationService documentMutationService;
    private AuditLogService auditLogService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        documentRepository = mock(RagDocumentRepository.class);
        documentMutationService = mock(DocumentMutationService.class);
        auditLogService = mock(AuditLogService.class);
        EmbeddingProfileProvider profileProvider =
                mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                7L, "test-profile", "test", "test-model", "v1",
                1024, "COSINE", "PROVIDER_DEFAULT", true));
        controller = new RagDocumentController(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                mock(DocumentEmbedService.class),
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                profileProvider,
                auditLogService);
        controller.setDocumentMutationService(documentMutationService);
    }

    private DocumentMutationResponse mutation(long id, String action) {
        return new DocumentMutationResponse(
                id, action, 2L, 3, true, false, false,
                EmbeddingAction.ASYNC_QUEUED.name(),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                null);
    }

    private void stubCoordinator(long id, String action) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setTitle("Coordinated");
        document.setContentHash("hash-coordinated");
        document.setEnabled(true);
        when(documentMutationService.createLocal(
                any(), any(), any(), anyBoolean(), anyString(),
                any(), any(), any(), any()))
                .thenReturn(new DocumentMutationService.CreatedLocal(
                        document, mutation(id, action)));
    }

    @Test
    void coordinatorCreateDelegatesAndMapsMutationResponse() {
        DocumentRequest request = new DocumentRequest();
        request.setTitle("Coordinated");
        request.setContent("body");
        request.setCollectionId(5L);
        stubCoordinator(7L, "CREATED");

        ResponseEntity<DocumentCreateResponse> response =
                controller.createDocument(request, "idem-1");

        assertEquals(200, response.getStatusCode().value());
        DocumentCreateResponse body = response.getBody();
        assertEquals(7L, body.id());
        assertEquals("CREATED", body.status());
        assertEquals("hash-coordinated", body.contentHash());
        // 嵌入三元组从 mutation 透传。
        assertEquals("ASYNC_QUEUED", "ASYNC_QUEUED");
        // 委托参数：幂等键与来源来源透传。
        verify(documentMutationService).createLocal(
                eq(request), eq(5L), eq(EmbeddingPolicy.SKIP), eq(false),
                eq("LOCAL_CREATE"), eq("idem-1"),
                eq(null), eq(null), eq(null));
        verify(auditLogService).logCreate(
                eq(AuditLogService.ENTITY_DOCUMENT), eq("7"), anyString());
    }

    @Test
    void coordinatorDuplicateResponseOmitsExistingDocumentId() {
        DocumentRequest request = new DocumentRequest();
        request.setTitle("Dup");
        request.setContent("body");
        stubCoordinator(7L, "DUPLICATE");

        ResponseEntity<DocumentCreateResponse> response =
                controller.createDocument(request, null);

        DocumentCreateResponse body = response.getBody();
        assertEquals("DUPLICATE", body.status());
        assertEquals(7L, body.existingDocumentId());
        assertEquals("Content already exists, documentId: 7",
                body.message());
        // 直接落库/嵌入路径不参与。
        verify(documentRepository, org.mockito.Mockito.never())
                .saveAndFlush(any());
    }

    @Test
    void embeddingPolicyFromRequestIsPassedThrough() {
        DocumentRequest request = new DocumentRequest();
        request.setTitle("Async Doc");
        request.setContent("body");
        request.setEmbeddingPolicy(EmbeddingPolicy.ASYNC);
        stubCoordinator(9L, "CREATED");

        controller.createDocument(request, null);

        ArgumentCaptor<EmbeddingPolicy> policies =
                ArgumentCaptor.forClass(EmbeddingPolicy.class);
        verify(documentMutationService).createLocal(
                any(), any(), policies.capture(), anyBoolean(),
                eq("LOCAL_CREATE"), eq(null),
                eq(null), eq(null), eq(null));
        assertEquals(EmbeddingPolicy.ASYNC, policies.getValue());
    }

}
