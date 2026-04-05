package com.springairag.core.controller;

import com.springairag.api.dto.AlertActionResponse;
import com.springairag.api.dto.FireAlertRequest;
import com.springairag.api.dto.FireAlertResponse;
import com.springairag.api.dto.ResolveAlertRequest;
import com.springairag.api.dto.SilenceAlertRequest;
import com.springairag.api.dto.SloConfigRequest;
import com.springairag.core.entity.RagSloConfig;
import com.springairag.core.repository.SloConfigRepository;
import com.springairag.core.service.AlertService;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * 告警管理控制器
 *
 * <p>提供告警查询、触发、解决、静默，以及 SLO 状态检查接口。
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/alerts")
@Tag(name = "RAG Alerts", description = "告警管理（阈值告警 + SLO 监控）")
public class AlertController {

    private final AlertService alertService;
    private final SloConfigRepository sloConfigRepository;

    public AlertController(AlertService alertService, SloConfigRepository sloConfigRepository) {
        this.alertService = alertService;
        this.sloConfigRepository = sloConfigRepository;
    }

    /**
     * 获取活跃告警
     */
    @Operation(summary = "获取活跃告警", description = "查询所有未解决的告警记录，按触发时间倒序。")
    @ApiResponse(responseCode = "200", description = "返回活跃告警列表")
    @GetMapping("/active")
    public ResponseEntity<List<AlertService.AlertRecord>> getActiveAlerts() {
        return ResponseEntity.ok(alertService.getActiveAlerts());
    }

    /**
     * 获取告警历史
     */
    @Operation(summary = "获取告警历史", description = "按时间范围查询告警记录，支持按严重程度和类型过滤。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "返回告警历史列表"),
        @ApiResponse(responseCode = "400", description = "日期格式无效")
    })
    @GetMapping("/history")
    public ResponseEntity<List<AlertService.AlertRecord>> getAlertHistory(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String alertType) {
        return ResponseEntity.ok(alertService.getAlertHistory(
                ZonedDateTime.parse(startDate), ZonedDateTime.parse(endDate), severity, alertType));
    }

    /**
     * 获取告警统计
     */
    @Operation(summary = "获取告警统计", description = "统计指定时间范围内的告警总数、各严重程度数量和告警频率。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "返回告警统计"),
        @ApiResponse(responseCode = "400", description = "日期格式无效")
    })
    @GetMapping("/stats")
    public ResponseEntity<AlertService.AlertStats> getAlertStats(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        return ResponseEntity.ok(alertService.getAlertStats(
                ZonedDateTime.parse(startDate), ZonedDateTime.parse(endDate)));
    }

    /**
     * 解决告警
     */
    @Operation(summary = "解决告警", description = "将告警标记为已解决。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "告警已解决"),
        @ApiResponse(responseCode = "404", description = "告警不存在")
    })
    @PostMapping("/{alertId}/resolve")
    public ResponseEntity<AlertActionResponse> resolveAlert(
            @PathVariable Long alertId,
            @Valid @RequestBody ResolveAlertRequest body) {
        alertService.resolveAlert(alertId, body.resolution() != null ? body.resolution() : "");
        return ResponseEntity.ok(AlertActionResponse.ok("Alert resolved"));
    }

    /**
     * 静默告警
     */
    @Operation(summary = "静默告警", description = "临时屏蔽指定告警，指定时间内不再触发同类型告警。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "告警已静默"),
        @ApiResponse(responseCode = "400", description = "请求参数无效")
    })
    @PostMapping("/silence")
    public ResponseEntity<AlertActionResponse> silenceAlert(
            @Valid @RequestBody SilenceAlertRequest body) {
        int duration = body.durationMinutes() != null ? body.durationMinutes() : 60;
        alertService.silenceAlert(body.alertKey(), duration);
        return ResponseEntity.ok(AlertActionResponse.ok(
                "Alert silenced: " + body.alertKey() + " (" + duration + " minutes)"));
    }

    /**
     * 手动触发告警
     */
    @Operation(summary = "手动触发告警", description = "手动创建一条告警记录（用于外部系统集成）。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "告警已触发"),
        @ApiResponse(responseCode = "400", description = "请求参数无效")
    })
    @PostMapping("/fire")
    public ResponseEntity<FireAlertResponse> fireAlert(
            @Valid @RequestBody FireAlertRequest body) {
        String severity = body.severity() != null ? body.severity() : "WARNING";
        Map<String, Object> metrics = body.metrics() != null ? body.metrics() : Map.of();
        Long alertId = alertService.fireAlert(
                body.alertType(), body.alertName(), body.message(),
                severity, metrics);
        return ResponseEntity.ok(FireAlertResponse.of(alertId));
    }

    /**
     * 检查所有 SLO 状态
     */
    @Operation(summary = "检查所有 SLO", description = "检查所有 SLO（可用性、延迟、质量）的达标状态。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "返回所有 SLO 状态"),
        @ApiResponse(responseCode = "400", description = "日期格式无效")
    })
    @GetMapping("/slos")
    public ResponseEntity<Map<String, AlertService.SloStatus>> checkAllSlos(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        return ResponseEntity.ok(alertService.checkAllSlos(
                ZonedDateTime.parse(startDate), ZonedDateTime.parse(endDate)));
    }

    /**
     * 检查单个 SLO
     */
    @Operation(summary = "检查单个 SLO", description = "检查指定 SLO 的达标状态。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "返回 SLO 状态"),
        @ApiResponse(responseCode = "400", description = "日期格式无效")
    })
    @GetMapping("/slos/{sloName}")
    public ResponseEntity<AlertService.SloStatus> checkSlo(
            @PathVariable String sloName,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        return ResponseEntity.ok(alertService.checkSlo(
                sloName, ZonedDateTime.parse(startDate), ZonedDateTime.parse(endDate)));
    }

    // ==================== SLO Config CRUD ====================

    @Operation(summary = "Create SLO config", description = "Create a new SLO configuration.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "SLO config created"),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "409", description = "SLO config with this name already exists")
    })
    @PostMapping("/slos")
    public ResponseEntity<RagSloConfig> createSloConfig(@Valid @RequestBody SloConfigRequest request) {
        if (sloConfigRepository.findBySloName(request.getSloName()).isPresent()) {
            return ResponseEntity.status(409).build();
        }
        RagSloConfig config = new RagSloConfig();
        config.setSloName(request.getSloName());
        config.setSloType(request.getSloType());
        config.setTargetValue(request.getTargetValue());
        config.setUnit(request.getUnit());
        config.setDescription(request.getDescription());
        config.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        config.setMetadata(request.getMetadata());
        config.setCreatedAt(ZonedDateTime.now());
        RagSloConfig saved = sloConfigRepository.save(config);
        return ResponseEntity.status(201).body(saved);
    }

    @Operation(summary = "List all SLO configs", description = "List all SLO configurations.")
    @GetMapping("/slos/configs")
    public ResponseEntity<List<RagSloConfig>> listSloConfigs() {
        return ResponseEntity.ok(sloConfigRepository.findAll());
    }

    @Operation(summary = "Get SLO config by name", description = "Get a specific SLO configuration by its name.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SLO config found"),
        @ApiResponse(responseCode = "404", description = "SLO config not found")
    })
    @GetMapping("/slos/configs/{sloName}")
    public ResponseEntity<RagSloConfig> getSloConfig(@PathVariable String sloName) {
        return sloConfigRepository.findBySloName(sloName)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Update SLO config", description = "Update an existing SLO configuration.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SLO config updated"),
        @ApiResponse(responseCode = "404", description = "SLO config not found")
    })
    @PutMapping("/slos/configs/{sloName}")
    public ResponseEntity<RagSloConfig> updateSloConfig(
            @PathVariable String sloName,
            @Valid @RequestBody SloConfigRequest request) {
        return sloConfigRepository.findBySloName(sloName)
                .map(existing -> {
                    existing.setSloType(request.getSloType());
                    existing.setTargetValue(request.getTargetValue());
                    existing.setUnit(request.getUnit());
                    existing.setDescription(request.getDescription());
                    if (request.getEnabled() != null) {
                        existing.setEnabled(request.getEnabled());
                    }
                    existing.setMetadata(request.getMetadata());
                    existing.setUpdatedAt(ZonedDateTime.now());
                    return ResponseEntity.ok(sloConfigRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete SLO config", description = "Delete an SLO configuration by its name.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "SLO config deleted"),
        @ApiResponse(responseCode = "404", description = "SLO config not found")
    })
    @DeleteMapping("/slos/configs/{sloName}")
    public ResponseEntity<Void> deleteSloConfig(@PathVariable String sloName) {
        if (!sloConfigRepository.findBySloName(sloName).isPresent()) {
            return ResponseEntity.notFound().build();
        }
        sloConfigRepository.deleteBySloName(sloName);
        return ResponseEntity.noContent().build();
    }
}
