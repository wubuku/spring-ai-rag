package com.springairag.core.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 批量嵌入生成服务
 *
 * <p>提供高效的批量向量化处理能力：
 * <ul>
 *   <li>按 batchSize 分批调用 {@link EmbeddingModel#call(EmbeddingRequest)} 批量 API</li>
 *   <li>减少 API 调用次数，避免逐条请求的网络开销</li>
 *   <li>单条失败不阻塞整批，自动降级为逐条重试</li>
 * </ul>
 *
 * <p>适配 Spring AI 1.1.x EmbeddingModel 接口。
 */
@Service
public class EmbeddingBatchService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBatchService.class);

    private static final int DEFAULT_BATCH_SIZE = 10;

    private final EmbeddingModel embeddingModel;
    private final int batchSize;

    @Autowired
    public EmbeddingBatchService(EmbeddingModel embeddingModel) {
        this(embeddingModel, DEFAULT_BATCH_SIZE);
    }

    public EmbeddingBatchService(EmbeddingModel embeddingModel, int batchSize) {
        this.embeddingModel = embeddingModel;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
    }

    /**
     * 批量生成嵌入向量
     *
     * @param texts 文本列表
     * @return 嵌入结果列表（顺序与输入一致）
     */
    public List<EmbeddingResult> createEmbeddingsBatch(List<String> texts) {
        return createEmbeddingsBatch(texts, null);
    }

    /**
     * 批量生成嵌入向量（带进度回调）
     *
     * @param texts            文本列表
     * @param progressCallback 进度回调（可为 null）
     * @return 嵌入结果列表（顺序与输入一致）
     */
    public List<EmbeddingResult> createEmbeddingsBatch(List<String> texts,
                                                        ProgressCallback progressCallback) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<EmbeddingResult> results = new ArrayList<>(texts.size());
        int total = texts.size();

        log.info("Starting batch embedding for {} texts (batchSize={})", total, batchSize);

        for (int i = 0; i < total; i += batchSize) {
            int end = Math.min(i + batchSize, total);
            List<String> batch = texts.subList(i, end);

            processBatch(batch, results);

            if (progressCallback != null) {
                progressCallback.onProgress(end, total);
            }
        }

        long successCount = results.stream().filter(EmbeddingResult::isSuccess).count();
        log.info("Batch embedding completed: {}/{} successful", successCount, total);

        return results;
    }

    /**
     * 处理一个批次：先尝试批量 API，失败则逐条降级
     */
    private void processBatch(List<String> batch, List<EmbeddingResult> results) {
        try {
            EmbeddingRequest request = new EmbeddingRequest(batch, EmbeddingOptions.builder().build());
            EmbeddingResponse response = embeddingModel.call(request);

            List<float[]> embeddings = response.getResults().stream()
                    .map(Embedding::getOutput)
                    .toList();

            for (int j = 0; j < batch.size(); j++) {
                float[] embedding = j < embeddings.size() ? embeddings.get(j) : null;
                if (embedding != null) {
                    results.add(new EmbeddingResult(batch.get(j), embedding, null));
                } else {
                    results.add(new EmbeddingResult(batch.get(j), null, "Batch response missing embedding at index " + j));
                }
            }
        } catch (Exception batchError) { // Resilience: batch → sequential fallback
            log.warn("Batch embedding failed, falling back to sequential: {}", batchError.getMessage());
            // 批量失败，逐条降级
            for (String text : batch) {
                try {
                    float[] embedding = embeddingModel.embed(text);
                    results.add(new EmbeddingResult(text, embedding, null));
                } catch (Exception e) { // Individual item failure, continue batch
                    log.error("Failed to generate embedding: {}", e.getMessage());
                    results.add(new EmbeddingResult(text, null, e.getMessage()));
                }
            }
        }
    }

    /**
     * 嵌入结果
     */
    public static class EmbeddingResult {
        private final String text;
        private final float[] embedding;
        private final String error;

        public EmbeddingResult(String text, float[] embedding, String error) {
            this.text = text;
            this.embedding = embedding;
            this.error = error;
        }

        public String getText() { return text; }

        public float[] getEmbedding() { return embedding; }

        public String getError() { return error; }

        public boolean isSuccess() { return error == null && embedding != null; }
    }

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int completed, int total);
    }
}
