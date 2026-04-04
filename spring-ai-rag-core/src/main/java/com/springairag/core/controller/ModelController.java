package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.ModelCompareResponse;
import com.springairag.api.dto.ModelDetailResponse;
import com.springairag.api.dto.ModelListResponse;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.ModelRegistry;
import com.springairag.core.service.ModelComparisonService;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 多模型管理 REST 端点
 *
 * <p>提供模型列表查询、模型详情、路由状态、模型对比等管理接口。
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/models")
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
    public ResponseEntity<ModelListResponse> listModels() {
        List<String> availableProviders = modelRouter.getAvailableProviders();
        return ResponseEntity.ok(ModelListResponse.of(
                modelRouter.isMultiModelEnabled(),
                resolveDefaultProvider(),
                availableProviders,
                modelRouter.getFallbackChain(),
                modelRegistry.getAllModelsInfo()
        ));
    }

    @GetMapping("/{provider}")
    @Operation(summary = "获取指定 provider 的模型详情")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "返回模型详情"),
        @ApiResponse(responseCode = "404", description = "provider 不存在或不可用")
    })
    public ResponseEntity<ModelDetailResponse> getModel(@PathVariable String provider) {
        if (!modelRouter.isProviderAvailable(provider)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ModelDetailResponse.of(
                true, modelRegistry.getModelInfo(provider)));
    }

    @PostMapping("/compare")
    @Operation(summary = "并行对比多个模型的响应",
            description = "将同一查询发送给多个模型，并行收集响应内容和延迟数据。用于模型效果对比。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "返回各模型的对比结果"),
        @ApiResponse(responseCode = "400", description = "providers 列表为空或无效")
    })
    public ResponseEntity<?> compareModels(@Valid @RequestBody CompareModelsRequest request) {
        if (request.providers == null || request.providers.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("providers cannot be empty"));
        }
        List<ModelComparisonService.ModelComparisonResult> results =
                modelComparisonService.compareProviders(
                        request.query, request.providers,
                        request.timeoutSeconds != null ? request.timeoutSeconds : 30);

        List<ModelCompareResponse.ModelCompareResult> resultList = results.stream()
                .map(r -> new ModelCompareResponse.ModelCompareResult(
                        r.getModelName(),
                        r.isSuccess(),
                        r.getResponse(),
                        r.getLatencyMs(),
                        r.getPromptTokens(),
                        r.getCompletionTokens(),
                        r.getTotalTokens(),
                        r.getError()))
                .toList();

        return ResponseEntity.ok(new ModelCompareResponse(
                request.query, request.providers, resultList));
    }

    private String resolveDefaultProvider() {
        var allInfo = modelRegistry.getAllModelsInfo();
        for (var info : allInfo) {
            if (Boolean.TRUE.equals(info.get("available"))) {
                return (String) info.get("provider");
            }
        }
        var providers = modelRouter.getAvailableProviders();
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
