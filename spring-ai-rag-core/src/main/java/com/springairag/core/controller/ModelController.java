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
 * Multi-model management REST endpoint.
 *
 * <p>Provides model list query, model details, routing status, model comparison and other management interfaces.
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/models")
@Tag(name = "Models", description = "Multi-model management")
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
    @Operation(summary = "Get all registered model list")
    @ApiResponse(responseCode = "200", description = "Returns all registered models and their status")
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
    @Operation(summary = "Get model details for specified provider")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Returns model details"),
        @ApiResponse(responseCode = "404", description = "Provider not found or unavailable")
    })
    public ResponseEntity<ModelDetailResponse> getModel(@PathVariable String provider) {
        if (!modelRouter.isProviderAvailable(provider)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ModelDetailResponse.of(
                true, modelRegistry.getModelInfo(provider)));
    }

    @PostMapping("/compare")
    @Operation(summary = "Compare responses from multiple models in parallel",
            description = "Sends the same query to multiple models and collects response content and latency data in parallel. Used for model effect comparison.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Returns comparison results of each model"),
        @ApiResponse(responseCode = "400", description = "providers list is empty or invalid")
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

    /** Model comparison request DTO. */
    public static class CompareModelsRequest {
        @NotBlank(message = "query cannot be blank")
        public String query;

        @Size(min = 1, message = "providers cannot be empty")
        public List<String> providers;

        /** Per-model timeout in seconds; default 30 */
        public Integer timeoutSeconds;
    }
}
