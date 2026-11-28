package com.springairag.core.tenant;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.filter.RequestTraceFilter;
import com.springairag.core.security.ApiRoutePolicy;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2：运营方管理控制台 REST 端点（{@code /api/v1/rag/operator/**}）。
 *
 * <p>所有路由均在 {@link TenantEndpointDeclarationCatalog} 中声明为
 * {@code PLATFORM_ONLY + PLATFORM_CAPABILITY}，统一返回 {@code Cache-Control: no-store}，
 * 且仅允许 Environment Root 或 {@code rag.tenant.lifecycle-actors} 白名单主体访问。</p>
 */
@RestController
@ConditionalOnBean(OperatorConsoleService.class)
@ApiRoutePolicy(access = ApiRoutePolicy.Access.MANAGEMENT)
@RequestMapping("/api/v1/rag/operator")
@Tag(name = "Operator Console", description = "Platform-only multi-tenant operator console APIs")
public class OperatorConsoleController {

    private final OperatorConsoleService service;
    private final PlatformCapabilityRegistry registry;
    private final EnvironmentRootCredentialResolver rootCredentialResolver;

    public OperatorConsoleController(OperatorConsoleService service,
                                     PlatformCapabilityRegistry registry,
                                     EnvironmentRootCredentialResolver rootCredentialResolver) {
        this.service = service;
        this.registry = registry;
        this.rootCredentialResolver = rootCredentialResolver;
    }

    @GetMapping("/tenants")
    public ResponseEntity<?> listTenants(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, "platform");
        if (denied != null) return denied;
        return okNoStore(service.listTenants(status, q, includeDeleted));
    }

    @GetMapping("/tenants/{tenantId}")
    public ResponseEntity<?> getTenantDetail(@PathVariable String tenantId, HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, tenantId);
        if (denied != null) return denied;
        var detail = service.getTenantDetail(tenantId);
        return detail == null
                ? error(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Tenant not found: " + tenantId, request)
                : okNoStore(detail);
    }

    @PostMapping("/tenants")
    public ResponseEntity<?> createTenant(@Valid @RequestBody CreateTenantRequest body,
                                          HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, body.tenantId());
        if (denied != null) return denied;
        try {
            var created = service.createTenant(
                    body.tenantId(),
                    body.displayName(),
                    body.issueBootstrapKey() == null || body.issueBootstrapKey(),
                    body.reason(),
                    resolveActorId(request),
                    resolveActorType(request),
                    rootCredentialResolver.isConfigured());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore())
                    .body(created);
        } catch (IllegalArgumentException bad) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", bad.getMessage(), request);
        } catch (IllegalStateException conflict) {
            return conflictResponse(conflict.getMessage(), request);
        }
    }

    @PatchMapping("/tenants/{tenantId}")
    public ResponseEntity<?> updateTenant(@PathVariable String tenantId,
                                          @Valid @RequestBody UpdateTenantRequest body,
                                          HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, tenantId);
        if (denied != null) return denied;
        try {
            return okNoStore(service.updateTenant(
                    tenantId, body.displayName(), body.reason(),
                    resolveActorId(request), resolveActorType(request)));
        } catch (IllegalStateException err) {
            return conflictResponse(err.getMessage(), request);
        }
    }

    @PostMapping("/tenants/{tenantId}/{action}")
    public ResponseEntity<?> transitionTenant(@PathVariable String tenantId,
                                              @PathVariable String action,
                                              @RequestBody(required = false) ReasonRequest body,
                                              HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, tenantId);
        if (denied != null) return denied;
        try {
            return okNoStore(service.transitionTenant(
                    tenantId, action, body == null ? "" : body.reason(),
                    resolveActorId(request), resolveActorType(request)));
        } catch (IllegalArgumentException bad) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", bad.getMessage(), request);
        } catch (IllegalStateException err) {
            return conflictResponse(err.getMessage(), request);
        }
    }

    @DeleteMapping("/tenants/{tenantId}")
    public ResponseEntity<?> softDeleteTenant(@PathVariable String tenantId,
                                              @RequestBody(required = false) ReasonRequest body,
                                              HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, tenantId);
        if (denied != null) return denied;
        try {
            return okNoStore(service.transitionTenant(
                    tenantId, "soft_delete", body == null ? "" : body.reason(),
                    resolveActorId(request), resolveActorType(request)));
        } catch (IllegalStateException err) {
            return conflictResponse(err.getMessage(), request);
        }
    }

    @GetMapping("/credentials")
    public ResponseEntity<?> listCredentials(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean orphanOnly,
            HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, tenantId == null ? "platform" : tenantId);
        if (denied != null) return denied;
        return okNoStore(service.listCredentials(tenantId, status, orphanOnly));
    }

    @PostMapping("/tenants/{tenantId}/api-keys")
    public ResponseEntity<?> issueTenantApiKey(@PathVariable String tenantId,
                                               @RequestBody(required = false) IssueKeyRequest body,
                                               HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, tenantId);
        if (denied != null) return denied;
        try {
            var issued = service.issueTenantApiKey(
                    tenantId,
                    body == null ? null : body.principalId(),
                    body == null ? null : body.label(),
                    body == null ? "" : body.reason(),
                    resolveActorId(request),
                    resolveActorType(request),
                    rootCredentialResolver.isConfigured());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore())
                    .body(issued);
        } catch (IllegalArgumentException bad) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", bad.getMessage(), request);
        } catch (IllegalStateException conflict) {
            return conflictResponse(conflict.getMessage(), request);
        }
    }

    @DeleteMapping("/api-keys/{keyId}")
    public ResponseEntity<?> revokeApiKey(@PathVariable String keyId,
                                          @RequestBody(required = false) ReasonRequest body,
                                          HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, keyId);
        if (denied != null) return denied;
        boolean revoked = service.revokeApiKey(
                keyId, body == null ? "" : body.reason(),
                resolveActorId(request), resolveActorType(request),
                rootCredentialResolver.isConfigured());
        return revoked
                ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
                : error(HttpStatus.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "Key not found: " + keyId, request);
    }

    @GetMapping("/quotas")
    public ResponseEntity<?> listQuotas(@RequestParam(required = false) String period,
                                        HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, "platform");
        if (denied != null) return denied;
        return okNoStore(service.listQuotas(period));
    }

    @PutMapping("/tenants/{tenantId}/quotas/{period}")
    public ResponseEntity<?> updateQuota(@PathVariable String tenantId,
                                         @PathVariable String period,
                                         @RequestBody UpdateQuotaRequest body,
                                         HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, tenantId);
        if (denied != null) return denied;
        try {
            return okNoStore(service.updateQuota(
                    tenantId, period, body.enabled(), body.maxTokens(), body.reason(),
                    resolveActorId(request), resolveActorType(request)));
        } catch (IllegalArgumentException bad) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", bad.getMessage(), request);
        }
    }

    @GetMapping("/knowledge-health")
    public ResponseEntity<?> listKnowledgeHealth(HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, "platform");
        if (denied != null) return denied;
        return okNoStore(service.listKnowledgeHealth());
    }

    @GetMapping("/compliance")
    public ResponseEntity<?> getCompliance(HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, "platform");
        if (denied != null) return denied;
        return okNoStore(service.getComplianceSnapshot());
    }

    @GetMapping("/audit-events")
    public ResponseEntity<?> listAuditEvents(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, tenantId == null ? "platform" : tenantId);
        if (denied != null) return denied;
        return okNoStore(service.listAuditEvents(tenantId, action, limit));
    }

    @GetMapping("/support-sessions")
    public ResponseEntity<?> listSupportSessions(HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, "platform");
        if (denied != null) return denied;
        return okNoStore(service.listSupportSessions());
    }

    @PostMapping("/support-sessions")
    public ResponseEntity<?> startSupportSession(@Valid @RequestBody StartSupportSessionRequest body,
                                                 HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, body.tenantId());
        if (denied != null) return denied;
        int ttl = body.ttlMinutes() == null ? 15 : body.ttlMinutes();
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(service.startSupportSession(
                        body.tenantId(), body.ticketRef(), body.reason(), ttl,
                        resolveActorId(request), resolveActorType(request)));
    }

    @PostMapping("/support-sessions/{sessionId}/revoke")
    public ResponseEntity<?> revokeSupportSession(@PathVariable String sessionId,
                                                  @RequestBody(required = false) ReasonRequest body,
                                                  HttpServletRequest request) {
        ResponseEntity<ErrorResponse> denied = authorizePlatform(request, sessionId);
        if (denied != null) return denied;
        boolean revoked = service.revokeSupportSession(
                sessionId, body == null ? "" : body.reason(),
                resolveActorId(request), resolveActorType(request));
        return revoked
                ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
                : error(HttpStatus.NOT_FOUND, "SUPPORT_SESSION_NOT_FOUND", "Session not found: " + sessionId, request);
    }

    private ResponseEntity<ErrorResponse> authorizePlatform(HttpServletRequest request, String target) {
        boolean isRoot = rootCredentialResolver.isConfigured()
                && Boolean.TRUE.equals(request.getAttribute(ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE));
        boolean isDbPrincipal = ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(
                request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE))
                && request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE) instanceof String;
        if (!isRoot && !isDbPrincipal) {
            return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only environment root or an authorized operator actor can access /api/v1/rag/operator",
                    request);
        }
        String actorId = resolveActorId(request);
        try {
            registry.execute(PlatformCapability.TENANT_LIFECYCLE, actorId, target);
            return null;
        } catch (IllegalStateException denied) {
            return error(HttpStatus.FORBIDDEN, "FORBIDDEN", denied.getMessage(), request);
        }
    }

    private ResponseEntity<ErrorResponse> conflictResponse(String detail, HttpServletRequest request) {
        String code = detail != null && detail.contains("DEFAULT_TENANT_PROTECTED")
                ? "DEFAULT_TENANT_PROTECTED"
                : "TENANT_CONFLICT";
        return error(HttpStatus.CONFLICT, code, detail, request);
    }

    private <T> ResponseEntity<T> okNoStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code,
                                                String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(ErrorResponse.builder()
                        .error(code)
                        .status(status.value())
                        .message(message)
                        .path(request == null ? null : request.getRequestURI())
                        .traceId(RequestTraceFilter.currentRequestTraceId())
                        .build());
    }

    private String resolveActorId(HttpServletRequest request) {
        Object principalId = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE);
        return principalId instanceof String id ? id : "unknown";
    }

    private String resolveActorType(HttpServletRequest request) {
        Object type = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        return ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(type)
                ? "ENVIRONMENT_ROOT" : "LIFECYCLE_ACTOR";
    }

    public record CreateTenantRequest(
            @NotBlank String tenantId,
            String displayName,
            Boolean issueBootstrapKey,
            String reason) {
    }

    public record UpdateTenantRequest(
            @NotBlank String displayName,
            String reason) {
    }

    public record ReasonRequest(String reason) {
    }

    public record IssueKeyRequest(String principalId, String label, String reason) {
    }

    public record UpdateQuotaRequest(boolean enabled, Long maxTokens, Double maxCostUsd, String reason) {
    }

    public record StartSupportSessionRequest(
            @NotBlank String tenantId,
            @NotBlank String ticketRef,
            @NotBlank String reason,
            Integer ttlMinutes) {
    }
}
