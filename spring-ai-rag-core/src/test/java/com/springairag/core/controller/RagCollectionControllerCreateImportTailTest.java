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
import com.springairag.core.service.DocumentMutationService;
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
                null,
                mock(DocumentMutationService.class));
    }

    private CollectionRequest createRequest() {
        CollectionRequest request = new CollectionRequest();
        request.setCollectionKey("kb:prov:v1");
        request.setName("Prov");
        return request;
    }

    // Batch 822 deleted createWithIdempotencyKeyWithoutLedgerSurfaces503. It
    // built a controller with no CollectionProvisioningService and asserted
    // SERVICE_UNAVAILABLE from the guard that preceded the create-or-replay
    // call. That service is an unconditional @Service, so the guard could never
    // fire; the endpoint now calls the ledger directly, as the running
    // application does. The idempotent-replay path itself is still covered.

    @Test
    void updateRejectsSuppliedCollectionKey() {
        // Batch 819 deleted a compatibility overload that took the old
        // CollectionRequest purely to police a key the HTTP DTO already refuses to
        // carry. The real contract is that a client may still POST a collectionKey
        // field — captureCollectionKey records it — and the production signature
        // rejects it. Assert that instead of the shim's shadow copy of the rule.
        com.springairag.api.dto.CollectionUpdateRequest request =
                new com.springairag.api.dto.CollectionUpdateRequest();
        request.setName("renamed");
        request.captureCollectionKey("stolen-key");

        assertThrows(IllegalArgumentException.class,
                () -> controllerWithoutProvisioning().update(5L, request));
    }

    // Batch 847 删除了 buildDocumentFromImport 与 controller 侧的 json-record
    // 分支。原先通过反射调用该私有方法的两个用例（enabled 缺省、
    // castToMap/import 往返）随之失效：字段映射现在由
    // DocumentMutationService.importDocument 负责，覆盖在
    // DocumentMutationImportTest / DocumentMutationExternalFinishTailTest。

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
}
