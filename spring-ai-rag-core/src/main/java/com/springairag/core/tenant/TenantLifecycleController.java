package com.springairag.core.tenant;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.filter.RequestTraceFilter;
import com.springairag.core.security.ApiRoutePolicy;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WP9：租户生命周期管理端点（实施规划 §6 WP9）。
 *
 * <p>契约：</p>
 * <ul>
 *   <li>仅环境 root 可调（与 {@code ApiKeyController} 的 root 门槛同一语义）；</li>
 *   <li>每次调用经 {@link PlatformCapabilityRegistry} 执行 {@link PlatformCapability#TENANT_LIFECYCLE}，
 *       actor 白名单 + 审计（与 WP6 discovery 同一契约）；</li>
 *   <li>SINGLE 模式拒绝创建非 {@code default} 租户，返回 409 冲突并记审计；</li>
 *   <li>租户行 + 初始 principal 同事务原子，避免「幽灵租户」；</li>
 *   <li>软删幂等；重复创建返回 409；非法状态迁移返回 409；非法 tenant_id 返回 400。</li>
 * </ul>
 *
 * <p>不实现租户切换器与邀请码（规划 §6 WP9 显式排除）。</p>
 */
@RestController
@ConditionalOnBean(TenantLifecycleService.class)
@ApiRoutePolicy(access = ApiRoutePolicy.Access.MANAGEMENT)
@RequestMapping("/api/v1/rag/tenants")
@Tag(name = "Tenant Lifecycle",
        description = "Platform-only tenant management (create / suspend / resume / disable / soft-delete)")
public class TenantLifecycleController {

    private final TenantLifecycleService service;
    private final PlatformCapabilityRegistry registry;
    private final EnvironmentRootCredentialResolver rootCredentialResolver;

    public TenantLifecycleController(TenantLifecycleService service,
                                     PlatformCapabilityRegistry registry,
                                     EnvironmentRootCredentialResolver rootCredentialResolver) {
        this.service = service;
        this.registry = registry;
        this.rootCredentialResolver = rootCredentialResolver;
    }

    @Operation(summary = "Create a tenant (tenant row + initial principal, single transaction)")
    @ApiResponses({
            @ApiResponse(responseCode = "201",
                    description = "Tenant and initial principal created atomically",
                    content = @Content(schema = @Schema(implementation = TenantProjection.class))),
            @ApiResponse(responseCode = "400", description = "Invalid tenant_id",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not environment root",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409",
                    description = "Tenant already exists / SINGLE mode rejects non-default",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody TenantCreateRequest body,
                                    HttpServletRequest request) {
        ResponseEntity<ErrorResponse> rootDenied = requireEnvironmentRoot(request);
        if (rootDenied != null) return rootDenied;
        String actorId = resolveActorId(request);
        TenantCreateCommand command = new TenantCreateCommand(
                body.tenantId(), body.displayName(), actorId);
        ResponseEntity<ErrorResponse> capabilityDenied =
                authorize(actorId, body.tenantId(), request);
        if (capabilityDenied != null) return capabilityDenied;
        try {
            // registry.execute 只做白名单 + 审计；业务投影一律来自 service，
            // 不把 capability 占位投影当成「租户已创建」的事实。
            TenantProjection created = service.create(command);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore())
                    .body(created);
        } catch (IllegalArgumentException bad) {
            return badRequest(bad.getMessage(), request);
        } catch (IllegalStateException conflict) {
            return conflict(conflict.getMessage(), request);
        }
    }

    @Operation(summary = "Suspend a tenant (ACTIVE → SUSPENDED)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant suspended"),
            @ApiResponse(responseCode = "403", description = "Not environment root"),
            @ApiResponse(responseCode = "404", description = "Tenant not found"),
            @ApiResponse(responseCode = "409", description = "Illegal state transition")
    })
    @PostMapping("/{tenantId}/suspend")
    public ResponseEntity<?> suspend(@PathVariable String tenantId,
                                     HttpServletRequest request) {
        return mutate(tenantId, request, "SUSPEND",
                actor -> service.suspend(tenantId, actor));
    }

    @Operation(summary = "Resume a suspended tenant (SUSPENDED → ACTIVE)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant resumed"),
            @ApiResponse(responseCode = "403", description = "Not environment root"),
            @ApiResponse(responseCode = "404", description = "Tenant not found"),
            @ApiResponse(responseCode = "409", description = "Illegal state transition")
    })
    @PostMapping("/{tenantId}/resume")
    public ResponseEntity<?> resume(@PathVariable String tenantId,
                                    HttpServletRequest request) {
        return mutate(tenantId, request, "RESUME",
                actor -> service.resume(tenantId, actor));
    }

    @Operation(summary = "Disable a tenant (ACTIVE → DISABLED; terminal)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant disabled"),
            @ApiResponse(responseCode = "403", description = "Not environment root"),
            @ApiResponse(responseCode = "404", description = "Tenant not found"),
            @ApiResponse(responseCode = "409", description = "Illegal state transition")
    })
    @PostMapping("/{tenantId}/disable")
    public ResponseEntity<?> disable(@PathVariable String tenantId,
                                     HttpServletRequest request) {
        return mutate(tenantId, request, "DISABLE",
                actor -> service.disable(tenantId, actor));
    }

    @Operation(summary = "Soft-delete a tenant (idempotent marker; never physical)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant soft-deleted"),
            @ApiResponse(responseCode = "403", description = "Not environment root")
    })
    @DeleteMapping("/{tenantId}")
    public ResponseEntity<?> softDelete(@PathVariable String tenantId,
                                        HttpServletRequest request) {
        return mutate(tenantId, request, "DELETE",
                actor -> service.softDelete(tenantId, actor));
    }

    @Operation(summary = "Describe a tenant (minimal identity projection)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant projection"),
            @ApiResponse(responseCode = "403", description = "Not environment root"),
            @ApiResponse(responseCode = "404", description = "Tenant not found or soft-deleted")
    })
    @GetMapping("/{tenantId}")
    public ResponseEntity<?> describe(@PathVariable String tenantId,
                                      HttpServletRequest request) {
        ResponseEntity<ErrorResponse> rootDenied = requireEnvironmentRoot(request);
        if (rootDenied != null) return rootDenied;
        String actorId = resolveActorId(request);
        ResponseEntity<ErrorResponse> capabilityDenied =
                authorize(actorId, tenantId, request);
        if (capabilityDenied != null) return capabilityDenied;
        TenantProjection projection = service.describe(tenantId);
        if (projection == null) {
            return notFound("租户不存在或已软删：" + tenantId, request);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(projection);
    }

    private ResponseEntity<?> mutate(String tenantId, HttpServletRequest request,
                                     String action,
                                     java.util.function.Function<String, TenantProjection> op) {
        ResponseEntity<ErrorResponse> rootDenied = requireEnvironmentRoot(request);
        if (rootDenied != null) return rootDenied;
        String actorId = resolveActorId(request);
        ResponseEntity<ErrorResponse> capabilityDenied =
                authorize(actorId, tenantId, request);
        if (capabilityDenied != null) return capabilityDenied;
        try {
            TenantProjection projection = op.apply(actorId);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(projection);
        } catch (IllegalStateException error) {
            if (error.getMessage() != null
                    && error.getMessage().contains("不存在")) {
                return notFound(error.getMessage(), request);
            }
            return conflict(error.getMessage(), request);
        }
    }

    /**
     * capability 守卫：白名单外 actor → 403（不是 409/500）。
     * registry.execute 同时记 PlatformAuditEvent（允许/拒绝都记）。
     */
    private ResponseEntity<ErrorResponse> authorize(String actorId, String tenantId,
                                                     HttpServletRequest request) {
        try {
            registry.execute(PlatformCapability.TENANT_LIFECYCLE, actorId, tenantId);
            return null;
        } catch (IllegalStateException denied) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(ErrorResponse.builder()
                            .error("FORBIDDEN")
                            .status(HttpStatus.FORBIDDEN.value())
                            .message(denied.getMessage())
                            .path(request == null ? null : request.getRequestURI())
                            .traceId(RequestTraceFilter.currentRequestTraceId())
                            .build());
        }
    }

    /**
     * 平台面门槛：租户生命周期只允许「已配置的环境 root 凭据 + 已通过 root 认证」的调用方。
     *
     * <p>未配置 root 时同样拒绝（fail-closed）：否则任何一条静态/数据库凭据都能创建租户，
     * 等于绕开平台 capability 白名单。</p>
     */
    private ResponseEntity<ErrorResponse> requireEnvironmentRoot(HttpServletRequest request) {
        if (rootCredentialResolver.isConfigured()
                && Boolean.TRUE.equals(request.getAttribute(
                        ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE))) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(ErrorResponse.builder()
                        .error("FORBIDDEN")
                        .status(HttpStatus.FORBIDDEN.value())
                        .message("Only the configured environment root can manage tenants")
                        .path(request == null ? null : request.getRequestURI())
                        .traceId(RequestTraceFilter.currentRequestTraceId())
                        .build());
    }

    private ResponseEntity<ErrorResponse> badRequest(String detail,
                                                     HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(ErrorResponse.builder()
                        .error("BAD_REQUEST")
                        .status(HttpStatus.BAD_REQUEST.value())
                        .message(detail)
                        .path(request == null ? null : request.getRequestURI())
                        .traceId(RequestTraceFilter.currentRequestTraceId())
                        .build());
    }

    private ResponseEntity<ErrorResponse> conflict(String detail,
                                                   HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .body(ErrorResponse.builder()
                        .error("TENANT_CONFLICT")
                        .status(HttpStatus.CONFLICT.value())
                        .message(detail)
                        .path(request == null ? null : request.getRequestURI())
                        .traceId(RequestTraceFilter.currentRequestTraceId())
                        .build());
    }

    private ResponseEntity<ErrorResponse> notFound(String detail,
                                                   HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .body(ErrorResponse.builder()
                        .error("TENANT_NOT_FOUND")
                        .status(HttpStatus.NOT_FOUND.value())
                        .message(detail)
                        .path(request == null ? null : request.getRequestURI())
                        .traceId(RequestTraceFilter.currentRequestTraceId())
                        .build());
    }

    private String resolveActorId(HttpServletRequest request) {
        Object principalType = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        Object principalId = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE);
        if (ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(principalType)
                && principalId instanceof String id) {
            return id;
        }
        return "unknown";
    }

    /** 请求体：创建租户的最小入参。 */
    public record TenantCreateRequest(
            @jakarta.validation.constraints.NotBlank String tenantId,
            String displayName) {
    }
}
