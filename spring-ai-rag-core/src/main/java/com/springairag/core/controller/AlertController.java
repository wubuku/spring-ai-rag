package com.springairag.core.controller;

import com.springairag.api.dto.AlertActionResponse;
import com.springairag.api.dto.FireAlertRequest;
import com.springairag.api.dto.FireAlertResponse;
import com.springairag.api.dto.ResolveAlertRequest;
import com.springairag.api.dto.SilenceAlertRequest;
import com.springairag.api.dto.SilenceScheduleRequest;
import com.springairag.api.dto.SloConfigRequest;
import com.springairag.core.entity.RagSilenceSchedule;
import com.springairag.core.entity.RagSloConfig;
import com.springairag.core.repository.RagSilenceScheduleRepository;
import com.springairag.core.repository.SloConfigRepository;
import com.springairag.core.service.AlertService;
import com.springairag.core.service.AuditLogService;
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
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Alert management controller
 *
 * <p>Provides alert query, trigger, resolve, silence, and SLO status check endpoints.
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/alerts")
@Tag(name = "RAG Alerts", description = "Alert management (threshold alerts + SLO monitoring)")
public class AlertController {

    private final AlertService alertService;
    private final SloConfigRepository sloConfigRepository;
    private final RagSilenceScheduleRepository silenceScheduleRepository;
    private AuditLogService auditLogService;  // optional: null when RagAuditLogRepository unavailable

    public AlertController(AlertService alertService, SloConfigRepository sloConfigRepository,
                           RagSilenceScheduleRepository silenceScheduleRepository,
                           @Autowired(required = false) AuditLogService auditLogService) {
        this.alertService = alertService;
        this.sloConfigRepository = sloConfigRepository;
        this.silenceScheduleRepository = silenceScheduleRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Get active alerts
     */
    @Operation(summary = "Get active alerts", description = "Query all unresolved alert records, ordered by trigger time descending.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Returns list of active alerts")
    })
    @GetMapping("/active")
    public ResponseEntity<List<AlertService.AlertRecord>> getActiveAlerts() {
        return ResponseEntity.ok(alertService.getActiveAlerts());
    }

    /**
     * Get alert history
     */
    @Operation(summary = "Get alert history", description = "Query alert records by time range, supports filtering by severity and type.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Returns alert history list"),
        @ApiResponse(responseCode = "400", description = "Invalid date format")
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
     * Get alert statistics
     */
    @Operation(summary = "Get alert statistics", description = "Statistics on total alerts, per-severity counts, and alert frequency within the specified time range.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Returns alert statistics"),
        @ApiResponse(responseCode = "400", description = "Invalid date format")
    })
    @GetMapping("/stats")
    public ResponseEntity<AlertService.AlertStats> getAlertStats(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        return ResponseEntity.ok(alertService.getAlertStats(
                ZonedDateTime.parse(startDate), ZonedDateTime.parse(endDate)));
    }

    /**
     * Resolve alert
     */
    @Operation(summary = "Resolve alert", description = "Mark an alert as resolved.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Alert resolved"),
        @ApiResponse(responseCode = "404", description = "Alert not found")
    })
    @PostMapping("/{alertId}/resolve")
    public ResponseEntity<AlertActionResponse> resolveAlert(
            @PathVariable Long alertId,
            @Valid @RequestBody ResolveAlertRequest body) {
        alertService.resolveAlert(alertId, body.resolution() != null ? body.resolution() : "");

        auditUpdate(AuditLogService.ENTITY_ALERT,
                String.valueOf(alertId),
                "Alert resolved: " + (body.resolution() != null ? body.resolution() : ""));

        return ResponseEntity.ok(AlertActionResponse.ok("Alert resolved"));
    }

    /**
     * Silence alert
     */
    @Operation(summary = "Silence alert", description = "Temporarily suppress a specified alert, preventing the same type from triggering within the specified duration.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Alert silenced"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    })
    @PostMapping("/silence")
    public ResponseEntity<AlertActionResponse> silenceAlert(
            @Valid @RequestBody SilenceAlertRequest body) {
        int duration = body.durationMinutes() != null ? body.durationMinutes() : 60;
        alertService.silenceAlert(body.alertKey(), duration);

        auditUpdate(AuditLogService.ENTITY_ALERT,
                body.alertKey(),
                "Alert silenced: " + body.alertKey() + " for " + duration + " minutes",
                Map.of("alertKey", body.alertKey() != null ? body.alertKey() : "",
                        "durationMinutes", duration));

        return ResponseEntity.ok(AlertActionResponse.ok(
                "Alert silenced: " + body.alertKey() + " (" + duration + " minutes)"));
    }

    /**
     * Get currently silenced alerts
     */
    @Operation(summary = "Get silenced alerts", description = "Returns all currently silenced alerts with their expiration times. Expired entries are automatically removed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Returns map of alert key to silence expiration time")
    })
    @GetMapping("/silence")
    public ResponseEntity<Map<String, ZonedDateTime>> getSilencedAlerts() {
        return ResponseEntity.ok(alertService.getSilencedAlerts());
    }

    /**
     * Unsilence alert
     */
    @Operation(summary = "Unsilence alert", description = "Manually lift the silence on a specified alert before its silence period expires.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Alert unsilenced or was not silenced"),
        @ApiResponse(responseCode = "400", description = "Invalid alert key")
    })
    @DeleteMapping("/silence/{alertKey}")
    public ResponseEntity<AlertActionResponse> unsilenceAlert(@PathVariable String alertKey) {
        boolean wasSilenced = alertService.unsilenceAlert(alertKey);

        auditUpdate(AuditLogService.ENTITY_ALERT,
                alertKey,
                wasSilenced ? "Alert unsilenced: " + alertKey : "Alert was not silenced: " + alertKey);

        if (wasSilenced) {
            return ResponseEntity.ok(AlertActionResponse.ok("Alert unsilenced: " + alertKey));
        } else {
            return ResponseEntity.ok(AlertActionResponse.ok("Alert was not silenced: " + alertKey));
        }
    }

    /**
     * Manually fire an alert
     */
    @Operation(summary = "Manually fire an alert", description = "Manually create an alert record (used for external system integration).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Alert fired"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    })
    @PostMapping("/fire")
    public ResponseEntity<FireAlertResponse> fireAlert(
            @Valid @RequestBody FireAlertRequest body) {
        String severity = body.severity() != null ? body.severity() : "WARNING";
        Map<String, Object> metrics = body.metrics() != null ? body.metrics() : Map.of();
        Long alertId = alertService.fireAlert(
                body.alertType(), body.alertName(), body.message(),
                severity, metrics);

        auditCreate(AuditLogService.ENTITY_ALERT,
                String.valueOf(alertId),
                "Alert fired: " + body.alertName() + " [" + severity + "]",
                Map.of("alertType", body.alertType() != null ? body.alertType() : "",
                        "alertName", body.alertName() != null ? body.alertName() : "",
                        "severity", severity));

        return ResponseEntity.ok(FireAlertResponse.of(alertId));
    }

    /**
     * Check all SLO statuses
     */
    @Operation(summary = "Check all SLO statuses", description = "Check compliance status of all SLOs (availability, latency, quality).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Returns all SLO statuses"),
        @ApiResponse(responseCode = "400", description = "Invalid date format")
    })
    @GetMapping("/slos")
    public ResponseEntity<Map<String, AlertService.SloStatus>> checkAllSlos(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        return ResponseEntity.ok(alertService.checkAllSlos(
                ZonedDateTime.parse(startDate), ZonedDateTime.parse(endDate)));
    }

    /**
     * Check single SLO status
     */
    @Operation(summary = "Check single SLO status", description = "Check compliance status of a specific SLO.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Returns SLO status"),
        @ApiResponse(responseCode = "400", description = "Invalid date format")
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

        auditCreate(AuditLogService.ENTITY_SLO_CONFIG,
                request.getSloName(),
                "SLO config created: " + request.getSloName());

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
                    RagSloConfig saved = sloConfigRepository.save(existing);

                    auditUpdate(AuditLogService.ENTITY_SLO_CONFIG,
                            sloName,
                            "SLO config updated: " + sloName);

                    return ResponseEntity.ok(saved);
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

        auditDelete(AuditLogService.ENTITY_SLO_CONFIG,
                sloName,
                "SLO config deleted: " + sloName);

        return ResponseEntity.noContent().build();
    }

    // ==================== Silence Schedule CRUD ====================

    @Operation(summary = "Create silence schedule", description = "Create a new silence schedule for alert downtime/suppress periods.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Silence schedule created"),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "409", description = "Schedule with this name already exists")
    })
    @PostMapping("/silence-schedules")
    public ResponseEntity<RagSilenceSchedule> createSilenceSchedule(
            @Valid @RequestBody SilenceScheduleRequest request) {
        if (silenceScheduleRepository.findByName(request.getName()).isPresent()) {
            return ResponseEntity.status(409).build();
        }
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        schedule.setName(request.getName());
        schedule.setAlertKey(request.getAlertKey());
        schedule.setSilenceType(request.getSilenceType());
        schedule.setStartTime(request.getStartTime());
        schedule.setEndTime(request.getEndTime());
        schedule.setDescription(request.getDescription());
        schedule.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        schedule.setMetadata(request.getMetadata());
        schedule.setCreatedAt(ZonedDateTime.now());
        RagSilenceSchedule saved = silenceScheduleRepository.save(schedule);

        auditCreate(AuditLogService.ENTITY_SILENCE_SCHEDULE,
                request.getName(),
                "Silence schedule created: " + request.getName());

        return ResponseEntity.status(201).body(saved);
    }

    @Operation(summary = "List all silence schedules", description = "List all configured silence schedules.")
    @GetMapping("/silence-schedules")
    public ResponseEntity<List<RagSilenceSchedule>> listSilenceSchedules() {
        return ResponseEntity.ok(silenceScheduleRepository.findAll());
    }

    @Operation(summary = "Get silence schedule by name", description = "Get a specific silence schedule by its name.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Silence schedule found"),
        @ApiResponse(responseCode = "404", description = "Silence schedule not found")
    })
    @GetMapping("/silence-schedules/{name}")
    public ResponseEntity<RagSilenceSchedule> getSilenceSchedule(@PathVariable String name) {
        return silenceScheduleRepository.findByName(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Update silence schedule", description = "Update an existing silence schedule.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Silence schedule updated"),
        @ApiResponse(responseCode = "404", description = "Silence schedule not found")
    })
    @PutMapping("/silence-schedules/{name}")
    public ResponseEntity<RagSilenceSchedule> updateSilenceSchedule(
            @PathVariable String name,
            @Valid @RequestBody SilenceScheduleRequest request) {
        return silenceScheduleRepository.findByName(name)
                .map(existing -> {
                    existing.setAlertKey(request.getAlertKey());
                    existing.setSilenceType(request.getSilenceType());
                    existing.setStartTime(request.getStartTime());
                    existing.setEndTime(request.getEndTime());
                    existing.setDescription(request.getDescription());
                    if (request.getEnabled() != null) {
                        existing.setEnabled(request.getEnabled());
                    }
                    existing.setMetadata(request.getMetadata());
                    existing.setUpdatedAt(ZonedDateTime.now());
                    RagSilenceSchedule saved = silenceScheduleRepository.save(existing);

                    auditUpdate(AuditLogService.ENTITY_SILENCE_SCHEDULE,
                            name,
                            "Silence schedule updated: " + name);

                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete silence schedule", description = "Delete a silence schedule by its name.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Silence schedule deleted"),
        @ApiResponse(responseCode = "404", description = "Silence schedule not found")
    })
    @DeleteMapping("/silence-schedules/{name}")
    public ResponseEntity<Void> deleteSilenceSchedule(@PathVariable String name) {
        if (!silenceScheduleRepository.findByName(name).isPresent()) {
            return ResponseEntity.notFound().build();
        }
        silenceScheduleRepository.deleteByName(name);

        auditDelete(AuditLogService.ENTITY_SILENCE_SCHEDULE,
                name,
                "Silence schedule deleted: " + name);

        return ResponseEntity.noContent().build();
    }

    // Null-safe audit logging helpers
    private void auditCreate(String entityType, String entityId, String message) {
        if (auditLogService != null) auditLogService.logCreate(entityType, entityId, message);
    }
    private void auditCreate(String entityType, String entityId, String message, Map<String, Object> details) {
        if (auditLogService != null) auditLogService.logCreate(entityType, entityId, message, details);
    }
    private void auditUpdate(String entityType, String entityId, String message) {
        if (auditLogService != null) auditLogService.logUpdate(entityType, entityId, message);
    }
    private void auditUpdate(String entityType, String entityId, String message, Map<String, Object> details) {
        if (auditLogService != null) auditLogService.logUpdate(entityType, entityId, message, details);
    }
    private void auditDelete(String entityType, String entityId, String message) {
        if (auditLogService != null) auditLogService.logDelete(entityType, entityId, message);
    }
}
