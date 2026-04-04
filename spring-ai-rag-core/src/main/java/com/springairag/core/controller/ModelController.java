package com.springairag.core.controller;

import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.ModelRegistry;
import com.springairag.core.service.ModelComparisonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多模型管理 REST 端点
 *
 * <p>提供模型列表查询、模型详情、路由状态等管理接口。
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
    public ResponseEntity<Map<String, Object>> getModel(@PathVariable String provider) {
        if (!modelRouter.isProviderAvailable(provider)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> baseInfo = modelRegistry.getModelInfo(provider);
        Map<String, Object> response = new LinkedHashMap<>(baseInfo);
        response.put("available", true);
        return ResponseEntity.ok(response);
    }

    private String getDefaultProvider() {
        var providers = modelRouter.getAvailableProviders();
        if (providers.isEmpty()) {
            return "none";
        }
        // 尝试从 registry 获取默认 provider
        var allInfo = modelRegistry.getAllModelsInfo();
        for (var info : allInfo) {
            if (Boolean.TRUE.equals(info.get("available"))) {
                return (String) info.get("provider");
            }
        }
        return providers.isEmpty() ? "none" : providers.get(0);
    }
}
