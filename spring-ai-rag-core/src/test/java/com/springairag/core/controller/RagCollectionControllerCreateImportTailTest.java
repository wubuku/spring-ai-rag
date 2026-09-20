package com.springairag.core.controller;

import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.api.dto.CollectionRequest;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.RagCollectionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * RagCollectionController 创建守卫与导入构建长尾（Batch 559，JaCoCo
 * 驱动）：create 携带 Idempotency-Key 但台账服务缺失 → 503、legacy
 * update 拒绝 collectionKey、buildDocumentFromImport 的 enabled 缺
 * 省回退（来源删除 → false）、audit 在无审计服务时静默跳过。
 */
class RagCollectionControllerCreateImportTailTest {

    private RagCollectionController controllerWithoutProvisioning() {
        return new RagCollectionController(
                mock(RagCollectionRepository.class),
                mock(RagDocumentRepository.class),
                mock(RagCollectionService.class),
                mock(CollectionIdentityResolver.class),
                null);
    }

    private CollectionRequest createRequest() {
        CollectionRequest request = new CollectionRequest();
        request.setCollectionKey("kb:prov:v1");
        request.setName("Prov");
        return request;
    }

    @Test
    void createWithIdempotencyKeyWithoutLedgerSurfaces503() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("Idempotency-Key",
                "11111111-1111-1111-1111-111111111111");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(httpRequest));
        try {
            RagException error = assertThrows(RagException.class,
                    () -> controllerWithoutProvisioning().create(
                            createRequest(), httpRequest));
            assertEquals(
                    com.springairag.api.enums.ErrorCode.SERVICE_UNAVAILABLE,
                    error.getErrorCodeEnum());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void legacyUpdateRejectsCollectionKey() {
        CollectionRequest request = createRequest();

        assertThrows(IllegalArgumentException.class,
                () -> controllerWithoutProvisioning().update(5L, request));
    }

    @Test
    void buildDocumentFromImportDefaultsEnabledBySourceDeletion()
            throws Exception {
        Method method = RagCollectionController.class.getDeclaredMethod(
                "buildDocumentFromImport",
                CollectionImportRequest.ImportedDocument.class, Long.class);
        method.setAccessible(true);

        var notDeleted = new CollectionImportRequest.ImportedDocument();
        notDeleted.setTitle("T");
        notDeleted.setContent("C");
        notDeleted.setExternalId("ext-1");
        RagDocument docNotDeleted = (RagDocument) method.invoke(
                controllerWithoutProvisioning(), notDeleted, 5L);
        assertEquals(Boolean.TRUE, docNotDeleted.getEnabled());

        var deleted = new CollectionImportRequest.ImportedDocument();
        deleted.setTitle("T");
        deleted.setContent("C");
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        deleted.setSourceDeletedAt(now);
        RagDocument docDeleted = (RagDocument) method.invoke(
                controllerWithoutProvisioning(), deleted, 5L);
        assertEquals(Boolean.FALSE, docDeleted.getEnabled());
        assertEquals(now, docDeleted.getSourceDeletedAt());
    }

    @Test
    void auditWithoutServiceIsSilentNoOp() throws Exception {
        Method method = RagCollectionController.class.getDeclaredMethod(
                "audit", AuditLogService.AuditAction.class, String.class,
                String.class, String.class);
        method.setAccessible(true);

        // auditLogService 为 null（9 参构造未注入）→ 不抛异常。
        method.invoke(controllerWithoutProvisioning(),
                AuditLogService.AuditAction.CREATE, "Collection", "5", "created");
    }

    @Test
    void castToMapAndImportRoundTripPreservesFields() throws Exception {
        var imported = new CollectionImportRequest.ImportedDocument();
        imported.setTitle("T");
        imported.setContent("C");
        imported.setExternalId("ext-1");
        imported.setSourceNamespace("crm");

        Method build = RagCollectionController.class.getDeclaredMethod(
                "buildDocumentFromImport",
                CollectionImportRequest.ImportedDocument.class, Long.class);
        build.setAccessible(true);
        RagDocument doc = (RagDocument) build.invoke(
                controllerWithoutProvisioning(), imported, 5L);

        assertEquals("ext-1", doc.getExternalId());
        assertEquals("crm", doc.getSourceNamespace());
        assertEquals("PENDING", doc.getProcessingStatus());
    }
}
