package com.springairag.core.controller;

import com.springairag.api.dto.CollectionEmbeddingReadinessResponse;
import com.springairag.api.dto.DerivationReadinessPageResponse;
import com.springairag.api.dto.DerivationReadinessResponse;
import com.springairag.core.embeddingjob.EmbeddingJobService;
import com.springairag.core.service.DerivationIntegrityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CollectionEmbeddingReadinessController 委托长尾（Batch 688，
 * JaCoCo 驱动）：readiness 的双 service 委派、derivationReadiness
 * 和 derivationDocuments 的委托路径。
 */
class CollectionEmbeddingReadinessControllerTailTest {

    private EmbeddingJobService embeddingJobService;
    private DerivationIntegrityService derivationIntegrityService;
    private CollectionEmbeddingReadinessController controller;

    @BeforeEach
    void setUp() {
        embeddingJobService = mock(EmbeddingJobService.class);
        derivationIntegrityService = mock(DerivationIntegrityService.class);
        controller = new CollectionEmbeddingReadinessController(
                embeddingJobService);
        controller.setDerivationIntegrityService(derivationIntegrityService);
    }

    @Test
    void readinessDelegatesToIntegrityServiceWhenPresent() {
        var expected = mock(CollectionEmbeddingReadinessResponse.class);
        when(derivationIntegrityService.embeddingReadiness("kb"))
                .thenReturn(expected);

        var result = controller.readiness("kb");

        assertSame(expected, result);
    }

    @Test
    void readinessDelegatesToEmbeddingJobServiceWhenIntegrityNull() {
        var controllerWithoutIntegrity =
                new CollectionEmbeddingReadinessController(embeddingJobService);
        var expected = mock(CollectionEmbeddingReadinessResponse.class);
        when(embeddingJobService.readiness("kb")).thenReturn(expected);

        var result = controllerWithoutIntegrity.readiness("kb");

        assertSame(expected, result);
    }

    @Test
    void derivationReadinessDelegatesToIntegrityService() {
        var expected = mock(DerivationReadinessResponse.class);
        when(derivationIntegrityService.summary("kb")).thenReturn(expected);

        var result = controller.derivationReadiness("kb");

        assertSame(expected, result);
    }

    @Test
    void derivationDocumentsDelegatesToIntegrityService() {
        var expected = mock(DerivationReadinessPageResponse.class);
        when(derivationIntegrityService.details("kb", null, 0, 50))
                .thenReturn(expected);

        var result = controller.derivationDocuments("kb", null, 0, 50);

        assertSame(expected, result);
    }
}
