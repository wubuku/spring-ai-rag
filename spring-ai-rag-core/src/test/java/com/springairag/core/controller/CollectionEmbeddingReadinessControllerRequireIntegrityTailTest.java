package com.springairag.core.controller;

import com.springairag.api.dto.CollectionEmbeddingReadinessResponse;
import com.springairag.api.dto.DerivationReadinessPageResponse;
import com.springairag.api.dto.DerivationReadinessResponse;
import com.springairag.core.embeddingjob.EmbeddingJobService;
import com.springairag.core.service.DerivationIntegrityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CollectionEmbeddingReadinessController 委托长尾（Batch 690，
 * JaCoCo 驱动）：readiness 双 service 委派、derivationReadiness
 * 和 derivationDocuments 委托、requireIntegrityService ISE 臂。
 */
class CollectionEmbeddingReadinessControllerRequireIntegrityTailTest {

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
    void requireIntegrityServiceThrowsWhenNull() {
        var controllerWithoutIntegrity =
                new CollectionEmbeddingReadinessController(embeddingJobService);

        assertThrows(IllegalStateException.class,
                () -> controllerWithoutIntegrity.derivationReadiness("kb"));
    }

    @Test
    void derivationDocumentsThrowsWhenIntegrityNull() {
        var controllerWithoutIntegrity =
                new CollectionEmbeddingReadinessController(embeddingJobService);

        assertThrows(IllegalStateException.class,
                () -> controllerWithoutIntegrity.derivationDocuments(
                        "kb", null, 0, 50));
    }

    @Test
    void derivationReadinessDelegates() {
        var expected = mock(DerivationReadinessResponse.class);
        when(derivationIntegrityService.summary("kb")).thenReturn(expected);

        var result = controller.derivationReadiness("kb");

        assertSame(expected, result);
    }

    @Test
    void derivationDocumentsDelegates() {
        var expected = mock(DerivationReadinessPageResponse.class);
        when(derivationIntegrityService.details("kb", null, 0, 50))
                .thenReturn(expected);

        var result = controller.derivationDocuments("kb", null, 0, 50);

        assertSame(expected, result);
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
}
