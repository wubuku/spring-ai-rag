package com.springairag.core.retrieval;

import com.springairag.core.config.RagProperties;
import com.springairag.core.resilience.LlmCircuitBreaker;
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
import java.util.List;

/**
 * Batch embedding generation service.
 *
 * <p>Provides efficient batch vectorization:
 * <ul>
 *   <li>Calls {@link EmbeddingModel#call(EmbeddingRequest)} in batches of batchSize</li>
 *   <li>Reduces API call count, avoiding per-item network overhead</li>
 *   <li>Individual failures do not block the batch; automatic degradation to sequential retry</li>
 *   <li>Circuit breaker prevents cascading failures when the embedding API is unhealthy</li>
 * </ul>
 *
 * <p>Compatible with Spring AI 1.1.x EmbeddingModel interface.
 */
@Service
public class EmbeddingBatchService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBatchService.class);

    private static final int DEFAULT_BATCH_SIZE = 10;

    private final EmbeddingModel embeddingModel;
    private final int batchSize;
    private final LlmCircuitBreaker circuitBreaker;

    @Autowired
    public EmbeddingBatchService(EmbeddingModel embeddingModel, RagProperties ragProperties) {
        this(embeddingModel, DEFAULT_BATCH_SIZE,
                ragProperties.getEmbeddingCircuitBreaker().isEnabled()
                        ? new LlmCircuitBreaker(ragProperties.getEmbeddingCircuitBreaker())
                        : null);
    }

    public EmbeddingBatchService(EmbeddingModel embeddingModel, int batchSize) {
        this(embeddingModel, batchSize, null);
    }

    public EmbeddingBatchService(EmbeddingModel embeddingModel, int batchSize, LlmCircuitBreaker circuitBreaker) {
        this.embeddingModel = embeddingModel;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        this.circuitBreaker = circuitBreaker;
        if (this.circuitBreaker != null) {
            log.info("Embedding circuit breaker enabled: {}", this.circuitBreaker.getStats());
        }
    }

    /**
     * Generate embeddings for a list of texts.
     *
     * @param texts the text list
     * @return embedding results (order matches input)
     */
    public List<EmbeddingResult> createEmbeddingsBatch(List<String> texts) {
        return createEmbeddingsBatch(texts, null);
    }

    /**
     * Generate embeddings with a progress callback.
     *
     * @param texts            the text list
     * @param progressCallback progress callback (may be null)
     * @return embedding results (order matches input)
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
     * Process one batch: circuit breaker check → try batch API → sequential fallback on failure.
     */
    private void processBatch(List<String> batch, List<EmbeddingResult> results) {
        // Circuit breaker: reject immediately if OPEN
        if (circuitBreaker != null && !circuitBreaker.allowCall()) {
            log.warn("Embedding circuit breaker is OPEN, rejecting batch of {} items", batch.size());
            for (String text : batch) {
                results.add(new EmbeddingResult(text, null,
                        "Embedding service unavailable (circuit breaker open)"));
            }
            return;
        }

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
            if (circuitBreaker != null) {
                circuitBreaker.recordSuccess();
            }
        } catch (Exception batchError) { // Resilience: batch → sequential fallback
            if (circuitBreaker != null) {
                circuitBreaker.recordFailure();
            }
            log.warn("Batch embedding failed (cb={}), falling back to sequential: {}",
                    circuitBreaker != null ? circuitBreaker.getState() : "disabled",
                    batchError.getMessage());
            for (String text : batch) {
                try {
                    float[] embedding = embeddingModel.embed(text);
                    results.add(new EmbeddingResult(text, embedding, null));
                    if (circuitBreaker != null) {
                        circuitBreaker.recordSuccess();
                    }
                } catch (Exception e) { // Individual item failure, continue batch
                    log.error("Failed to generate embedding: {}", e.getMessage());
                    results.add(new EmbeddingResult(text, null, e.getMessage()));
                    if (circuitBreaker != null) {
                        circuitBreaker.recordFailure();
                    }
                }
            }
        }
    }

    /**
     * Embedding result.
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
     * Progress callback interface.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int completed, int total);
    }
}
