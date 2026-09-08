package com.springairag.core.controller;

import com.springairag.api.dto.CollectionUpdateRequest;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.RagCollectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * by-key 路由语义：以 collectionKey 解析活跃集合后委托数字路由，
 * 覆盖读取（含文档计数）、更新、删除、恢复（含已删除集合的解析）。
 */
@ExtendWith(MockitoExtension.class)
class RagCollectionByKeyTest {

    private static final String KEY = "tenant:manual";

    @Mock RagCollectionRepository collectionRepository;
    @Mock RagDocumentRepository documentRepository;
    @Mock RagCollectionService collectionService;
    @Mock CollectionIdentityResolver identityResolver;
    @Mock AuditLogService auditLogService;

    private RagCollectionController controller;
    private RagCollection collection;

    @BeforeEach
    void setUp() {
        controller = new RagCollectionController(
                collectionRepository,
                documentRepository,
                collectionService,
                identityResolver,
                auditLogService);
        collection = new RagCollection();
        collection.setId(7L);
        collection.setCollectionKey(KEY);
        collection.setName("Manual");

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/rag/collections/by-key");
        request.setAttribute("authenticatedPrincipalType", "DATABASE_API_KEY");
        request.setAttribute("authenticatedApiKey", "key-42");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
        lenient().when(identityResolver.requireActive(isNull(), eq(KEY)))
                .thenReturn(collection);
        lenient().when(identityResolver.requireIncludingDeleted(
                isNull(), eq(KEY))).thenReturn(collection);
    }

    @Test
    void getByKeyReturnsCollectionMapWithDocumentCount() {
        when(documentRepository.countByCollectionId(7L)).thenReturn(5L);

        ResponseEntity<Map<String, Object>> response =
                controller.getByKey(KEY);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(7L, response.getBody().get("id"));
        assertEquals(5L, response.getBody().get("documentCount"));
    }

    @Test
    void updateByKeyDelegatesToNumericUpdateWithResolvedId() {
        collection.setDeleted(false);
        when(documentRepository.countByCollectionId(7L)).thenReturn(1L);
        when(collectionRepository.findByIdAndDeletedFalse(7L))
                .thenReturn(Optional.of(collection));
        when(collectionRepository.save(any(RagCollection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        CollectionUpdateRequest request = new CollectionUpdateRequest();
        request.setName("Renamed");

        ResponseEntity<Map<String, Object>> response =
                controller.updateByKey(KEY, request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Renamed", response.getBody().get("name"));
        verify(collectionRepository).save(any(RagCollection.class));
    }

    @Test
    void deleteByKeySoftDeletesThroughNumericRoute() {
        collection.setDeleted(false);
        when(collectionService.deleteCollection(7L))
                .thenReturn(Optional.of(
                        new RagCollectionService.DeleteResult(7L, 2L)));

        ResponseEntity<Map<String, String>> response =
                controller.deleteByKey(KEY);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("7", response.getBody().get("id"));
        assertEquals("2", response.getBody().get("documentsUnlinked"));
    }

    @Test
    void restoreByKeyRestoresDeletedCollectionThroughNumericRoute() {
        collection.setDeleted(true);
        RagCollection restored = new RagCollection();
        restored.setId(7L);
        restored.setCollectionKey(KEY);
        restored.setName("Manual");
        restored.setDeleted(false);
        when(collectionService.restoreCollection(7L))
                .thenReturn(Optional.of(
                        new RagCollectionService.RestoreResult(restored, 3L)));
        when(documentRepository.countByCollectionId(7L)).thenReturn(3L);

        ResponseEntity<com.springairag.api.dto.CollectionRestoreResponse>
                response = controller.restoreByKey(KEY);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Long.valueOf(7L), response.getBody().collectionId());
        assertEquals(Long.valueOf(3L), response.getBody().documentCount());
    }

    @Test
    void missingByKeyLookupSurfacesRagException() {
        when(identityResolver.requireActive(isNull(), eq(KEY)))
                .thenThrow(new RagException(
                        com.springairag.api.enums.ErrorCode.NOT_FOUND,
                        "collection not found"));

        assertThrows(RagException.class, () -> controller.getByKey(KEY));
    }
}
