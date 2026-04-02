package com.springairag.core.controller;

import com.springairag.core.service.AlertService;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 获取活跃告警
     */
    @Operation(summary = "获取活跃告警", description = "查询所有未解决的告警记录，按触发时间倒序。")
    @GetMapping("/active")
    public ResponseEntity<List<AlertService.AlertRecord>> getActiveAlerts() {
        return ResponseEntity.ok(alertService.getActiveAlerts());
    }

    /**
     * 获取告警历史
     */
    @Operation(summary = "获取告警历史", description = "按时间范围查询告警记录，支持按严重程度和类型过滤。")
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
    @PostMapping("/{alertId}/resolve")
    public ResponseEntity<Map<String, String>> resolveAlert(
            @PathVariable Long alertId,
            @RequestBody Map<String, String> body) {
        alertService.resolveAlert(alertId, body.getOrDefault("resolution", ""));
        return ResponseEntity.ok(Map.of("message", "告警已解决", "alertId", String.valueOf(alertId)));
    }

    /**
     * 静默告警
     */
    @Operation(summary = "静默告警", description = "临时屏蔽指定告警，指定时间内不再触发同类型告警。")
    @PostMapping("/silence")
    public ResponseEntity<Map<String, String>> silenceAlert(@RequestBody Map<String, Object> body) {
        String alertKey = (String) body.get("alertKey");
        int durationMinutes = (int) body.getOrDefault("durationMinutes", 60);
        alertService.silenceAlert(alertKey, durationMinutes);
        return ResponseEntity.ok(Map.of(
                "message", "告警已静默",
                "alertKey", alertKey,
                "durationMinutes", String.valueOf(durationMinutes)));
    }

    /**
     * 手动触发告警
     */
    @Operation(summary = "手动触发告警", description = "手动创建一条告警记录（用于外部系统集成）。")
    @PostMapping("/fire")
    public ResponseEntity<Map<String, Object>> fireAlert(@RequestBody Map<String, Object> body) {
        String alertType = (String) body.get("alertType");
        String alertName = (String) body.get("alertName");
        String message = (String) body.get("message");
        String severity = (String) body.getOrDefault("severity", "WARNING");
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) body.getOrDefault("metrics", Map.of());

        Long alertId = alertService.fireAlert(alertType, alertName, message, severity, metrics);
        return ResponseEntity.ok(Map.of("alertId", alertId, "status", "ACTIVE"));
    }

    /**
     * 检查所有 SLO 状态
     */
    @Operation(summary = "检查所有 SLO", description = "检查所有 SLO（可用性、延迟、质量）的达标状态。")
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
    @GetMapping("/slos/{sloName}")
    public ResponseEntity<AlertService.SloStatus> checkSlo(
            @PathVariable String sloName,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        return ResponseEntity.ok(alertService.checkSlo(
                sloName, ZonedDateTime.parse(startDate), ZonedDateTime.parse(endDate)));
    }
}
