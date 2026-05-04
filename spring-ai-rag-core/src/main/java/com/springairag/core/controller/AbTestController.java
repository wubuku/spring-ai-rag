package com.springairag.core.controller;

import com.springairag.api.dto.VariantResponse;
import com.springairag.api.service.AbTestService;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.versioning.ApiVersion;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A/B test controller.
 *
 * <p>Provides experiment CRUD, lifecycle management, result recording and statistical analysis.
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/ab")
@Tag(name = "A/B Testing", description = "A/B testing: retrieval strategy comparison experiment")
public class AbTestController {

    private final AbTestService abTestService;
    private final AuditLogService auditLogService;  // optional: null when RagAuditLogRepository unavailable

    public AbTestController(AbTestService abTestService,
                            @Autowired(required = false) AuditLogService auditLogService) {
        this.abTestService = abTestService;
        this.auditLogService = auditLogService;
    }

    @Operation(summary = "Create experiment")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Experiment created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    })
    @Timed(value = "rag.ab.create", description = "A/B experiment creation", percentiles = {0.5, 0.95, 0.99})
    @PostMapping("/experiments")
    public ResponseEntity<AbTestService.Experiment> createExperiment(
            @Valid @RequestBody AbTestService.CreateExperimentRequest request) {
        AbTestService.Experiment experiment = abTestService.createExperiment(request);
        auditCreate(AuditLogService.ENTITY_AB_TEST,
                String.valueOf(experiment.getId()),
                "A/B experiment created: " + experiment.getExperimentName());
        return ResponseEntity.ok(experiment);
    }

    @Operation(summary = "Update experiment (DRAFT/PAUSED status only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Experiment updated successfully"),
        @ApiResponse(responseCode = "404", description = "Experiment not found"),
        @ApiResponse(responseCode = "409", description = "Experiment status does not allow update")
    })
    @Timed(value = "rag.ab.update", description = "A/B experiment update", percentiles = {0.5, 0.95, 0.99})
    @PutMapping("/experiments/{id}")
    public ResponseEntity<Void> updateExperiment(
            @PathVariable Long id,
            @Valid @RequestBody AbTestService.UpdateExperimentRequest request) {
        abTestService.updateExperiment(id, request);
        auditUpdate(AuditLogService.ENTITY_AB_TEST, String.valueOf(id),
                "A/B experiment updated: " + id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Start experiment")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Experiment started successfully"),
        @ApiResponse(responseCode = "404", description = "Experiment not found"),
        @ApiResponse(responseCode = "409", description = "Experiment status does not allow starting")
    })
    @Timed(value = "rag.ab.start", description = "A/B experiment start", percentiles = {0.5, 0.95, 0.99})
    @PostMapping("/experiments/{id}/start")
    public ResponseEntity<Void> startExperiment(@PathVariable Long id) {
        abTestService.startExperiment(id);
        auditUpdate(AuditLogService.ENTITY_AB_TEST, String.valueOf(id),
                "A/B experiment started: " + id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Pause experiment")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Experiment paused successfully"),
        @ApiResponse(responseCode = "404", description = "Experiment not found")
    })
    @Timed(value = "rag.ab.pause", description = "A/B experiment pause", percentiles = {0.5, 0.95, 0.99})
    @PostMapping("/experiments/{id}/pause")
    public ResponseEntity<Void> pauseExperiment(@PathVariable Long id) {
        abTestService.pauseExperiment(id);
        auditUpdate(AuditLogService.ENTITY_AB_TEST, String.valueOf(id),
                "A/B experiment paused: " + id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Stop experiment")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Experiment stopped successfully"),
        @ApiResponse(responseCode = "404", description = "Experiment not found")
    })
    @Timed(value = "rag.ab.stop", description = "A/B experiment stop", percentiles = {0.5, 0.95, 0.99})
    @PostMapping("/experiments/{id}/stop")
    public ResponseEntity<Void> stopExperiment(@PathVariable Long id) {
        abTestService.stopExperiment(id);
        auditUpdate(AuditLogService.ENTITY_AB_TEST, String.valueOf(id),
                "A/B experiment stopped: " + id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Get running experiments")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Returns list of running experiments")
    })
    @GetMapping("/experiments/running")
    public ResponseEntity<List<AbTestService.Experiment>> getRunningExperiments() {
        return ResponseEntity.ok(abTestService.getRunningExperiments());
    }

    @Operation(summary = "Get variant assignment", description = "Calculate which variant this user should be assigned to based on sessionId")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Returns variant assignment result"),
        @ApiResponse(responseCode = "404", description = "Experiment not found")
    })
    @Timed(value = "rag.ab.variant", description = "A/B variant assignment lookup", percentiles = {0.5, 0.95, 0.99})
    @GetMapping("/experiments/{id}/variant")
    public ResponseEntity<VariantResponse> getVariant(
            @PathVariable Long id,
            @RequestParam String sessionId) {
        String variant = abTestService.getVariantForSession(sessionId, id);
        return ResponseEntity.ok(VariantResponse.of(variant));
    }

    @Operation(summary = "Record experiment results")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Result recorded successfully"),
        @ApiResponse(responseCode = "404", description = "Experiment not found")
    })
    @Timed(value = "rag.ab.record", description = "A/B experiment result recording", percentiles = {0.5, 0.95, 0.99})
    @PostMapping("/experiments/{id}/results")
    public ResponseEntity<Void> recordResult(
            @PathVariable Long id,
            @RequestBody ResultRequest request) {
        abTestService.recordResult(id, request.variantName, request.sessionId,
                request.query, request.retrievedDocIds, request.metrics);
        auditUpdate(AuditLogService.ENTITY_AB_TEST, String.valueOf(id),
                "Result recorded for experiment " + id,
                Map.of("variant", request.variantName != null ? request.variantName : "null",
                        "sessionId", request.sessionId != null ? request.sessionId : "null"));
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Analyze experiment results")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Returns experiment analysis results"),
        @ApiResponse(responseCode = "404", description = "Experiment not found")
    })
    @Timed(value = "rag.ab.analyze", description = "A/B experiment analysis", percentiles = {0.5, 0.95, 0.99})
    @GetMapping("/experiments/{id}/analysis")
    public ResponseEntity<AbTestService.ExperimentAnalysis> analyzeExperiment(@PathVariable Long id) {
        return ResponseEntity.ok(abTestService.analyzeExperiment(id));
    }

    @Operation(summary = "Get experiment results list")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Returns paginated experiment results list"),
        @ApiResponse(responseCode = "404", description = "Experiment not found")
    })
    @GetMapping("/experiments/{id}/results")
    public ResponseEntity<List<AbTestService.ExperimentResult>> getResults(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(abTestService.getExperimentResults(id, page, size));
    }

    // ==================== Request DTO ====================

    /**
     * Result recording request body.
     */
    public static class ResultRequest {
        public String variantName;
        public String sessionId;
        public String query;
        public List<Long> retrievedDocIds;
        public Map<String, Double> metrics;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ResultRequest that = (ResultRequest) o;
            return Objects.equals(variantName, that.variantName)
                    && Objects.equals(sessionId, that.sessionId)
                    && Objects.equals(query, that.query)
                    && Objects.equals(retrievedDocIds, that.retrievedDocIds)
                    && Objects.equals(metrics, that.metrics);
        }

        @Override
        public int hashCode() {
            return Objects.hash(variantName, sessionId, query, retrievedDocIds, metrics);
        }

        @Override
        public String toString() {
            return "ResultRequest{" +
                    "variantName=" + variantName +
                    ", sessionId=" + sessionId +
                    ", query=" + query +
                    ", retrievedDocIds=" + retrievedDocIds +
                    ", metrics=" + metrics +
                    '}';
        }
    }

    // Null-safe audit logging helpers (AuditLogService is optional)
    private void auditCreate(String entityType, String entityId, String message) {
        if (auditLogService != null) auditLogService.logCreate(entityType, entityId, message);
    }

    private void auditUpdate(String entityType, String entityId, String message) {
        if (auditLogService != null) auditLogService.logUpdate(entityType, entityId, message);
    }

    private void auditUpdate(String entityType, String entityId, String message, Map<String, Object> details) {
        if (auditLogService != null) auditLogService.logUpdate(entityType, entityId, message, details);
    }
}
