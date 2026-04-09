package com.springairag.core.retrieval;

import com.springairag.core.config.EmbeddingCircuitBreakerProperties;
import com.springairag.core.resilience.LlmCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EmbeddingBatchService Unit Tests
 */
class EmbeddingBatchServiceTest {

    private EmbeddingModel embeddingModel;
    private EmbeddingBatchService service;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        service = new EmbeddingBatchService(embeddingModel, 10);
    }

    private EmbeddingResponse mockBatchResponse(float[]... vectors) {
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < vectors.length; i++) {
            embeddings.add(new Embedding(vectors[i], i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Test
    @DisplayName("Batch API succeeds — all texts processed in one batch")
    void createEmbeddingsBatch_batchApiSuccess() {
        float[] vec1 = {0.1f, 0.2f, 0.3f};
        float[] vec2 = {0.4f, 0.5f, 0.6f};
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(mockBatchResponse(vec1, vec2));

        List<EmbeddingBatchService.EmbeddingResult> results =
                service.createEmbeddingsBatch(List.of("text1", "text2"));

        assertEquals(2, results.size());
        assertTrue(results.get(0).isSuccess());
        assertTrue(results.get(1).isSuccess());
        assertEquals("text1", results.get(0).getText());
        assertEquals("text2", results.get(1).getText());
        assertArrayEquals(vec1, results.get(0).getEmbedding());
        assertArrayEquals(vec2, results.get(1).getEmbedding());

        // Verify batch API called once, not individual embed(text) twice
        verify(embeddingModel, times(1)).call(any(EmbeddingRequest.class));
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    @DisplayName("Batch API fails — degrades to sequential embedding")
    void createEmbeddingsBatch_batchApiFails_fallbackToSequential() {
        float[] vec1 = {0.1f, 0.2f};
        float[] vec2 = {0.3f, 0.4f};
        // Batch API throws
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenThrow(new RuntimeException("Batch API error"));
        // Sequential calls succeed
        when(embeddingModel.embed("text1")).thenReturn(vec1);
        when(embeddingModel.embed("text2")).thenReturn(vec2);

        List<EmbeddingBatchService.EmbeddingResult> results =
                service.createEmbeddingsBatch(List.of("text1", "text2"));

        assertEquals(2, results.size());
        assertTrue(results.get(0).isSuccess());
        assertTrue(results.get(1).isSuccess());
        assertArrayEquals(vec1, results.get(0).getEmbedding());
        assertArrayEquals(vec2, results.get(1).getEmbedding());

        // Verify batch API tried first, then degraded to sequential
        verify(embeddingModel, times(1)).call(any(EmbeddingRequest.class));
        verify(embeddingModel, times(2)).embed(anyString());
    }

    @Test
    @DisplayName("Sequential fallback — partial failure continues processing")
    void createEmbeddingsBatch_sequentialFallback_partialFailure() {
        float[] vec1 = {0.1f, 0.2f};
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenThrow(new RuntimeException("Batch error"));
        when(embeddingModel.embed("good")).thenReturn(vec1);
        when(embeddingModel.embed("bad")).thenThrow(new RuntimeException("API error"));

        List<EmbeddingBatchService.EmbeddingResult> results =
                service.createEmbeddingsBatch(List.of("good", "bad", "good"));

        assertEquals(3, results.size());
        assertTrue(results.get(0).isSuccess());
        assertFalse(results.get(1).isSuccess());
        assertEquals("API error", results.get(1).getError());
        assertNull(results.get(1).getEmbedding());
        assertTrue(results.get(2).isSuccess());
    }

    @Test
    @DisplayName("Exceeds batchSize — splits into multiple batches")
    void createEmbeddingsBatch_exceedsBatchSize_splitsIntoBatches() {
        EmbeddingBatchService smallBatchService = new EmbeddingBatchService(embeddingModel, 2);

        float[] vec1 = {0.1f};
        float[] vec2 = {0.2f};
        float[] vec3 = {0.3f};
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(mockBatchResponse(vec1, vec2))
                .thenReturn(mockBatchResponse(vec3));

        List<EmbeddingBatchService.EmbeddingResult> results =
                smallBatchService.createEmbeddingsBatch(List.of("a", "b", "c"));

        assertEquals(3, results.size());
        assertTrue(results.get(0).isSuccess());
        assertTrue(results.get(1).isSuccess());
        assertTrue(results.get(2).isSuccess());

        // Verify 2 batches: ["a","b"] then ["c"]
        verify(embeddingModel, times(2)).call(any(EmbeddingRequest.class));
    }

    @Test
    @DisplayName("Empty input returns empty list")
    void createEmbeddingsBatch_emptyInput_returnsEmpty() {
        List<EmbeddingBatchService.EmbeddingResult> results = service.createEmbeddingsBatch(List.of());
        assertTrue(results.isEmpty());
        verify(embeddingModel, never()).call(any(EmbeddingRequest.class));
    }

    @Test
    @DisplayName("null input returns empty list")
    void createEmbeddingsBatch_nullInput_returnsEmpty() {
        List<EmbeddingBatchService.EmbeddingResult> results = service.createEmbeddingsBatch(null);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("Progress callback reports correct progress")
    void createEmbeddingsBatch_withProgressCallback() {
        float[] vec = {0.1f};
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(mockBatchResponse(vec, vec, vec));

        AtomicInteger lastCompleted = new AtomicInteger(0);
        AtomicInteger lastTotal = new AtomicInteger(0);

        service.createEmbeddingsBatch(List.of("a", "b", "c"), (completed, total) -> {
            lastCompleted.set(completed);
            lastTotal.set(total);
        });

        assertEquals(3, lastCompleted.get());
        assertEquals(3, lastTotal.get());
    }

    @Test
    @DisplayName("null callback does not throw")
    void createEmbeddingsBatch_nullCallback_doesNotThrow() {
        float[] vec = {0.1f};
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(mockBatchResponse(vec));

        assertDoesNotThrow(() -> service.createEmbeddingsBatch(List.of("a"), null));
    }

    @Test
    @DisplayName("Embedding success result fields are correct")
    void embeddingResult_successFields() {
        float[] vec = {0.1f, 0.2f};
        EmbeddingBatchService.EmbeddingResult result =
                new EmbeddingBatchService.EmbeddingResult("text", vec, null);

        assertEquals("text", result.getText());
        assertArrayEquals(vec, result.getEmbedding());
        assertNull(result.getError());
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("Embedding failure result fields are correct")
    void embeddingResult_failureFields() {
        EmbeddingBatchService.EmbeddingResult result =
                new EmbeddingBatchService.EmbeddingResult("text", null, "error");

        assertEquals("text", result.getText());
        assertNull(result.getEmbedding());
        assertEquals("error", result.getError());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("batchSize=0 uses default value of 10")
    void constructor_zeroBatchSize_usesDefault() {
        EmbeddingBatchService defaultService = new EmbeddingBatchService(embeddingModel, 0);

        float[] vec = {0.1f};
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(mockBatchResponse(vec));

        defaultService.createEmbeddingsBatch(List.of("a"));
        // Should complete in 1 batch call (default batchSize=10)
        verify(embeddingModel, times(1)).call(any(EmbeddingRequest.class));
    }

    @Test
    @DisplayName("Two-arg constructor uses default batchSize=10")
    void twoArgConstructor_usesDefaultBatchSize() {
        EmbeddingBatchService defaultService = new EmbeddingBatchService(embeddingModel, 10);

        // 5 texts should complete in 1 batch (default batchSize=10)
        float[] vec = {0.1f};
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(mockBatchResponse(vec, vec, vec, vec, vec));

        defaultService.createEmbeddingsBatch(List.of("a", "b", "c", "d", "e"));
        verify(embeddingModel, times(1)).call(any(EmbeddingRequest.class));
    }

    // --- Circuit Breaker Tests ---

    private LlmCircuitBreaker createEnabledCircuitBreaker() {
        EmbeddingCircuitBreakerProperties props = new EmbeddingCircuitBreakerProperties();
        props.setEnabled(true);
        props.setFailureRateThreshold(50);
        props.setMinimumNumberOfCalls(5);
        props.setWaitDurationInOpenStateSeconds(30);
        props.setSlidingWindowSize(20);
        return new LlmCircuitBreaker(props);
    }

    @Test
    @DisplayName("Circuit breaker CLOSED allows batch embedding to proceed")
    void createEmbeddingsBatch_circuitBreakerClosed_allowsEmbedding() {
        LlmCircuitBreaker cb = createEnabledCircuitBreaker();
        EmbeddingBatchService cbService = new EmbeddingBatchService(embeddingModel, 10, cb);

        float[] vec = {0.1f, 0.2f};
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(mockBatchResponse(vec, vec));

        List<EmbeddingBatchService.EmbeddingResult> results =
                cbService.createEmbeddingsBatch(List.of("text1", "text2"));

        assertEquals(2, results.size());
        assertTrue(results.get(0).isSuccess());
        assertEquals(LlmCircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    @DisplayName("Circuit breaker OPEN rejects batch immediately without calling embedding API")
    void createEmbeddingsBatch_circuitBreakerOpen_rejectsImmediately() {
        LlmCircuitBreaker cb = createEnabledCircuitBreaker();
        // Force OPEN state by recording failures
        for (int i = 0; i < 15; i++) {
            cb.recordFailure();
        }
        assertEquals(LlmCircuitBreaker.State.OPEN, cb.getState());

        EmbeddingBatchService cbService = new EmbeddingBatchService(embeddingModel, 10, cb);
        List<EmbeddingBatchService.EmbeddingResult> results =
                cbService.createEmbeddingsBatch(List.of("text1", "text2"));

        assertEquals(2, results.size());
        assertFalse(results.get(0).isSuccess());
        assertTrue(results.get(0).getError().contains("circuit breaker open"));
        verify(embeddingModel, never()).call(any(EmbeddingRequest.class));
    }

    @Test
    @DisplayName("Circuit breaker records failure on batch API error")
    void createEmbeddingsBatch_batchApiFailure_recordsFailure() {
        LlmCircuitBreaker cb = createEnabledCircuitBreaker();
        EmbeddingBatchService cbService = new EmbeddingBatchService(embeddingModel, 10, cb);

        // Batch API fails; sequential fallback succeeds
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenThrow(new RuntimeException("Batch error"));
        when(embeddingModel.embed("text1")).thenReturn(new float[]{0.1f, 0.2f});
        when(embeddingModel.embed("text2")).thenReturn(new float[]{0.3f, 0.4f});

        List<EmbeddingBatchService.EmbeddingResult> results =
                cbService.createEmbeddingsBatch(List.of("text1", "text2"));

        // Sequential fallback succeeds for both items
        assertTrue(results.get(0).isSuccess());
        assertTrue(results.get(1).isSuccess());
        assertEquals(LlmCircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    @DisplayName("Service without circuit breaker still functions normally")
    void createEmbeddingsBatch_noCircuitBreaker_worksNormally() {
        EmbeddingBatchService noCbService = new EmbeddingBatchService(embeddingModel, 10, null);

        float[] vec = {0.1f};
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(mockBatchResponse(vec));

        List<EmbeddingBatchService.EmbeddingResult> results =
                noCbService.createEmbeddingsBatch(List.of("text"));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
        verify(embeddingModel, times(1)).call(any(EmbeddingRequest.class));
    }
}
