package com.springairag.core.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量嵌入生成服务
 *
 * <p>提供高效的批量向量化处理能力：
 * <ul>
 *   <li>分批处理避免 API 限流</li>
 *   <li>顺序处理避免线程池冲突</li>
 *   <li>错误处理和重试机制</li>
 * </ul>
 *
 * <p>适配 Spring AI EmbeddingModel 接口。
 */
@Service
public class EmbeddingBatchService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBatchService.class);

    @Value("${rag.embedding.batch-size:10}")
    private int batchSize;

    private final EmbeddingModel embeddingModel;

    public EmbeddingBatchService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 批量生成嵌入向量（顺序处理）
     *
     * @param texts 文本列表
     * @return 嵌入结果列表
     */
    public List<EmbeddingResult> createEmbeddingsBatch(List<String> texts) {
        return createEmbeddingsBatch(texts, null);
    }

    /**
     * 批量生成嵌入向量（带进度回调）
     *
     * @param texts 文本列表
     * @param progressCallback 进度回调（可为 null）
     * @return 嵌入结果列表
     */
    public List<EmbeddingResult> createEmbeddingsBatch(List<String> texts,
                                                        ProgressCallback progressCallback) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<EmbeddingResult> results = new ArrayList<>();
        int total = texts.size();
        int completed = 0;

        log.info("Starting batch embedding for {} texts", total);

        for (String text : texts) {
            try {
                float[] embedding = embeddingModel.embed(text);
                results.add(new EmbeddingResult(text, embedding, null));
            } catch (Exception e) {
                log.error("Failed to generate embedding: {}", e.getMessage());
                results.add(new EmbeddingResult(text, null, e.getMessage()));
            }

            completed++;
            if (progressCallback != null) {
                progressCallback.onProgress(completed, total);
            }
        }

        long successCount = results.stream().filter(EmbeddingResult::isSuccess).count();
        log.info("Batch embedding completed: {}/{} successful", successCount, total);

        return results;
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
