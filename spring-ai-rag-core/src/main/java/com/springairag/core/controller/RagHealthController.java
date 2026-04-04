package com.springairag.core.controller;

import com.springairag.api.dto.ComponentHealthResponse;
import com.springairag.api.dto.HealthResponse;
import com.springairag.core.metrics.ComponentHealthService;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查控制器（增强版 — 多组件探针）
 *
 * <p>提供细粒度的组件级健康状态，支持：
 * <ul>
 *   <li>GET /rag/health — 整体状态 + 各组件摘要</li>
 *   <li>GET /rag/health/components — 各组件详细状态</li>
 * </ul>
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag")
@Tag(name = "RAG Health", description = "健康检查与状态监控")
public class RagHealthController {

    private final ComponentHealthService componentHealth;

    public RagHealthController(ComponentHealthService componentHealth) {
        this.componentHealth = componentHealth;
    }

    /**
     * 整体健康检查
     */
    @Operation(summary = "健康检查", description = "返回整体状态和各组件摘要。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回健康状态（UP/DEGRADED/DOWN）和各组件状态"),
    })
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        Map<String, ComponentHealthService.ComponentStatus> components =
                componentHealth.checkAll();

        // 简化输出：每个组件只返回 status
        Map<String, String> componentStatuses = new LinkedHashMap<>();
        for (Map.Entry<String, ComponentHealthService.ComponentStatus> entry : components.entrySet()) {
            componentStatuses.put(entry.getKey(), entry.getValue().status());
        }

        return ResponseEntity.ok(HealthResponse.of(
                componentHealth.overallStatus(components), componentStatuses));
    }

    /**
     * 组件级详细健康检查
     */
    @Operation(summary = "组件详细状态", description = "返回每个组件的详细健康信息，包括延迟、版本、表计数等。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回各组件的详细状态信息（database/pgvector/tables/cache）"),
    })
    @GetMapping("/health/components")
    public ResponseEntity<ComponentHealthResponse> healthComponents() {
        Map<String, ComponentHealthService.ComponentStatus> components =
                componentHealth.checkAll();

        // 详细输出：每个组件的完整信息
        Map<String, Map<String, Object>> componentDetails = new LinkedHashMap<>();
        for (Map.Entry<String, ComponentHealthService.ComponentStatus> entry : components.entrySet()) {
            componentDetails.put(entry.getKey(), entry.getValue().toMap());
        }

        return ResponseEntity.ok(ComponentHealthResponse.of(
                componentHealth.overallStatus(components), componentDetails));
    }
}
