package com.springairag.demo.multimodel;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.config.ModelRegistry;
import com.springairag.core.service.ModelComparisonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多模型演示控制器
 *
 * <p>展示三种多模型使用场景：
 *
 * <h3>1. 模型注册中心（ModelRegistry）</h3>
 * <pre>GET /demo/models — 查看所有已注册模型及状态</pre>
 *
 * <h3>2. 指定模型问答（ModelComparisonService）</h3>
 * <pre>POST /demo/chat?provider=openai — 指定使用某个模型回答</pre>
 *
 * <h3>3. 模型效果对比（Model Comparison）</h3>
 * <pre>POST /demo/compare — 并行对比多个模型的回答</pre>
 */
@RestController
@RequestMapping("/demo")
@Tag(name = "Multi-Model Demo", description = "多模型演示 API")
public class MultiModelController {

    private final ModelRegistry modelRegistry;
    private final ModelComparisonService modelComparisonService;

    public MultiModelController(ModelRegistry modelRegistry,
                                ModelComparisonService modelComparisonService) {
        this.modelRegistry = modelRegistry;
        this.modelComparisonService = modelComparisonService;
    }

    /**
     * 查看所有已注册模型
     */
    @GetMapping("/models")
    @Operation(summary = "查看模型注册中心",
            description = "返回所有已注册模型的状态、可用 provider 列表和详细信息")
    public ResponseEntity<Map<String, Object>> listModels() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("availableProviders", modelRegistry.availableProviders());
        response.put("models", modelRegistry.getAllModelsInfo());

        return ResponseEntity.ok(response);
    }

    /**
     * 获取指定 provider 的模型详情
     */
    @GetMapping("/models/{provider}")
    @Operation(summary = "查看指定模型详情")
    public ResponseEntity<Map<String, Object>> getModel(@PathVariable String provider) {
        if (!modelRegistry.isAvailable(provider)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> info = modelRegistry.getModelInfo(provider);
        return ResponseEntity.ok(info);
    }

    /**
     * 使用指定模型进行问答
     *
     * <p>通过 ModelComparisonService 调用指定模型，支持：
     * <ul>
     *   <li>openai — DeepSeek 等 OpenAI 兼容模型</li>
     *   <li>anthropic — Anthropic Claude 系列</li>
     *   <li>minimax — MiniMax Text-01</li>
     * </ul>
     */
    @PostMapping("/chat")
    @Operation(summary = "使用指定模型进行问答",
            description = "通过 provider 参数指定使用哪个模型，支持 openai/anthropic/minimax")
    public ResponseEntity<ChatResponse> chatWithProvider(
            @RequestBody Map<String, String> body,
            @Parameter(description = "模型提供者：openai / anthropic / minimax")
            @RequestParam(defaultValue = "openai") String provider) {

        if (!modelRegistry.isAvailable(provider)) {
            throw new IllegalArgumentException("Provider '" + provider + "' not available. "
                    + "Available: " + modelRegistry.availableProviders());
        }

        List<ModelComparisonService.ModelComparisonResult> results =
                modelComparisonService.compareProviders(
                        body.get("message"),
                        List.of(provider),
                        30);

        if (results.isEmpty()) {
            throw new IllegalStateException("No response from provider: " + provider);
        }

        ModelComparisonService.ModelComparisonResult result = results.get(0);
        if (!result.isSuccess()) {
            throw new IllegalStateException("Provider '" + provider
                    + "' failed: " + result.getError());
        }

        ChatResponse response = new ChatResponse(result.getResponse());
        return ResponseEntity.ok(response);
    }

    /**
     * 并行对比多个模型的回答
     *
     * <p>将同一问题同时发给多个模型，返回各模型的回答和性能数据。
     * 用于评估不同模型在特定问题上的效果差异。
     */
    @PostMapping("/compare")
    @Operation(summary = "并行对比多个模型的回答",
            description = "将同一查询发送给多个模型，并行收集响应内容和延迟数据")
    @Valid
    public ResponseEntity<Map<String, Object>> compareModels(
            @RequestBody CompareRequest request) {

        List<ModelComparisonService.ModelComparisonResult> results =
                modelComparisonService.compareProviders(
                        request.query,
                        request.providers,
                        request.timeoutSeconds != null ? request.timeoutSeconds : 30);

        List<Map<String, Object>> resultList = results.stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("provider", r.getModelName());
                    m.put("success", r.isSuccess());
                    m.put("response", r.getResponse());
                    m.put("latencyMs", r.getLatencyMs());
                    m.put("promptTokens", r.getPromptTokens());
                    m.put("completionTokens", r.getCompletionTokens());
                    m.put("totalTokens", r.getTotalTokens());
                    m.put("error", r.getError());
                    return m;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", request.query);
        response.put("providers", request.providers);
        response.put("results", resultList);

        return ResponseEntity.ok(response);
    }

    /** 模型对比请求 DTO */
    public static class CompareRequest {
        @NotBlank(message = "query cannot be blank")
        public String query;

        @Size(min = 1, message = "providers cannot be empty")
        public List<String> providers;

        /** 单个模型超时秒数，默认 30 */
        public Integer timeoutSeconds;
    }
}
