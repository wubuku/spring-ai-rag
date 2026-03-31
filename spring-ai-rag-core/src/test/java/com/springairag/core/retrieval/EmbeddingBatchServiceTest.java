package com.springairag.core.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * EmbeddingBatchService 单元测试
 */
class EmbeddingBatchServiceTest {

    private EmbeddingModel embeddingModel;
    private EmbeddingBatchService service;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        service = new EmbeddingBatchService(embeddingModel);
    }

    @Test
    void createEmbeddingsBatch_success() {
        float[] vec1 = {0.1f, 0.2f, 0.3f};
        float[] vec2 = {0.4f, 0.5f, 0.6f};
        when(embeddingModel.embed("text1")).thenReturn(vec1);
        when(embeddingModel.embed("text2")).thenReturn(vec2);

        List<EmbeddingBatchService.EmbeddingResult> results =
                service.createEmbeddingsBatch(List.of("text1", "text2"));

        assertEquals(2, results.size());
        assertTrue(results.get(0).isSuccess());
        assertTrue(results.get(1).isSuccess());
        assertEquals("text1", results.get(0).getText());
        assertEquals("text2", results.get(1).getText());
        assertArrayEquals(vec1, results.get(0).getEmbedding());
        assertArrayEquals(vec2, results.get(1).getEmbedding());
    }

    @Test
    void createEmbeddingsBatch_emptyInput_returnsEmpty() {
        List<EmbeddingBatchService.EmbeddingResult> results = service.createEmbeddingsBatch(List.of());
        assertTrue(results.isEmpty());
    }

    @Test
    void createEmbeddingsBatch_nullInput_returnsEmpty() {
        List<EmbeddingBatchService.EmbeddingResult> results = service.createEmbeddingsBatch(null);
        assertTrue(results.isEmpty());
    }

    @Test
    void createEmbeddingsBatch_partialFailure_continuesProcessing() {
        float[] vec1 = {0.1f, 0.2f};
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
    void createEmbeddingsBatch_withProgressCallback() {
        float[] vec = {0.1f};
        when(embeddingModel.embed(anyString())).thenReturn(vec);

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
    void createEmbeddingsBatch_nullCallback_doesNotThrow() {
        float[] vec = {0.1f};
        when(embeddingModel.embed(anyString())).thenReturn(vec);

        assertDoesNotThrow(() -> service.createEmbeddingsBatch(List.of("a"), null));
    }

    @Test
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
    void embeddingResult_failureFields() {
        EmbeddingBatchService.EmbeddingResult result =
                new EmbeddingBatchService.EmbeddingResult("text", null, "error");

        assertEquals("text", result.getText());
        assertNull(result.getEmbedding());
        assertEquals("error", result.getError());
        assertFalse(result.isSuccess());
    }
}
