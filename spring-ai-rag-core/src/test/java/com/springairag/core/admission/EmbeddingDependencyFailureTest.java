package com.springairag.core.admission;

import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.retrieval.EmbeddingFailureClassifier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmbeddingDependencyFailureTest {
    @ParameterizedTest
    @ValueSource(strings = {"DEPENDENCY_TIMEOUT", "DEPENDENCY_CANCELLED", "CAPACITY"})
    void deadlineCancelAndCapacityNeverFanOutSequentialFallback(String code) {
        EmbeddingModel model = mock(EmbeddingModel.class);
        RuntimeException error = code.equals("CAPACITY") ? new AdmissionRejectedException() : new DependencyCallException(code);
        when(model.call(any(EmbeddingRequest.class))).thenThrow(error);
        var service = new EmbeddingBatchService(model, 10);
        assertSame(error, assertThrows(RuntimeException.class,
                () -> service.createEmbeddingsBatch(List.of("one", "two"))));
        verify(model, times(1)).call(any(EmbeddingRequest.class));
        verify(model, never()).embed(any(String.class));
        assertEquals(EmbeddingFailureClassifier.Kind.TRANSIENT, EmbeddingFailureClassifier.classify(error));
    }
}
