package com.springairag.core.controller;

import com.springairag.api.dto.AlertNotificationDeliveryPageResponse;
import com.springairag.api.dto.AlertNotificationDeliveryResponse;
import com.springairag.core.alertdelivery.AlertNotificationDeliveryService;
import com.springairag.core.service.AlertManagementAuthorization;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Alert operator 管理面的 durable provider delivery receipt API。 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/alerts/notification-deliveries")
@Validated
public class AlertNotificationDeliveryController {

    private final AlertNotificationDeliveryService deliveryService;
    private final AlertManagementAuthorization authorization;
    private final AuditLogService auditLogService;

    @Autowired
    public AlertNotificationDeliveryController(
            AlertNotificationDeliveryService deliveryService,
            AlertManagementAuthorization authorization,
            @Autowired(required = false) AuditLogService auditLogService) {
        this.deliveryService = deliveryService;
        this.authorization = authorization;
        this.auditLogService = auditLogService;
    }

    @ModelAttribute
    void requireOperator(HttpServletRequest request) {
        authorization.requireAllowed(request);
    }

    @GetMapping
    @Operation(summary = "List alert notification delivery receipts")
    public ResponseEntity<AlertNotificationDeliveryPageResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) @Positive Long alertId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Size(max = 1024) String cursor) {
        return ResponseEntity.ok(deliveryService.query(
                status, provider, alertId, limit, cursor));
    }

    @PostMapping("/{deliveryId}/retry")
    @Operation(summary = "Retry a failed alert notification delivery")
    public ResponseEntity<AlertNotificationDeliveryResponse> retry(
            @PathVariable UUID deliveryId) {
        AlertNotificationDeliveryResponse response =
                deliveryService.retry(deliveryId);
        if (auditLogService != null) {
            auditLogService.logUpdate(
                    "AlertNotificationDelivery",
                    deliveryId.toString(),
                    "Alert notification delivery retry requested",
                    Map.of(
                            "provider", response.provider(),
                            "status", response.status(),
                            "manualRetryCount", response.manualRetryCount()));
        }
        return ResponseEntity.ok(response);
    }
}
