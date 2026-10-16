package com.springairag.core.controller;

import com.springairag.api.dto.AuditLogPageResponse;
import com.springairag.api.dto.AuditLogResponse;
import com.springairag.api.dto.DataErasureApplyRequest;
import com.springairag.api.dto.DataErasurePreviewRequest;
import com.springairag.api.dto.DataErasurePreviewResponse;
import com.springairag.api.dto.DataErasureResultResponse;
import com.springairag.api.dto.DataErasureStatusResponse;
import com.springairag.api.dto.DataGovernancePolicyResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagAuditLog;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import com.springairag.core.security.ApiRoutePolicy;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.CollectionPurgeAuthorization;
import com.springairag.core.service.DataErasureService;
import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Client-visible governance policy and management-only audit view.
 */
@RestController
@ApiVersion("v1")
@ApiRoutePolicy(access = ApiRoutePolicy.Access.READ)
@RequestMapping("/rag")
@Tag(name = "Data Governance", description = "Retention policy and redacted audit access")
public class DataGovernanceController {

    private static final String POLICY_VERSION = "1";
    private static final String REDACTION_VERSION = "sensitive-data-masking-v1";
    private static final int MAX_AUDIT_PAGE_SIZE = 200;
    private static final int MAX_AUDIT_RANGE_DAYS = 31;

    private final RagProperties properties;
    private final AuditLogService auditLogService;
    private final CollectionPurgeAuthorization collectionPurgeAuthorization;
    private final DataErasureService dataErasureService; // optional-claim: WebMvc slice 可只装配既有治理依赖；擦除端点在服务缺失时显式返回 SERVICE_UNAVAILABLE，不影响旧 policy/audit 端点

    @Autowired
    public DataGovernanceController(
            RagProperties properties,
            AuditLogService auditLogService,
            CollectionPurgeAuthorization collectionPurgeAuthorization,
            @Autowired(required = false)
            DataErasureService dataErasureService) {
        this.properties = properties;
        this.auditLogService = auditLogService;
        this.collectionPurgeAuthorization = collectionPurgeAuthorization;
        this.dataErasureService = dataErasureService;
    }

    @GetMapping("/data-governance/policy")
    @Operation(summary = "Get data governance policy",
            description = "Returns retention, redaction and bounded cleanup capabilities without content or secrets.")
    @ApiResponse(responseCode = "200", description = "Governance policy returned")
    public DataGovernancePolicyResponse policy(HttpServletRequest request) {
        var governance = properties.getDataGovernance();
        boolean automaticCleanup = governance.isEnabled() && governance.isCleanupEnabled();
        return new DataGovernancePolicyResponse(
                POLICY_VERSION,
                REDACTION_VERSION,
                governance.isEnabled(),
                List.of(
                        new DataGovernancePolicyResponse.RetentionPolicy(
                                "AUDIT_LOG", governance.isEnabled(),
                                governance.getAuditLogRetentionDays(), automaticCleanup),
                        new DataGovernancePolicyResponse.RetentionPolicy(
                                "CLIENT_ERROR", governance.isEnabled(),
                                governance.getClientErrorRetentionDays(), automaticCleanup),
                        new DataGovernancePolicyResponse.RetentionPolicy(
                                "RETRIEVAL_EVALUATION", governance.isEnabled(),
                                governance.getEvaluationRetentionDays(), automaticCleanup),
                        new DataGovernancePolicyResponse.RetentionPolicy(
                                "CHAT_HISTORY", true,
                                properties.getMemory().getMessageTtlDays(), false),
                        new DataGovernancePolicyResponse.RetentionPolicy(
                                "RETRIEVAL_DIAGNOSTICS",
                                properties.getRetrievalDiagnostics().isEnabled()
                                        && properties.getRetrievalDiagnostics().isPersist(),
                                properties.getRetrievalDiagnostics().getRetentionDays(), automaticCleanup),
                        new DataGovernancePolicyResponse.RetentionPolicy(
                                "LLM_USAGE", properties.getUsage().isEnabled(),
                                properties.getUsage().getRetentionDays(),
                                properties.getUsage().isCleanupEnabled()),
                        new DataGovernancePolicyResponse.RetentionPolicy(
                                "INTEGRATION_OBSERVABILITY",
                                properties.getIntegrationObservability().isEnabled(),
                                properties.getIntegrationObservability().retentionDays(), automaticCleanup),
                        new DataGovernancePolicyResponse.RetentionPolicy(
                                "BUDGET_RESERVATION", properties.getLlmBudget().isEnabled(),
                                properties.getLlmBudget().getRetentionDays(), automaticCleanup)),
                new DataGovernancePolicyResponse.Capabilities(
                        isAuditManagementCaller(request),
                        true,
                        true,
                        collectionPurgeAuthorization.isAllowed(request),
                        automaticCleanup),
                new DataGovernancePolicyResponse.Cleanup(
                        automaticCleanup,
                        governance.getCleanupBatchSize(),
                        governance.getCleanupMaxBatches(),
                        governance.getCleanupCron()),
                new DataGovernancePolicyResponse.Erasure(
                        governance.isEnabled(),
                        dataErasureService != null,
                        dataErasureService != null,
                        dataErasureService != null,
                        "/api/v1/rag/data-governance/erasures/preview",
                        "/api/v1/rag/data-governance/erasures/{operationId}/apply",
                        "/api/v1/rag/data-governance/erasures/{operationId}"),
                "central-retention-v1");
    }

    @PostMapping("/data-governance/erasures/preview")
    @ApiRoutePolicy(access = ApiRoutePolicy.Access.WRITE)
    @Operation(summary = "Preview owner-scoped data erasure",
            description = "Creates a short-lived, content-free erasure confirmation preview.")
    public DataErasurePreviewResponse previewErasure(
            @Valid @RequestBody(required = false) DataErasurePreviewRequest request,
            HttpServletRequest httpRequest) {
        return requireDataErasureService().preview(request, httpRequest);
    }

    @GetMapping("/data-governance/erasures/{operationId}")
    @Operation(summary = "Get data erasure status")
    public DataErasureStatusResponse erasureStatus(
            @PathVariable UUID operationId,
            HttpServletRequest httpRequest) {
        return requireDataErasureService().status(operationId, httpRequest);
    }

    @PostMapping("/data-governance/erasures/{operationId}/apply")
    @ApiRoutePolicy(access = ApiRoutePolicy.Access.WRITE)
    @Operation(summary = "Apply a confirmed data erasure")
    public DataErasureResultResponse applyErasure(
            @PathVariable UUID operationId,
            @Valid @RequestBody DataErasureApplyRequest request,
            HttpServletRequest httpRequest) {
        if (request == null || !operationId.equals(request.operationId())) {
            throw badRequest("operationId in the path and body must match");
        }
        return requireDataErasureService().apply(request, httpRequest);
    }

    private DataErasureService requireDataErasureService() {
        if (dataErasureService == null) {
            throw new RagException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Data erasure service is unavailable");
        }
        return dataErasureService;
    }

    @GetMapping("/audit-logs")
    @ApiRoutePolicy(access = ApiRoutePolicy.Access.MANAGEMENT)
    @Operation(summary = "Query redacted audit logs",
            description = "Returns a bounded management view without details, prompts, provider payloads or secrets.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Audit page returned"),
            @ApiResponse(responseCode = "400", description = "Invalid pagination or time range"),
            @ApiResponse(responseCode = "403", description = "Management access required")
    })
    public AuditLogPageResponse auditLogs(
            @Parameter(in = ParameterIn.QUERY, description = "Zero-based page number")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(in = ParameterIn.QUERY, description = "Page size, from 1 to 200")
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            HttpServletRequest request) {
        requireAuditAccess(request);
        if (page < 0 || page > 1_000_000) {
            throw badRequest("page must be between 0 and 1000000");
        }
        if (size < 1 || size > MAX_AUDIT_PAGE_SIZE) {
            throw badRequest("size must be between 1 and 200");
        }

        TimeRange range = parseRange(from, to);
        Page<RagAuditLog> result = auditLogService.search(
                normalizeFilter(operation, "operation"),
                normalizeFilter(entityType, "entityType"),
                normalizeFilter(entityId, "entityId"),
                range == null ? null : range.from(),
                range == null ? null : range.to(),
                PageRequest.of(page, size));
        List<AuditLogResponse> items = result.getContent().stream()
                .map(this::toResponse)
                .toList();
        return new AuditLogPageResponse(
                items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    private AuditLogResponse toResponse(RagAuditLog entry) {
        return new AuditLogResponse(
                entry.getId(), entry.getOperation(), entry.getEntityType(),
                entry.getEntityId(), entry.getSessionId(), entry.getOperator(),
                SensitiveDataMaskingConverter.maskSensitiveData(entry.getDescription()),
                entry.getClientIp(), entry.getTraceId(),
                entry.getCreatedAt());
    }

    private void requireAuditAccess(HttpServletRequest request) {
        if (isAuditManagementCaller(request)) {
            return;
        }
        throw new RagException(
                ErrorCode.FORBIDDEN,
                "Audit log access requires the environment root or a database ADMIN principal");
    }

    private boolean isAuditManagementCaller(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        Object type = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        if (ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(type)
                || Boolean.TRUE.equals(request.getAttribute(
                ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE))) {
            return true;
        }
        return ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(type)
                && request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE)
                instanceof AuthenticatedApiPrincipal principal
                && principal.getRole() == ApiKeyRole.ADMIN;
    }

    private String normalizeFilter(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw badRequest(name + " must not exceed 128 characters");
        }
        return normalized;
    }

    private TimeRange parseRange(String fromValue, String toValue) {
        if ((fromValue == null || fromValue.isBlank())
                && (toValue == null || toValue.isBlank())) {
            return null;
        }
        Instant now = Instant.now();
        Instant from = parseInstant(fromValue, "from");
        Instant to = parseInstant(toValue, "to");
        if (from == null) {
            from = to.minus(Duration.ofDays(MAX_AUDIT_RANGE_DAYS));
        }
        if (to == null) {
            to = now;
        }
        if (from.isAfter(to)) {
            throw badRequest("from must not be after to");
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_AUDIT_RANGE_DAYS)) > 0) {
            throw badRequest("audit time range must not exceed 31 days");
        }
        return new TimeRange(
                ZonedDateTime.ofInstant(from, ZoneOffset.UTC),
                ZonedDateTime.ofInstant(to, ZoneOffset.UTC));
    }

    private Instant parseInstant(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException error) {
            throw badRequest(name + " must be an ISO-8601 instant");
        }
    }

    private RagException badRequest(String message) {
        return new RagException(ErrorCode.BAD_REQUEST, message);
    }

    private record TimeRange(ZonedDateTime from, ZonedDateTime to) {
    }
}
