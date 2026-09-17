package com.springairag.core.controller;

import com.springairag.api.dto.DocumentAddedResponse;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentMutationService;
import com.springairag.core.service.RagCollectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagCollectionController 关联文档长尾（Batch 483，JaCoCo 驱动）：
 * addDocument 的 legacy 直移 / mutation 服务经 updateLocal 迁移 /
 * 期望版本缺失 IAE / 外部管理文档拒绝 / documentId 缺失 / 集合
 * 404 / 受限调用者越权，以及 addDocumentByKey 的键路由与访问校验。
 */
class RagCollectionControllerAddDocumentTailTest {

    private RagCollectionRepository collectionRepository;
    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver identityResolver;
    private DocumentMutationService documentMutationService;
    private RagCollectionController controller;
    private RagCollection collection;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        collectionRepository = mock(RagCollectionRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        identityResolver = mock(CollectionIdentityResolver.class);
        documentMutationService = mock(DocumentMutationService.class);

        RagCollectionController bare = new RagCollectionController(
                collectionRepository,
                documentRepository,
                mock(RagCollectionService.class),
                identityResolver,
                mock(AuditLogService.class));
        controller = bare;
        bare.setDocumentMutationService(null);

        collection = new RagCollection();
        collection.setId(5L);
        collection.setCollectionKey("kb:manual:v1");
        collection.setEnabled(true);
        document = new RagDocument();
        document.setId(9L);
        document.setCollectionId(6L);
        document.setEnabled(true);
    }

    @AfterEach
    void tearDown() {
        org.springframework.web.context.request.RequestContextHolder
                .resetRequestAttributes();
    }

    private Map<String, Long> request(Long documentId, Long expectedRevision) {
        return expectedRevision == null
                ? Map.of("documentId", documentId)
                : Map.of("documentId", documentId,
                        "expectedDocumentRevision", expectedRevision);
    }

    private void stubActiveCollection() {
        when(collectionRepository.findByIdAndDeletedFalse(5L))
                .thenReturn(Optional.of(collection));
        when(identityResolver.mapKeys(java.util.List.of(5L)))
                .thenReturn(Map.of(5L, "kb:manual:v1"));
    }

    @Test
    void legacyPathMovesDocumentDirectly() {
        stubActiveCollection();
        when(documentRepository.findById(9L))
                .thenReturn(Optional.of(document));

        ResponseEntity<DocumentAddedResponse> response =
                controller.addDocument(5L, request(9L, null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(5L, response.getBody().collectionId());
        assertEquals(5L, document.getCollectionId());
        verify(documentRepository).save(document);
        verify(documentMutationService, org.mockito.Mockito.never())
                .updateLocal(anyLong(), any());
    }

    @Test
    void mutationServiceMovesDocumentViaUpdateLocal() {
        stubActiveCollection();
        when(documentRepository.findById(9L))
                .thenReturn(Optional.of(document));
        controller.setDocumentMutationService(documentMutationService);

        ResponseEntity<DocumentAddedResponse> response =
                controller.addDocument(5L, request(9L, 3L));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<com.springairag.api.dto.DocumentUpdateRequest> captor =
                ArgumentCaptor.forClass(
                        com.springairag.api.dto.DocumentUpdateRequest.class);
        verify(documentMutationService).updateLocal(eq(9L), captor.capture());
        assertEquals(3L, captor.getValue().getExpectedDocumentRevision());
        assertEquals("kb:manual:v1",
                captor.getValue().getCollectionKey());
        verify(documentRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void mutationServiceRequiresExpectedRevision() {
        stubActiveCollection();
        when(documentRepository.findById(9L))
                .thenReturn(Optional.of(document));
        controller.setDocumentMutationService(documentMutationService);

        assertThrows(IllegalArgumentException.class,
                () -> controller.addDocument(5L, request(9L, null)));
    }

    @Test
    void externalManagedDocumentIsRejected() {
        stubActiveCollection();
        document.setExternalId("ext-1");
        when(documentRepository.findById(9L))
                .thenReturn(Optional.of(document));

        assertThrows(DocumentRevisionConflictException.class,
                () -> controller.addDocument(5L, request(9L, 3L)));
    }

    @Test
    void missingDocumentIdIsRejected() {
        stubActiveCollection();

        assertThrows(IllegalArgumentException.class,
                () -> controller.addDocument(5L, Map.of()));
    }

    @Test
    void missingCollectionReturns404() {
        when(collectionRepository.findByIdAndDeletedFalse(5L))
                .thenReturn(Optional.empty());
        when(collectionRepository.findById(5L))
                .thenReturn(Optional.empty());

        ResponseEntity<DocumentAddedResponse> response =
                controller.addDocument(5L, request(9L, null));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void restrictedCallerCannotAttachForeignDocument() {
        MockHttpServletRequest httpRequest =
                new MockHttpServletRequest("POST", "/api/collections");
        RagApiKey key = new RagApiKey();
        key.setKeyId("rag_k_caller");
        key.setRole(ApiKeyRole.NORMAL);
        key.setAllowedCollectionIds("5");
        httpRequest.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_KEY_ENTITY,
                key);
        org.springframework.web.context.request.RequestContextHolder
                .setRequestAttributes(
                        new org.springframework.web.context.request
                                .ServletRequestAttributes(httpRequest));
        stubActiveCollection();
        document.setCollectionId(9L);
        when(documentRepository.findById(9L))
                .thenReturn(Optional.of(document));

        assertThrows(SecurityException.class,
                () -> controller.addDocument(5L, request(9L, null)));
    }

    @Test
    void addDocumentByKeyRoutesThroughAccessChecks() {
        stubActiveCollection();
        when(identityResolver.requireActive(null, "kb:manual:v1"))
                .thenReturn(collection);
        when(documentRepository.findById(9L))
                .thenReturn(Optional.of(document));

        ResponseEntity<DocumentAddedResponse> response =
                controller.addDocumentByKey(
                        "kb:manual:v1", request(9L, null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(5L, response.getBody().collectionId());
    }
}
