package com.springairag.core.controller;

import com.springairag.api.dto.VariantResponse;
import com.springairag.api.service.AbTestService;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.versioning.ApiVersion;
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

/**
 * A/B 测试控制器
 *
 * <p>提供实验的 CRUD、生命周期管理、结果记录和统计分析接口。
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/ab")
@Tag(name = "A/B Testing", description = "A/B 测试：检索策略对比实验")
public class AbTestController {

    private final AbTestService abTestService;
    private final AuditLogService auditLogService;  // optional: null when RagAuditLogRepository unavailable

    public AbTestController(AbTestService abTestService,
                            @Autowired(required = false) AuditLogService auditLogService) {
        this.abTestService = abTestService;
        this.auditLogService = auditLogService;
    }

    @Operation(summary = "创建实验")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "实验创建成功"),
        @ApiResponse(responseCode = "400", description = "请求参数无效")
    })
    @PostMapping("/experiments")
    public ResponseEntity<AbTestService.Experiment> createExperiment(
            @Valid @RequestBody AbTestService.CreateExperimentRequest request) {
        AbTestService.Experiment experiment = abTestService.createExperiment(request);
        auditCreate(AuditLogService.ENTITY_AB_TEST,
                String.valueOf(experiment.getId()),
                "A/B experiment created: " + experiment.getExperimentName());
        return ResponseEntity.ok(experiment);
    }

    @Operation(summary = "更新实验（仅 DRAFT/PAUSED 状态）")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "实验更新成功"),
        @ApiResponse(responseCode = "404", description = "实验不存在"),
        @ApiResponse(responseCode = "409", description = "实验状态不允许更新")
    })
    @PutMapping("/experiments/{id}")
    public ResponseEntity<Void> updateExperiment(
            @PathVariable Long id,
            @Valid @RequestBody AbTestService.UpdateExperimentRequest request) {
        abTestService.updateExperiment(id, request);
        auditUpdate(AuditLogService.ENTITY_AB_TEST, String.valueOf(id),
                "A/B experiment updated: " + id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "启动实验")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "实验启动成功"),
        @ApiResponse(responseCode = "404", description = "实验不存在"),
        @ApiResponse(responseCode = "409", description = "实验状态不允许启动")
    })
    @PostMapping("/experiments/{id}/start")
    public ResponseEntity<Void> startExperiment(@PathVariable Long id) {
        abTestService.startExperiment(id);
        auditUpdate(AuditLogService.ENTITY_AB_TEST, String.valueOf(id),
                "A/B experiment started: " + id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "暂停实验")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "实验暂停成功"),
        @ApiResponse(responseCode = "404", description = "实验不存在")
    })
    @PostMapping("/experiments/{id}/pause")
    public ResponseEntity<Void> pauseExperiment(@PathVariable Long id) {
        abTestService.pauseExperiment(id);
        auditUpdate(AuditLogService.ENTITY_AB_TEST, String.valueOf(id),
                "A/B experiment paused: " + id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "停止实验")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "实验停止成功"),
        @ApiResponse(responseCode = "404", description = "实验不存在")
    })
    @PostMapping("/experiments/{id}/stop")
    public ResponseEntity<Void> stopExperiment(@PathVariable Long id) {
        abTestService.stopExperiment(id);
        auditUpdate(AuditLogService.ENTITY_AB_TEST, String.valueOf(id),
                "A/B experiment stopped: " + id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "获取运行中的实验")
    @ApiResponse(responseCode = "200", description = "返回运行中的实验列表")
    @GetMapping("/experiments/running")
    public ResponseEntity<List<AbTestService.Experiment>> getRunningExperiments() {
        return ResponseEntity.ok(abTestService.getRunningExperiments());
    }

    @Operation(summary = "获取变体分配", description = "根据 sessionId 计算该用户应分配到的变体")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "返回变体分配结果"),
        @ApiResponse(responseCode = "404", description = "实验不存在")
    })
    @GetMapping("/experiments/{id}/variant")
    public ResponseEntity<VariantResponse> getVariant(
            @PathVariable Long id,
            @RequestParam String sessionId) {
        String variant = abTestService.getVariantForSession(sessionId, id);
        return ResponseEntity.ok(VariantResponse.of(variant));
    }

    @Operation(summary = "记录实验结果")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "结果记录成功"),
        @ApiResponse(responseCode = "404", description = "实验不存在")
    })
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

    @Operation(summary = "分析实验结果")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "返回实验分析结果"),
        @ApiResponse(responseCode = "404", description = "实验不存在")
    })
    @GetMapping("/experiments/{id}/analysis")
    public ResponseEntity<AbTestService.ExperimentAnalysis> analyzeExperiment(@PathVariable Long id) {
        return ResponseEntity.ok(abTestService.analyzeExperiment(id));
    }

    @Operation(summary = "获取实验结果列表")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "返回实验结果分页列表"),
        @ApiResponse(responseCode = "404", description = "实验不存在")
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
     * 记录结果请求体
     */
    public static class ResultRequest {
        public String variantName;
        public String sessionId;
        public String query;
        public List<Long> retrievedDocIds;
        public Map<String, Double> metrics;
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
