package com.springairag.core.service;

import com.springairag.core.config.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Multi-Model Parallel Comparison Service
 *
 * <p>Sends the same query to multiple models, collects response content and latency data,
 * used for model effectiveness comparison and pre-A/B experiment data collection.
 */
@Service
public class ModelComparisonService {

    private static final Logger log = LoggerFactory.getLogger(ModelComparisonService.class);

    private final ExecutorService modelComparisonExecutor;
    private final ModelRegistry modelRegistry;

    public ModelComparisonService(
            @org.springframework.beans.factory.annotation.Qualifier("modelComparisonExecutor") ExecutorService modelComparisonExecutor,
            ModelRegistry modelRegistry) {
        this.modelComparisonExecutor = modelComparisonExecutor;
        this.modelRegistry = modelRegistry;
    }

    /**
     * Compare multiple models in parallel
     *
     * <p>Uses shared thread pool, avoids creating new ExecutorService for each call.
     * Single model timeout or exception does not affect other models; result degrades to failure record.
     *
     * @param query   query text
     * @param models  model name → ChatModel mapping
     * @param timeoutSeconds per-model timeout (seconds)
     * @return comparison result list (in submission order)
     */
    public List<ModelComparisonResult> compareModels(String query,
                                                      Map<String, ChatModel> models,
                                                      int timeoutSeconds) {
        if (models == null || models.isEmpty()) {
            return List.of();
        }
        log.info("Comparing {} models with query: {}", models.size(),
                query.length() > 50 ? query.substring(0, 50) + "..." : query);

        Map<String, Future<ModelComparisonResult>> futures = new LinkedHashMap<>();

        for (Map.Entry<String, ChatModel> entry : models.entrySet()) {
            String modelName = entry.getKey();
            ChatModel model = entry.getValue();
            futures.put(modelName, submitQuery(modelName, model, query));
        }

        List<ModelComparisonResult> results = new ArrayList<>(futures.size());
        for (Map.Entry<String, Future<ModelComparisonResult>> entry : futures.entrySet()) {
            try {
                results.add(entry.getValue().get(timeoutSeconds, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupt status
                log.error("Model {} comparison interrupted: {}", entry.getKey(), e.getMessage());
                results.add(ModelComparisonResult.failure(entry.getKey(), "Interrupted: " + e.getMessage()));
            } catch (ExecutionException | TimeoutException e) {
                log.error("Model {} failed: {}", entry.getKey(), e.getMessage());
                results.add(ModelComparisonResult.failure(entry.getKey(), e.getMessage()));
            }
        }

        return results;
    }

    /**
     * Compare multiple providers in parallel (resolved via ModelRegistry)
     *
     * @param query query text
     * @param providers provider list to compare (e.g., ["openai", "minimax"])
     * @param timeoutSeconds per-model timeout (seconds)
     * @return comparison result list (in submission order)
     */
    public List<ModelComparisonResult> compareProviders(String query,
                                                       List<String> providers,
                                                       int timeoutSeconds) {
        Map<String, ChatModel> models = new LinkedHashMap<>();
        for (String p : providers) {
            try {
                models.put(p, modelRegistry.get(p));
            } catch (IllegalArgumentException e) {
                log.warn("Provider not available for comparison: {}", p);
            }
        }
        return compareModels(query, models, timeoutSeconds);
    }

    /**
     * Compare all registered providers
     */
    public List<ModelComparisonResult> compareAllProviders(String query, int timeoutSeconds) {
        Map<String, ChatModel> models = new LinkedHashMap<>();
        for (String p : modelRegistry.availableProviders()) {
            models.put(p, modelRegistry.get(p));
        }
        return compareModels(query, models, timeoutSeconds);
    }

    private Future<ModelComparisonResult> submitQuery(String modelName, ChatModel model, String query) {
        return modelComparisonExecutor.submit(() -> queryModel(modelName, model, query));
    }

    private ModelComparisonResult queryModel(String modelName, ChatModel model, String query) {
        Instant start = Instant.now();
        try {
            Prompt prompt = new Prompt(query);
            ChatResponse response = model.call(prompt);
            Duration elapsed = Duration.between(start, Instant.now());

            String content = "";
            if (response.getResult() != null && response.getResult().getOutput() != null) {
                content = response.getResult().getOutput().getText();
            }

            int promptTokens = 0;
            int completionTokens = 0;
            int totalTokens = 0;
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                var usage = response.getMetadata().getUsage();
                promptTokens = usage.getPromptTokens();
                completionTokens = usage.getCompletionTokens();
                totalTokens = usage.getTotalTokens();
            }

            log.info("Model {} responded in {}ms ({} tokens)",
                    modelName, elapsed.toMillis(), totalTokens);

            return new ModelComparisonResult(
                    modelName, true, content, elapsed.toMillis(),
                    promptTokens, completionTokens, totalTokens, null);
        } catch (Exception e) { // Intentional: LLM failures shouldn't crash comparison
            Duration elapsed = Duration.between(start, Instant.now());
            log.error("Model {} failed after {}ms: {}", modelName, elapsed.toMillis(), e.getMessage());
            return new ModelComparisonResult(
                    modelName, false, null, elapsed.toMillis(),
                    0, 0, 0, e.getMessage());
        }
    }

    /**
     * Model comparison result
     */
    public static class ModelComparisonResult {
        private final String modelName;
        private final boolean success;
        private final String response;
        private final long latencyMs;
        private final int promptTokens;
        private final int completionTokens;
        private final int totalTokens;
        private final String error;

        public ModelComparisonResult(String modelName, boolean success, String response,
                                      long latencyMs, int promptTokens, int completionTokens,
                                      int totalTokens, String error) {
            this.modelName = modelName;
            this.success = success;
            this.response = response;
            this.latencyMs = latencyMs;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
            this.error = error;
        }

        public static ModelComparisonResult failure(String modelName, String error) {
            return new ModelComparisonResult(modelName, false, null, 0, 0, 0, 0, error);
        }

        public String getModelName() { return modelName; }
        public boolean isSuccess() { return success; }
        public String getResponse() { return response; }
        public long getLatencyMs() { return latencyMs; }
        public int getPromptTokens() { return promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public int getTotalTokens() { return totalTokens; }
        public String getError() { return error; }
    }
}
