package com.springairag.core.controller;

import com.springairag.api.dto.CollectionCloneRequest;
import com.springairag.api.dto.CollectionCloneResponse;
import com.springairag.api.dto.CollectionDocumentListResponse;
import com.springairag.api.dto.CollectionExportResponse;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.RagCollectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagCollectionController by-key 路由长尾（Batch 549，JaCoCo 驱
 * 动）：listDocumentsByKey 分页透传与未知键、exportCollectionByKey
 * 导出映射、cloneCollectionByKey 未命中 404 与无键 IAE、castToMap
 * 与 audit 的兜底分支。
 */
class RagCollectionControllerByKeyTailTest {

    private RagCollectionRepository collectionRepository;
    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver identityResolver;
    private RagCollectionService collectionService;
    private AuditLogService auditLogService;
    private RagCollectionController controller;
    private RagCollection collection;

    @BeforeEach
    void setUp() {
        collectionRepository = mock(RagCollectionRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        identityResolver = mock(CollectionIdentityResolver.class);
        collectionService = mock(RagCollectionService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new RagCollectionController(
                collectionRepository,
                documentRepository,
                collectionService,
                identityResolver,
                auditLogService);

        collection = new RagCollection();
        collection.setId(5L);
        collection.setCollectionKey("kb:manual:v1");
        collection.setEnabled(true);
        when(collectionRepository.findByIdAndDeletedFalse(5L))
                .thenReturn(Optional.of(collection));
        when(identityResolver.mapKeys(List.of(5L)))
                .thenReturn(Map.of(5L, "kb:manual:v1"));
        when(identityResolver.requireActive(
                isNull(), eq("kb:manual:v1"))).thenReturn(collection);
        when(identityResolver.requireActive(
                isNull(), eq("kb:ghost:v1")))
                .thenThrow(new RagException(
                        com.springairag.api.enums.ErrorCode.NOT_FOUND,
                        "no such collection"));
    }

    private RagDocument document(long id) {
        RagDocument doc = new RagDocument();
        doc.setId(id);
        doc.setCollectionId(5L);
        doc.setEnabled(true);
        doc.setTitle("Doc " + id);
        doc.setContent("body");
        return doc;
    }

    @Test
    void listDocumentsByKeyReturnsPagedDocuments() {
        when(documentRepository.findByCollectionId(
                eq(5L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(document(9L))));

        ResponseEntity<CollectionDocumentListResponse> response =
                controller.listDocumentsByKey("kb:manual:v1", 0, 20,
                        null, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(5L, response.getBody().collectionId());
        assertEquals(1, response.getBody().documents().size());
    }

    @Test
    void listDocumentsByKeyWithUnknownKeySurfacesNotFound() {
        when(collectionRepository.findByIdAndDeletedFalse(99L))
                .thenReturn(Optional.empty());
        when(identityResolver.mapKeys(List.of(99L)))
                .thenReturn(Map.of(99L, "kb:ghost:v1"));

        assertThrows(RagException.class,
                () -> controller.listDocumentsByKey("kb:ghost:v1",
                        0, 20, null, null, null));
    }

    @Test
    void exportCollectionByKeyMapsDocuments() {
        when(documentRepository.findAllByCollectionId(5L))
                .thenReturn(List.of(document(9L), document(10L)));

        ResponseEntity<CollectionExportResponse> response =
                controller.exportCollectionByKey("kb:manual:v1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().documents().size());
    }

    @Test
    void cloneCollectionByKeyReturns404WhenSourceMissing() {
        when(collectionRepository.findByIdAndDeletedFalse(5L))
                .thenReturn(Optional.of(collection));
        when(collectionService.cloneCollection(5L, "kb:copy:v1"))
                .thenReturn(Optional.empty());

        CollectionCloneRequest request = new CollectionCloneRequest();
        request.setSourceCollectionKey("kb:manual:v1");
        request.setCollectionKey("kb:copy:v1");

        ResponseEntity<CollectionCloneResponse> response =
                controller.cloneCollectionByKey(request);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void legacyCloneOverloadIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.cloneCollection(5L));
    }

    @Test
    void castToMapPassesMapsAndNullifiesOthers() throws Exception {
        Method method = RagCollectionController.class.getDeclaredMethod(
                "castToMap", Object.class);
        method.setAccessible(true);

        Map<String, Object> source = Map.of("k", "v");
        assertSame(source, method.invoke(controller, source));
        assertNull(method.invoke(controller, new Object[]{null}));
        assertNull(method.invoke(controller, "plain-string"));
    }

    @Test
    void auditSkipsWhenServiceMissingAndDelegatesWhenPresent()
            throws Exception {
        Method method = RagCollectionController.class.getDeclaredMethod(
                "audit", AuditLogService.AuditAction.class, String.class,
                String.class, String.class);
        method.setAccessible(true);

        method.invoke(controller, AuditLogService.AuditAction.DELETE,
                "Document", "7", "removed");

        verify(auditLogService).logDelete(
                eq("Document"), eq("7"), eq("removed"), any());
    }
}
