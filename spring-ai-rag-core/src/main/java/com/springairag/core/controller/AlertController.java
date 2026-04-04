package com.springairag.core.controller;

import com.springairag.api.dto.AlertActionResponse;
import com.springairag.api.dto.FireAlertRequest;
import com.springairag.api.dto.FireAlertResponse;
import com.springairag.api.dto.ResolveAlertRequest;
import com.springairag.api.dto.SilenceAlertRequest;
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

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
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
        return ResponseEntity.ok(AlertActionResponse.ok("告警已解决"));
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
                "告警已静默: " + body.alertKey() + " (" + duration + " 分钟)"));
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
}
