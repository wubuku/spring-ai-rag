package com.springairag.core.controller;

import com.springairag.api.dto.ComponentHealthResponse;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.HealthResponse;
import com.springairag.core.metrics.ComponentHealthService;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * Health check controller (enhanced — multi-component probes).
 *
 * <p>Provides fine-grained component-level health status, supports:
 * <ul>
 *   <li>GET /rag/health — overall status + component summaries</li>
 *   <li>GET /rag/health/components — detailed status of each component</li>
 * </ul>
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag")
@Tag(name = "RAG Health", description = "Health check and status monitoring")
public class RagHealthController {

    private final ComponentHealthService componentHealth;

    public RagHealthController(ComponentHealthService componentHealth) {
        this.componentHealth = componentHealth;
    }

    /**
     * Overall health check.
     */
    @Operation(summary = "Health check",
               description = "Returns overall status and component summaries (UP/DEGRADED/DOWN).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Returns health status (UP/DEGRADED/DOWN) and each component status"),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        Map<String, ComponentHealthService.ComponentStatus> components =
                componentHealth.checkAll();

        // Simplified output: only return status for each component
        Map<String, String> componentStatuses = new LinkedHashMap<>();
        for (Map.Entry<String, ComponentHealthService.ComponentStatus> entry : components.entrySet()) {
            componentStatuses.put(entry.getKey(), entry.getValue().status());
        }

        return ResponseEntity.ok(HealthResponse.of(
                componentHealth.overallStatus(components), componentStatuses));
    }

    /**
     * Component-level detailed health check.
     */
    @Operation(summary = "Component detailed status",
               description = "Returns detailed health information for each component, including latency, version, table counts, etc.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Returns detailed status info for each component (database/pgvector/tables/cache)"),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/health/components")
    public ResponseEntity<ComponentHealthResponse> healthComponents() {
        Map<String, ComponentHealthService.ComponentStatus> components =
                componentHealth.checkAll();

        // Detailed output: full information for each component
        Map<String, Map<String, Object>> componentDetails = new LinkedHashMap<>();
        for (Map.Entry<String, ComponentHealthService.ComponentStatus> entry : components.entrySet()) {
            componentDetails.put(entry.getKey(), entry.getValue().toMap());
        }

        return ResponseEntity.ok(ComponentHealthResponse.of(
                componentHealth.overallStatus(components), componentDetails));
    }
}
