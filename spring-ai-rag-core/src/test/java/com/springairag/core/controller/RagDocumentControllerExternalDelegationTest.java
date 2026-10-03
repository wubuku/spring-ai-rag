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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagDocumentController 的外部文档/版本恢复委托路径。
 *
 * <p>Batch 820 更名与瘦身。这个类原本叫 {@code ...OptionalServiceTailTest}，
 * Javadoc 写着"Batch 577，JaCoCo 驱动"，其中两条用例专门构造一个
 * **不注入任何协作者**的 controller，然后断言
 * {@code IllegalStateException("… service is unavailable")}。
 *
 * <p>那些状态**产品不可能出现**：`DocumentRelocationService` 与
 * `ExternalDocumentService` 都是无条件 {@code @Service}，容器在启动时一定会
 * 装配它们。Batch 820 因此删掉那两条用例——它们钉住的是一个不存在的运行时状态，
 * 而它们存在的原因（覆盖率）写在类注释里，这本身就是不该出现的那种测试。
 *
 * <p>保留下来的三条是**真实的委托契约**：请求确实会到达这些服务。
 * 名字按实际测的东西改了，"optional" 这个前提本身就是假的。
 */
class RagDocumentControllerExternalDelegationTest {

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
