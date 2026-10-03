package com.springairag.core.controller;

import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentUpdateRequest;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.dto.ExternalDocumentUpsertResponse;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentLifecycleService;
import com.springairag.core.service.DocumentMutationService;
import com.springairag.core.service.DocumentRelocationService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.service.ExternalDocumentService;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagDocumentController 按**生产接线**构造的用例。
 *
 * <p>Batch 821。背景：19 个测试文件直接 {@code new RagDocumentController(...)}，
 * 而其中只有 8 个调用了那些 setter——也就是说**十一个文件跑在一个应用根本不会
 * 产生的装配上**，而**没有一个是 {@code @SpringBootTest}**，于是生产接线
 * （协作者齐全）至今没有任何用例走过。
 *
 * <p>这个类补的正是那一半：把协作者全部接上，然后断言
 * <strong>每个被 {@code if (x == null)} 守卫的字段都非空</strong>，
 * 并且守卫所保护的调用确实走到了协作者手里。
 * 反射断言不是循环论证——它编码的正是"生产接线 = 协作者齐全"这条没人验证过的约定；
 * 而 {@code scripts/verify-false-optional-wiring.mjs} 证明这些协作者全是无条件
 * {@code @Service}，所以 null 那一支在任何运行的应用里都走不到。
 *
 * <p>本类取代了两个已删除的用例：它们构造一个"裸" controller 再断言
 * {@code IllegalStateException}——那是在给覆盖率记账，不是在验证契约。
 */
class RagDocumentControllerProductionWiringTest {

    private DocumentMutationService documentMutationService;
    private ExternalDocumentService externalDocumentService;
    private DocumentLifecycleService documentLifecycleService;
    private DocumentDerivationDescriptorProvider derivationDescriptorProvider;
    private DocumentRelocationService documentRelocationService;
    private EmbeddingDispatchService dispatchService;
    private AuditLogService auditLogService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        documentMutationService = mock(DocumentMutationService.class);
        externalDocumentService = mock(ExternalDocumentService.class);
        documentLifecycleService = mock(DocumentLifecycleService.class);
        derivationDescriptorProvider = mock(DocumentDerivationDescriptorProvider.class);
        documentRelocationService = mock(DocumentRelocationService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        auditLogService = mock(AuditLogService.class);

        controller = new RagDocumentController(
                mock(RagDocumentRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                mock(DocumentEmbedService.class),
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                auditLogService);
        controller.setDocumentMutationService(documentMutationService);
        controller.setExternalDocumentService(externalDocumentService);
        controller.setDocumentLifecycleService(documentLifecycleService);
        controller.setDerivationDescriptorProvider(derivationDescriptorProvider);
        controller.setDocumentRelocationService(documentRelocationService);
        controller.setDispatchService(dispatchService);
    }

    /** The claim the whole class exists to make: production wiring has no null. */
    @Test
    void everyNullGuardedCollaboratorIsWired() throws Exception {
        for (String field : List.of(
                "documentMutationService", "externalDocumentService",
                "documentLifecycleService", "derivationDescriptorProvider",
                "documentRelocationService", "dispatchService",
                "auditLogService")) {
            Field f = RagDocumentController.class.getDeclaredField(field);
            f.setAccessible(true);
            assertNotNull(f.get(controller),
                    field + " is null under production wiring, so its guard is reachable");
        }
    }

    @Test
    void updateDocumentDelegatesToTheMutationService() {
        DocumentUpdateRequest request = new DocumentUpdateRequest();
        DocumentMutationResponse expected = new DocumentMutationResponse(
                1L, "UPDATED", 2L, 1, true, false, false, "NONE", null, null, null);
        when(documentMutationService.updateLocal(eq(1L), any(DocumentUpdateRequest.class)))
                .thenReturn(expected);

        ResponseEntity<DocumentMutationResponse> response =
                controller.updateDocument(1L, request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
    }

    @Test
    void upsertExternalDelegatesToTheExternalDocumentService() {
        ExternalDocumentUpsertRequest request = new ExternalDocumentUpsertRequest();
        // A 13-component record; mocking it keeps this test about the delegation
        // rather than about constructing a fixture nobody reads.
        ExternalDocumentUpsertResponse expected = mock(ExternalDocumentUpsertResponse.class);
        when(externalDocumentService.upsert(request)).thenReturn(expected);

        ResponseEntity<ExternalDocumentUpsertResponse> response =
                controller.upsertExternalDocument(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
        verify(externalDocumentService).upsert(request);
    }

    @Test
    void auditLogServiceIsTheOneTheAuditHelpersUse() throws Exception {
        // The helper is private, which is the point: the guard is a one-liner
        // inside it, and this is the only way to observe which service it holds.
        var audit = RagDocumentController.class
                .getDeclaredMethod("auditCreate", String.class, String.class, String.class);
        audit.setAccessible(true);

        audit.invoke(controller, "DOCUMENT", "7", "created");

        verify(auditLogService).logCreate("DOCUMENT", "7", "created");
    }
}
