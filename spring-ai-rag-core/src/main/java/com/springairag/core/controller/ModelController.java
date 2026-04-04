package com.springairag.core.controller;

import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.ModelRegistry;
import com.springairag.core.service.ModelComparisonService;
import com.springairag.core.service.ModelComparisonService.ModelComparisonResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
 * 多模型管理 REST 端点
 *
 * <p>提供模型列表查询、模型详情、路由状态、模型对比等管理接口。
 */
@RestController
@RequestMapping("/api/v1/rag/models")
@Tag(name = "Models", description = "多模型管理")
public class ModelController {

    private final ModelRegistry modelRegistry;
    private final ChatModelRouter modelRouter;
    private final ModelComparisonService modelComparisonService;

    public ModelController(ModelRegistry modelRegistry,
                          ChatModelRouter modelRouter,
                          ModelComparisonService modelComparisonService) {
        this.modelRegistry = modelRegistry;
        this.modelRouter = modelRouter;
        this.modelComparisonService = modelComparisonService;
    }

    @GetMapping
    @Operation(summary = "获取所有已注册模型列表")
    @ApiResponse(responseCode = "200", description = "返回所有已注册模型及其状态")
    public ResponseEntity<Map<String, Object>> listModels() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("multiModelEnabled", modelRouter.isMultiModelEnabled());
        response.put("defaultProvider", getDefaultProvider());
        response.put("availableProviders", modelRouter.getAvailableProviders());
        response.put("fallbackChain", modelRouter.getFallbackChain());
        response.put("models", modelRegistry.getAllModelsInfo());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{provider}")
    @Operation(summary = "获取指定 provider 的模型详情")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "返回模型详情"),
        @ApiResponse(responseCode = "404", description = "provider 不存在或不可用")
    })
    public ResponseEntity<Map<String, Object>> getModel(@PathVariable String provider) {
        if (!modelRouter.isProviderAvailable(provider)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> baseInfo = modelRegistry.getModelInfo(provider);
        Map<String, Object> response = new LinkedHashMap<>(baseInfo);
        response.put("available", true);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/compare")
    @Operation(summary = "并行对比多个模型的响应",
            description = "将同一查询发送给多个模型，并行收集响应内容和延迟数据。用于模型效果对比。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "返回各模型的对比结果"),
        @ApiResponse(responseCode = "400", description = "providers 列表为空或无效")
    })
    public ResponseEntity<Map<String, Object>> compareModels(
            @Valid @RequestBody CompareModelsRequest request) {
        if (request.providers == null || request.providers.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "providers cannot be empty"));
        }
        List<ModelComparisonResult> results = modelComparisonService.compareProviders(
                request.query, request.providers,
                request.timeoutSeconds != null ? request.timeoutSeconds : 30);

        List<Map<String, Object>> resultList = results.stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("modelName", r.getModelName());
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

    private String getDefaultProvider() {
        var providers = modelRouter.getAvailableProviders();
        if (providers.isEmpty()) {
            return "none";
        }
        var allInfo = modelRegistry.getAllModelsInfo();
        for (var info : allInfo) {
            if (Boolean.TRUE.equals(info.get("available"))) {
                return (String) info.get("provider");
            }
        }
        return providers.isEmpty() ? "none" : providers.get(0);
    }

    /** 模型对比请求 DTO */
    public static class CompareModelsRequest {
        @NotBlank(message = "query cannot be blank")
        public String query;

        @Size(min = 1, message = "providers cannot be empty")
        public List<String> providers;

        /** 单个模型超时秒数，默认 30 */
        public Integer timeoutSeconds;
    }
}
