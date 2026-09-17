package com.springairag.core.controller;

import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.RagCollectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagCollectionController 导入与清理守卫长尾（Batch 491，JaCoCo
 * 驱动）：importCollection 的空请求/空白 name/空白 collectionKey
 * 三拒绝与正常导入落库（维度/启用缺省回填）、purge 服务缺省时的
 * SERVICE_UNAVAILABLE 守卫。
 */
class RagCollectionControllerImportPurgeTailTest {

    private RagCollectionRepository collectionRepository;
    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver identityResolver;
    private RagCollectionService collectionService;
    private RagCollectionController controller;

    @BeforeEach
    void setUp() {
        collectionRepository = mock(RagCollectionRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        identityResolver = mock(CollectionIdentityResolver.class);
        collectionService = mock(RagCollectionService.class);
        controller = new RagCollectionController(
                collectionRepository,
                documentRepository,
                collectionService,
                identityResolver,
                mock(AuditLogService.class));
    }

    private CollectionImportRequest importRequest(String name, String key) {
        CollectionImportRequest request = new CollectionImportRequest();
        request.setName(name);
        request.setCollectionKey(key);
        return request;
    }

    @Test
    void importRejectsNullNameAndBlankKey() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.importCollection(null));

        assertThrows(IllegalArgumentException.class,
                () -> controller.importCollection(
                        importRequest("  ", "kb:manual:v1")));

        assertThrows(IllegalArgumentException.class,
                () -> controller.importCollection(
                        importRequest("Manual", " ")));
        verify(collectionService, org.mockito.Mockito.never())
                .createCollection(any());
    }

    @Test
    void importCreatesCollectionWithDefaultsAndReturnsMap() {
        RagCollection created = new RagCollection();
        created.setId(5L);
        created.setCollectionKey("kb:manual:v1");
        created.setName("Manual");
        when(collectionService.createCollection(any())).thenReturn(created);
        when(collectionRepository.findById(5L))
                .thenReturn(Optional.of(created));

        CollectionImportRequest request =
                importRequest("Manual", "kb:manual:v1");
        request.setDocuments(java.util.List.of());

        ResponseEntity<Map<String, Object>> response =
                controller.importCollection(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().get("importedDocuments"));
        org.mockito.ArgumentCaptor<com.springairag.api.dto.CollectionRequest>
                captor = org.mockito.ArgumentCaptor
                .forClass(com.springairag.api.dto.CollectionRequest.class);
        verify(collectionService).createCollection(captor.capture());
        assertEquals("kb:manual:v1", captor.getValue().getCollectionKey());
    }

    @Test
    void purgeEndpointsRejectWhenPurgeServiceMissing() {
        RagException previewError = assertThrows(RagException.class,
                () -> controller.previewPurge("kb:manual:v1",
                        new org.springframework.mock.web.MockHttpServletRequest()));
        assertEquals(
                com.springairag.api.enums.ErrorCode.SERVICE_UNAVAILABLE,
                previewError.getErrorCodeEnum());
        assertTrue(previewError.getMessage().contains("purge"));

        RagException applyError = assertThrows(RagException.class,
                () -> controller.applyPurge(
                        new com.springairag.api.dto.CollectionPurgeApplyRequest(
                                "kb:manual:v1", java.util.UUID.randomUUID(),
                                "token", "fp", 1L, 1L),
                        new org.springframework.mock.web.MockHttpServletRequest()));
        assertEquals(
                com.springairag.api.enums.ErrorCode.SERVICE_UNAVAILABLE,
                applyError.getErrorCodeEnum());
    }
}
