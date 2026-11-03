package com.springairag.core.retrieval;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileModelResolver;
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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
    private final EmbeddingProfileModelResolver profileModelResolver;
    private final int batchSize;
    private final LlmCircuitBreaker circuitBreaker;

    @Autowired
    public EmbeddingBatchService(
            EmbeddingModel embeddingModel,
            RagProperties ragProperties,
            EmbeddingProfileModelResolver profileModelResolver) {
        this(embeddingModel, DEFAULT_BATCH_SIZE,
                ragProperties.getEmbeddingCircuitBreaker().isEnabled()
                        ? new LlmCircuitBreaker(ragProperties.getEmbeddingCircuitBreaker())
                        : null,
                profileModelResolver);
    }

    public EmbeddingBatchService(EmbeddingModel embeddingModel, RagProperties ragProperties) {
        this(embeddingModel, ragProperties, null);
    }

    public EmbeddingBatchService(EmbeddingModel embeddingModel, int batchSize) {
        this(embeddingModel, batchSize, null, null);
    }

    public EmbeddingBatchService(EmbeddingModel embeddingModel, int batchSize, LlmCircuitBreaker circuitBreaker) {
        this(embeddingModel, batchSize, circuitBreaker, null);
    }

    private EmbeddingBatchService(
            EmbeddingModel embeddingModel,
            int batchSize,
            LlmCircuitBreaker circuitBreaker,
            EmbeddingProfileModelResolver profileModelResolver) {
        this.embeddingModel = embeddingModel;
        this.profileModelResolver = profileModelResolver;
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

    public List<EmbeddingResult> createEmbeddingsBatchForProfile(
            List<String> texts, EmbeddingProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Embedding Profile is required");
        }
        EmbeddingModel selectedModel = profileModelResolver == null
                ? requireLegacyProfile(profile)
                : profileModelResolver.resolve(profile);
        return createEmbeddingsBatch(texts, null, selectedModel, profile.provider());
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
        return createEmbeddingsBatch(texts, progressCallback, embeddingModel,
                embeddingModel.getClass().getSimpleName());
    }

    private List<EmbeddingResult> createEmbeddingsBatch(
            List<String> texts,
            ProgressCallback progressCallback,
            EmbeddingModel selectedModel,
            String provider) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<EmbeddingResult> results = new ArrayList<>(texts.size());
        int total = texts.size();

        log.info("Starting batch embedding for {} texts (batchSize={})", total, batchSize);

        for (int i = 0; i < total; i += batchSize) {
            int end = Math.min(i + batchSize, total);
            List<String> batch = texts.subList(i, end);

            processBatch(batch, results, selectedModel, provider);

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
     *
     * <p>顺序回退只用于**可能由单个输入导致**的失败（UNKNOWN/REJECTED，例如某一段
     * 超长或含非法字符）。对 TRANSIENT（429/5xx/连接失败/超时）一律不再逐条重发：
     * provider 整体不可用时，逐条回退会把 1 次批量调用放大成 1 + N 次（每条还会
     * 各自重试），既烧额度也不改变结果。此时整批直接以同一分类失败。
     */
    private void processBatch(
            List<String> batch, List<EmbeddingResult> results, EmbeddingModel selectedModel,
            String provider) {
        // Circuit breaker: reject immediately if OPEN
        if (circuitBreaker != null && !circuitBreaker.allowCall()) {
            log.warn("Embedding circuit breaker is OPEN, rejecting batch of {} items", batch.size());
            for (String text : batch) {
                results.add(new EmbeddingResult(text, null,
                        "Embedding service unavailable (circuit breaker open)",
                        EmbeddingFailureClassifier.Kind.TRANSIENT));
            }
            return;
        }

        try {
            EmbeddingRequest request = new EmbeddingRequest(batch, EmbeddingOptions.builder().build());
            EmbeddingResponse response = selectedModel.call(request);

            List<float[]> embeddings = response.getResults().stream()
                    .map(Embedding::getOutput)
                    .toList();

            for (int j = 0; j < batch.size(); j++) {
                float[] embedding = j < embeddings.size() ? embeddings.get(j) : null;
                if (embedding != null) {
                    results.add(new EmbeddingResult(batch.get(j), embedding, null));
                } else {
                    results.add(new EmbeddingResult(batch.get(j), null, "Batch response missing embedding at index " + j,
                            EmbeddingFailureClassifier.Kind.NONE));
                }
            }
            if (circuitBreaker != null) {
                circuitBreaker.recordSuccess();
            }
        } catch (Exception batchError) { // Resilience: batch → sequential fallback
            if (batchError instanceof com.springairag.core.admission.DependencyCallException
                    || batchError instanceof com.springairag.core.admission.AdmissionRejectedException) {
                throw (RuntimeException) batchError;
            }
            var failureKind = EmbeddingFailureClassifier.classify(batchError);
            var details = com.springairag.core.resilience.UpstreamFailureClassifier
                    .classify(batchError);
            if (circuitBreaker != null) {
                circuitBreaker.recordFailure();
            }
            // 全局上游失败（5xx/429/超时/连接/鉴权/畸形响应）对整批同样成立，
            // 逐条重发只会在坏 provider 上放大调用量。
            if (failureKind == EmbeddingFailureClassifier.Kind.TRANSIENT
                    || "UPSTREAM".equals(details.origin())) {
                log.warn("Batch embedding failed without sequential fan-out operation=embedding "
                                + "provider={} reason={} retryable={} items={}",
                        provider, details.reason(), details.retryable(), batch.size());
                for (String text : batch) {
                    results.add(new EmbeddingResult(text, null, batchError.getMessage(),
                            failureKind, details));
                }
                return;
            }
            log.warn("Batch embedding failed (cb={}), falling back to sequential code={} "
                            + "operation=embedding provider={}",
                    circuitBreaker != null ? circuitBreaker.getState() : "disabled",
                    failureKind, provider);
            for (String text : batch) {
                try {
                    float[] embedding = selectedModel.embed(text);
                    results.add(new EmbeddingResult(text, embedding, null));
                    if (circuitBreaker != null) {
                        circuitBreaker.recordSuccess();
                    }
                } catch (Exception e) { // Individual item failure, continue batch
                    var itemKind = EmbeddingFailureClassifier.classify(e);
                    var itemDetails = com.springairag.core.resilience.UpstreamFailureClassifier
                            .classify(e);
                    log.error("Failed to generate embedding code={} operation=embedding provider={} "
                                    + "reason={} retryable={}",
                            itemKind, provider, itemDetails.reason(), itemDetails.retryable());
                    results.add(new EmbeddingResult(text, null, e.getMessage(), itemKind,
                            itemDetails));
                    if (circuitBreaker != null) {
                        circuitBreaker.recordFailure();
                    }
                }
            }
        }
    }

    private EmbeddingModel requireLegacyProfile(EmbeddingProfile profile) {
        if (profileModelResolver == null) {
            throw new IllegalStateException(
                    "Profile-specific model routing is not configured for "
                            + profile.profileKey());
        }
        return profileModelResolver.resolve(profile);
    }

    /**
     * Embedding result.
     */
    public static class EmbeddingResult {
        private final String text;
        private final float[] embedding;
        private final String error;
        @JsonIgnore
        private final EmbeddingFailureClassifier.Kind failureKind;
        /**
         * 导致整批/单条失败的 provider 分类，供上层返回
         * {@code X-RAG-Failure-Class} 与 {@code failureDetails}。
         * 只在已知上游原因时非空，避免用错误文案猜分类。
         */
        @JsonIgnore
        private final com.springairag.api.dto.FailureDetails failureDetails;

        public EmbeddingResult(String text, float[] embedding, String error) {
            this(text, embedding, error, error == null
                    ? EmbeddingFailureClassifier.Kind.NONE : EmbeddingFailureClassifier.Kind.UNKNOWN);
        }

        public EmbeddingResult(String text, float[] embedding, String error,
                EmbeddingFailureClassifier.Kind failureKind) {
            this(text, embedding, error, failureKind, null);
        }

        public EmbeddingResult(String text, float[] embedding, String error,
                EmbeddingFailureClassifier.Kind failureKind,
                com.springairag.api.dto.FailureDetails failureDetails) {
            this.text = text;
            this.embedding = embedding;
            this.error = error;
            this.failureKind = failureKind == null ? EmbeddingFailureClassifier.Kind.UNKNOWN : failureKind;
            this.failureDetails = failureDetails;
        }

        public String getText() { return text; }

        public float[] getEmbedding() { return embedding; }

        public String getError() { return error; }
        @JsonIgnore
        public EmbeddingFailureClassifier.Kind getFailureKind() { return failureKind; }
        @JsonIgnore
        public com.springairag.api.dto.FailureDetails getFailureDetails() { return failureDetails; }

        public boolean isSuccess() { return error == null && embedding != null; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EmbeddingResult that = (EmbeddingResult) o;
            return Arrays.equals(embedding, that.embedding)
                    && Objects.equals(text, that.text)
                    && Objects.equals(error, that.error);
        }

        @Override
        public int hashCode() {
            return Objects.hash(text, Arrays.hashCode(embedding), error);
        }

        @Override
        public String toString() {
            String embedingSummary = embedding == null ? "null" : "float[" + embedding.length + "]";
            return "EmbeddingResult{text=\"" + text + "\", embedding=" + embedingSummary + ", error=\"" + error + "\"}";
        }
    }

    /**
     * Progress callback interface.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int completed, int total);
    }
}
