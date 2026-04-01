package com.springairag.core.retrieval;

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
 * EmbeddingBatchService 单元测试
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
    @DisplayName("批量 API 调用成功——一次性处理所有文本")
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

        // 验证只调用了一次批量 API，而非两次 embed(text)
        verify(embeddingModel, times(1)).call(any(EmbeddingRequest.class));
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    @DisplayName("批量 API 失败时降级为逐条调用")
    void createEmbeddingsBatch_batchApiFails_fallbackToSequential() {
        float[] vec1 = {0.1f, 0.2f};
        float[] vec2 = {0.3f, 0.4f};
        // 批量 API 抛异常
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenThrow(new RuntimeException("Batch API error"));
        // 逐条调用成功
        when(embeddingModel.embed("text1")).thenReturn(vec1);
        when(embeddingModel.embed("text2")).thenReturn(vec2);

        List<EmbeddingBatchService.EmbeddingResult> results =
                service.createEmbeddingsBatch(List.of("text1", "text2"));

        assertEquals(2, results.size());
        assertTrue(results.get(0).isSuccess());
        assertTrue(results.get(1).isSuccess());
        assertArrayEquals(vec1, results.get(0).getEmbedding());
        assertArrayEquals(vec2, results.get(1).getEmbedding());

        // 验证尝试了批量 API，然后降级到逐条
        verify(embeddingModel, times(1)).call(any(EmbeddingRequest.class));
        verify(embeddingModel, times(2)).embed(anyString());
    }

    @Test
    @DisplayName("逐条降级时部分失败继续处理")
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
    @DisplayName("超过 batchSize 时分批调用批量 API")
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

        // 验证分了 2 批：第一批 ["a","b"]，第二批 ["c"]
        verify(embeddingModel, times(2)).call(any(EmbeddingRequest.class));
    }

    @Test
    @DisplayName("空输入返回空列表")
    void createEmbeddingsBatch_emptyInput_returnsEmpty() {
        List<EmbeddingBatchService.EmbeddingResult> results = service.createEmbeddingsBatch(List.of());
        assertTrue(results.isEmpty());
        verify(embeddingModel, never()).call(any(EmbeddingRequest.class));
    }

    @Test
    @DisplayName("null 输入返回空列表")
    void createEmbeddingsBatch_nullInput_returnsEmpty() {
        List<EmbeddingBatchService.EmbeddingResult> results = service.createEmbeddingsBatch(null);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("进度回调报告正确进度")
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
    @DisplayName("null 回调不抛异常")
    void createEmbeddingsBatch_nullCallback_doesNotThrow() {
        float[] vec = {0.1f};
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(mockBatchResponse(vec));

        assertDoesNotThrow(() -> service.createEmbeddingsBatch(List.of("a"), null));
    }

    @Test
    @DisplayName("嵌入成功结果字段正确")
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
    @DisplayName("嵌入失败结果字段正确")
    void embeddingResult_failureFields() {
        EmbeddingBatchService.EmbeddingResult result =
                new EmbeddingBatchService.EmbeddingResult("text", null, "error");

        assertEquals("text", result.getText());
        assertNull(result.getEmbedding());
        assertEquals("error", result.getError());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("batchSize=0 使用默认值 10")
    void constructor_zeroBatchSize_usesDefault() {
        EmbeddingBatchService defaultService = new EmbeddingBatchService(embeddingModel, 0);

        float[] vec = {0.1f};
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(mockBatchResponse(vec));

        defaultService.createEmbeddingsBatch(List.of("a"));
        // 应该一次批量调用处理完（batchSize 默认为 10）
        verify(embeddingModel, times(1)).call(any(EmbeddingRequest.class));
    }

    @Test
    @DisplayName("两参数构造函数使用默认 batchSize=10")
    void twoArgConstructor_usesDefaultBatchSize() {
        EmbeddingBatchService defaultService = new EmbeddingBatchService(embeddingModel);

        // 5 个文本应该在 1 批内处理完（默认 batchSize=10）
        float[] vec = {0.1f};
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(mockBatchResponse(vec, vec, vec, vec, vec));

        defaultService.createEmbeddingsBatch(List.of("a", "b", "c", "d", "e"));
        verify(embeddingModel, times(1)).call(any(EmbeddingRequest.class));
    }
}
