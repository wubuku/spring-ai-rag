package com.springairag.core.controller;

import com.springairag.core.metrics.ComponentHealthService;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
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
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, ComponentHealthService.ComponentStatus> components =
                componentHealth.checkAll();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", componentHealth.overallStatus(components));
        result.put("timestamp", Instant.now().toString());

        // 简化输出：每个组件只返回 status
        for (Map.Entry<String, ComponentHealthService.ComponentStatus> entry : components.entrySet()) {
            result.put(entry.getKey(), entry.getValue().status());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 组件级详细健康检查
     */
    @Operation(summary = "组件详细状态", description = "返回每个组件的详细健康信息，包括延迟、版本、表计数等。")
    @GetMapping("/health/components")
    public ResponseEntity<Map<String, Object>> healthComponents() {
        Map<String, ComponentHealthService.ComponentStatus> components =
                componentHealth.checkAll();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", componentHealth.overallStatus(components));
        result.put("timestamp", Instant.now().toString());

        // 详细输出：每个组件的完整信息
        Map<String, Object> componentDetails = new LinkedHashMap<>();
        for (Map.Entry<String, ComponentHealthService.ComponentStatus> entry : components.entrySet()) {
            componentDetails.put(entry.getKey(), entry.getValue().toMap());
        }
        result.put("components", componentDetails);

        return ResponseEntity.ok(result);
    }
}
