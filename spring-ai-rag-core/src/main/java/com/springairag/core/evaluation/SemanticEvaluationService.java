package com.springairag.core.evaluation;

import com.springairag.api.dto.SemanticEvaluationRequest;
import com.springairag.api.dto.SemanticEvaluationResponse;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 薄适配 Spring AI FactCheckingEvaluator / RelevancyEvaluator。
 * 类不存在或未配置 ChatClient 时返回 DISABLED，不编造 groundedness 分数。
 */
@Service
public class SemanticEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(SemanticEvaluationService.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final ChatClient.Builder chatClientBuilder;
    private final ChatModelRouter chatModelRouter;
    private final RagProperties ragProperties;

    public SemanticEvaluationService(
            ChatClient.Builder chatClientBuilder,
            RagProperties ragProperties) {
        this(chatClientBuilder, null, ragProperties);
    }

    @Autowired
    public SemanticEvaluationService(
            @Autowired(required = false) ChatClient.Builder chatClientBuilder,
            @Autowired(required = false) ChatModelRouter chatModelRouter,
            RagProperties ragProperties) {
        this.chatClientBuilder = chatClientBuilder;
        this.chatModelRouter = chatModelRouter;
        this.ragProperties = ragProperties;
    }

    public SemanticEvaluationResponse evaluate(SemanticEvaluationRequest request) {
        String evaluator = request.evaluator() == null
                ? "" : request.evaluator().trim().toUpperCase();
        if (!"FACT_CHECKING".equals(evaluator) && !"RELEVANCY".equals(evaluator)) {
            throw new IllegalArgumentException(
                    "evaluator must be FACT_CHECKING or RELEVANCY");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        ChatClient.Builder evaluatorBuilder = resolveBuilder(request.model());
        if (evaluatorBuilder == null) {
            return disabled(evaluator, request.model(), "ChatClient is not configured");
        }
        String className = "FACT_CHECKING".equals(evaluator)
                ? "org.springframework.ai.chat.evaluation.FactCheckingEvaluator"
                : "org.springframework.ai.chat.evaluation.RelevancyEvaluator";
        try {
            Class<?> evaluatorType = Class.forName(className);
            Class<?> requestType = Class.forName(
                    "org.springframework.ai.evaluation.EvaluationRequest");
            Object evaluatorInstance = instantiateEvaluator(
                    evaluator, evaluatorType, evaluatorBuilder);
            Object evaluationRequest = createEvaluationRequest(requestType, request);
            Method evaluate = evaluatorType.getMethod("evaluate", requestType);
            int timeout = ragProperties.getRetrieval().getAnswerQualityTimeoutSeconds();
            Object response = CompletableFuture.supplyAsync(
                            () -> invoke(evaluate, evaluatorInstance, evaluationRequest))
                    .get(timeout, TimeUnit.SECONDS);
            Method passed = response.getClass().getMethod("isPass");
            Method score = response.getClass().getMethod("getScore");
            Method feedback = response.getClass().getMethod("getFeedback");
            Boolean isPass = (Boolean) passed.invoke(response);
            Number rawScore = (Number) score.invoke(response);
            String text = String.valueOf(feedback.invoke(response));
            return new SemanticEvaluationResponse(
                    evaluator, "COMPLETED", isPass,
                    rawScore == null ? null : rawScore.doubleValue(),
                    text, request.model(), null);
        } catch (ClassNotFoundException e) {
            return disabled(evaluator, request.model(),
                    "Spring AI evaluator classes are not on the classpath");
        } catch (java.util.concurrent.TimeoutException e) {
            return new SemanticEvaluationResponse(
                    evaluator, "TIMEOUT", null, null, null, request.model(),
                    "Semantic evaluator timed out");
        } catch (Exception e) {
            log.warn("Semantic evaluator failed: {}", e.getMessage());
            return new SemanticEvaluationResponse(
                    evaluator, "FAILED", null, null, null, request.model(),
                    safeError(e.getMessage()));
        }
    }

    public List<SemanticEvaluationResponse> evaluateBatch(
            List<SemanticEvaluationRequest> requests) {
        int limit = ragProperties.getEvaluation().getSemanticBatchLimit();
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (requests.size() > limit) {
            throw new IllegalArgumentException("semantic batch must not exceed " + limit);
        }
        Map<String, SemanticEvaluationResponse> unique = new LinkedHashMap<>();
        List<SemanticEvaluationResponse> results = new ArrayList<>();
        for (SemanticEvaluationRequest request : requests) {
            String key = String.join("\0",
                    request.query(), request.context(), request.answer(),
                    request.evaluator(), String.valueOf(request.model()));
            SemanticEvaluationResponse existing = unique.get(key);
            if (existing == null) {
                existing = evaluate(request);
                unique.put(key, existing);
            }
            results.add(existing);
        }
        return results;
    }

    /**
     * Spring AI 1.1.4：RelevancyEvaluator 有公开 (ChatClient.Builder) 构造；
     * FactCheckingEvaluator 只有 builder(ChatClient.Builder).build()。
     */
    Object instantiateEvaluator(String evaluator, Class<?> evaluatorType) throws Exception {
        return instantiateEvaluator(evaluator, evaluatorType, chatClientBuilder);
    }

    private Object instantiateEvaluator(
            String evaluator,
            Class<?> evaluatorType,
            ChatClient.Builder builder) throws Exception {
        if ("FACT_CHECKING".equals(evaluator)) {
            Method builderFactory = evaluatorType.getMethod(
                    "builder", ChatClient.Builder.class);
            Object evaluatorBuilder = builderFactory.invoke(null, builder);
            return evaluatorBuilder.getClass().getMethod("build").invoke(evaluatorBuilder);
        }
        Constructor<?> evaluatorCtor = evaluatorType.getConstructor(ChatClient.Builder.class);
        return evaluatorCtor.newInstance(builder);
    }

    /**
     * EvaluationRequest(String, List&lt;Document&gt;, String) 需要 Document，不能传入裸 String。
     */
    Object createEvaluationRequest(
            Class<?> requestType, SemanticEvaluationRequest request) throws Exception {
        Class<?> documentType = Class.forName("org.springframework.ai.document.Document");
        Object document = documentType.getConstructor(String.class).newInstance(
                request.context() == null ? "" : request.context());
        Constructor<?> requestCtor = requestType.getConstructor(
                String.class, List.class, String.class);
        return requestCtor.newInstance(
                request.query(), List.of(document), request.answer());
    }

    private SemanticEvaluationResponse disabled(String evaluator, String model, String error) {
        return new SemanticEvaluationResponse(
                evaluator, "DISABLED", null, null, null, model, error);
    }

    ChatClient.Builder resolveBuilder(String modelRef) {
        if (chatModelRouter == null) {
            return chatClientBuilder;
        }
        ChatModel model = chatModelRouter.resolveRequired(modelRef);
        ChatClient.Builder builder = ChatClient.builder(model);
        ChatOptions options = model.getDefaultOptions();
        if (options != null) {
            builder.defaultOptions(options.copy());
        }
        return builder;
    }

    private String safeError(String value) {
        String raw = value == null || value.isBlank()
                ? "Semantic evaluator failed"
                : value;
        String masked = SensitiveDataMaskingConverter.maskSensitiveData(raw);
        return masked.length() <= MAX_ERROR_LENGTH
                ? masked
                : masked.substring(0, MAX_ERROR_LENGTH);
    }

    private Object invoke(Method method, Object target, Object argument) {
        try {
            return method.invoke(target, argument);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
