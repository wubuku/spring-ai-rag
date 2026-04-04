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
 * 多模型并行对比服务
 *
 * <p>将同一查询发送给多个模型，收集响应内容和延迟数据，
 * 用于模型效果对比和 A/B 实验前的数据采集。
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
     * 并行对比多个模型
     *
     * <p>使用共享线程池，避免每次调用创建新 ExecutorService。
     * 单个模型超时或异常时不影响其他模型，结果降级为失败记录。
     *
     * @param query   查询文本
     * @param models  模型名称 → ChatModel 映射
     * @param timeoutSeconds 单个模型超时（秒）
     * @return 对比结果列表（按提交顺序）
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
     * 并行对比多个 provider（通过 ModelRegistry 解析）
     *
     * @param query 查询文本
     * @param providers 要对比的 provider 列表（如 ["openai", "minimax"]）
     * @param timeoutSeconds 单个模型超时（秒）
     * @return 对比结果列表（按提交顺序）
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
     * 对比所有已注册的 provider
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
     * 模型对比结果
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
