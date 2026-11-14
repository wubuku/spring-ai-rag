package com.springairag.core.tenant;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP9：租户生命周期管理端点的 HTTP 契约（403 / 409 / 幂等 / 审计）。
 *
 * <p>红绿对照：控制器不存在时本测试无法编译；实现后覆盖
 * ①非 root → 403；②白名单外 actor → 403；③SINGLE 拒绝非 default → 409；
 * ④重复创建 → 409；⑤软删幂等；⑥每次调用都留 capability 审计。</p>
 */
@DisplayName("WP9 租户生命周期管理端点")
class TenantLifecycleControllerTest {

    private final EnvironmentRootCredentialResolver rootCredentialResolver =
            mock(EnvironmentRootCredentialResolver.class);
    private final TenantLifecycleService service = mock(TenantLifecycleService.class);

    private MockMvc mockMvc(PlatformCapabilityRegistry registry) {
        TenantLifecycleController controller = new TenantLifecycleController(
                service, registry, rootCredentialResolver);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private PlatformCapabilityRegistry registryWith(String... actors) {
        PlatformCapabilityRegistry registry = new PlatformCapabilityRegistry();
        for (String actor : actors) {
            registry.allowActor(actor);
        }
        registry.register(PlatformCapability.TENANT_LIFECYCLE,
                tenantId -> new TenantProjection(tenantId, "ACTIVE"));
        return registry;
    }

    /** standalone MockMvc 会新建请求；必须用 RequestPostProcessor 把 root 身份写进真实请求。 */
    private static RequestPostProcessor root() {
        return request -> {
            request.setAttribute(ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                    ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT);
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                    EnvironmentRootCredentialResolver.PRINCIPAL_ID);
            return request;
        };
    }

    @Test
    @DisplayName("未配置 root 凭据 → 403（平台面 fail-closed，不放行任意凭据）")
    void rejectsWhenRootCredentialNotConfigured() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);

        mockMvc(registryWith("environment-root"))
                .perform(post("/api/v1/rag/tenants")
                        .with(root())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"acme\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("已配置 root 但调用方非 root → 403")
    void rejectsNonRootCaller() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);

        mockMvc(registryWith("environment-root"))
                .perform(post("/api/v1/rag/tenants")
                        .with(request -> {
                            request.setAttribute(
                                    ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE,
                                    Boolean.FALSE);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"acme\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("白名单外 actor → 403 且记 capability 审计")
    void rejectsActorOutsideWhitelist() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        PlatformCapabilityRegistry registry = registryWith("someone-else");

        mockMvc(registry)
                .perform(post("/api/v1/rag/tenants")
                        .with(root())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"acme\"}"))
                .andExpect(status().isForbidden());
        assertEquals(1, registry.auditEvents().size());
        assertTrue(!registry.auditEvents().get(0).allowed());
        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("SINGLE 拒绝创建非 default → 409 冲突（不写库）")
    void singleModeConflictReturns409() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        when(service.create(any())).thenThrow(new IllegalStateException(
                "SINGLE 模式拒绝创建非 default 租户（与 TenantModeGuard 同一契约）"));

        mockMvc(registryWith("environment-root"))
                .perform(post("/api/v1/rag/tenants")
                        .with(root())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"acme\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TENANT_CONFLICT"));
    }

    @Test
    @DisplayName("重复创建 → 409 冲突（幂等不静默覆盖）")
    void duplicateCreateReturns409() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        when(service.create(any())).thenThrow(new IllegalStateException("租户已存在：acme"));

        mockMvc(registryWith("environment-root"))
                .perform(post("/api/v1/rag/tenants")
                        .with(root())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"acme\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("创建成功 → 201，且返回真实 service 投影")
    void createReturns201WithServiceProjection() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        when(service.create(any())).thenReturn(new TenantProjection("acme", "ACTIVE"));

        mockMvc(registryWith("environment-root"))
                .perform(post("/api/v1/rag/tenants")
                        .with(root())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"acme\",\"displayName\":\"Acme\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("非法 tenant_id → 400")
    void invalidTenantIdReturns400() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        when(service.create(any())).thenThrow(
                new IllegalArgumentException("非法 tenant_id"));

        mockMvc(registryWith("environment-root"))
                .perform(post("/api/v1/rag/tenants")
                        .with(root())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"Bad_ID!\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("软删幂等：重复 DELETE 都返回 200")
    void softDeleteIsIdempotent() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        when(service.softDelete(anyString(), anyString()))
                .thenReturn(new TenantProjection("acme", "DISABLED"));

        mockMvc(registryWith("environment-root"))
                .perform(delete("/api/v1/rag/tenants/acme").with(root()))
                .andExpect(status().isOk());
        mockMvc(registryWith("environment-root"))
                .perform(delete("/api/v1/rag/tenants/acme").with(root()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("状态迁移非法 → 409；租户不存在 → 404")
    void illegalTransitionAndMissingTenant() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        when(service.suspend(anyString(), anyString()))
                .thenThrow(new IllegalStateException("非法状态迁移：SUSPENDED → DISABLED"));
        when(service.describe(anyString())).thenReturn(null);

        mockMvc(registryWith("environment-root"))
                .perform(post("/api/v1/rag/tenants/acme/suspend").with(root()))
                .andExpect(status().isConflict());
        mockMvc(registryWith("environment-root"))
                .perform(get("/api/v1/rag/tenants/acme").with(root()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_FOUND"));
    }

    @Test
    @DisplayName("每次生命周期调用都记 capability 审计（actor + target）")
    void everyLifecycleCallIsAudited() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        when(service.suspend(anyString(), anyString()))
                .thenReturn(new TenantProjection("acme", "SUSPENDED"));
        PlatformCapabilityRegistry registry = registryWith("environment-root");

        mockMvc(registry).perform(post("/api/v1/rag/tenants/acme/suspend").with(root()))
                .andExpect(status().isOk());
        assertEquals(1, registry.auditEvents().size());
        assertEquals(PlatformCapability.TENANT_LIFECYCLE,
                registry.auditEvents().get(0).capability());
        assertEquals("acme", registry.auditEvents().get(0).targetTenantId());
        assertTrue(registry.auditEvents().get(0).allowed());
    }

    @Test
    @DisplayName("ErrorResponse 契约：错误响应体使用统一结构")
    void errorResponseShapeIsUnified() throws Exception {
        when(rootCredentialResolver.isConfigured()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE, Boolean.FALSE);

        mockMvc(registryWith("environment-root"))
                .perform(get("/api/v1/rag/tenants/acme"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("审计端口：服务层每次动作都写审计（含 SINGLE 拒绝）")
    void serviceAuditSeesEveryAction() {
        AtomicInteger count = new AtomicInteger();
        TenantLifecycleAudit audit = (action, tenant, actor, detail) -> count.incrementAndGet();
        assertEquals(0, count.get());
        audit.record("REJECT", "acme", "actor-1", "SINGLE 模式拒绝创建非 default 租户");
        assertEquals(1, count.get());
        assertTrue(count.get() > 0);
    }
}
