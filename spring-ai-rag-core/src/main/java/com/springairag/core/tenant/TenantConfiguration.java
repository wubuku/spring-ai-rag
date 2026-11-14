package com.springairag.core.tenant;

import com.springairag.core.config.RagProperties;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.filter.ApiKeyAuthFilter;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * WP5：租户请求链路条件装配（实施规划 §5.3 / §6 WP5）。
 *
 * <p>默认 SINGLE：{@link SingleTenantResolver} 固定 default，不启用 RLS/verifier；
 * STRICT：{@link PrincipalTenantResolver} 取凭据绑定 tenant，启动时由
 * {@link TenantModeGuard} 委托 {@link TenantRlsVerifier} 全项校验。</p>
 *
 * <p>app DataSource 由 {@link TenantDataSourceBeanPostProcessor} 包装为
 * {@link TenantGucDataSource}（session 级 GUC 三件套）。</p>
 */
@Configuration(proxyBeanMethods = false)
public class TenantConfiguration {

    @Bean
    @ConditionalOnMissingBean(TenantResolver.class)
    public TenantResolver tenantResolver(RagProperties properties) {
        TenantMode mode = properties.getTenant().getMode();
        return mode == TenantMode.STRICT
                ? new PrincipalTenantResolver()
                : new SingleTenantResolver();
    }

    @Bean
    @ConditionalOnMissingBean(name = "tenantContextFilterRegistration")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<Filter> tenantContextFilterRegistration(
            RagProperties properties, TenantResolver resolver) {
        TenantMode mode = properties.getTenant().getMode();
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(
                new TenantContextFilter(resolver, mode));
        registration.addUrlPatterns("/api/*", "/v1/*");
        // 认证(-10) → MDC(-9) → 租户上下文(-8) → capability(-5) → 限流(0)
        registration.setOrder(-8);
        return registration;
    }

    /** 启动模式守卫：SINGLE 校验凭据全 default；STRICT 委托 RLS verifier。 */
    @Bean
    @ConditionalOnMissingBean(TenantModeGuard.class)
    public TenantModeGuard tenantModeGuard(JdbcTemplate jdbcTemplate) {
        return new TenantModeGuard(jdbcTemplate);
    }

    /**
     * 启动校验：按当前模式执行守卫 + 端点声明全覆盖（WP12 收口）。
     *
     * <p>用 ApplicationRunner 形式让失败发生在启动阶段。端点声明来自
     * {@code ApiRoutePolicyRegistry.describe()} 的启动期真实快照：
     * 每个受保护路径（{@code /api/**}、{@code /v1/**}）都必须被
     * {@link TenantEndpointGuard} 的 tenant + ACL 声明接住，新端点漏声明即启动失败。
     * registry 可能不在非 Web 上下文装配，此时跳过端点校验（RLS/GUC 层与 Web 无关）。</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "tenantStartupGuardRunner")
    public org.springframework.boot.ApplicationRunner tenantStartupGuardRunner(
            RagProperties properties,
            TenantModeGuard guard,
            org.springframework.beans.factory.ObjectProvider<com.springairag.core.security.ApiRoutePolicyRegistry> routePolicies) {
        return args -> {
            guard.verify(properties.getTenant().getMode());
            com.springairag.core.security.ApiRoutePolicyRegistry registry = routePolicies.getIfAvailable();
            if (registry != null) {
                TenantEndpointDeclarationCatalog.fromDescriptions(registry.describe()).validate();
            }
        };
    }

    /**
     * 包装 app DataSource：session 级 GUC 三件套。
     *
     * <p>用 BeanPostProcessor 而不是声明 {@code @Bean DataSource}，因为本仓的
     * DataSource 由 Spring Boot 自动配置；包装后 JdbcTemplate / JPA 都拿到包装实例。</p>
     */
    @Bean
    @ConditionalOnMissingBean(TenantDataSourceBeanPostProcessor.class)
    public static TenantDataSourceBeanPostProcessor tenantDataSourceBeanPostProcessor() {
        return new TenantDataSourceBeanPostProcessor();
    }

    /**
     * WP9：租户生命周期服务（平台面，仅由 TenantLifecycleController 调用）。
     *
     * <p>{@code @ConditionalOnBean} 保证无 JDBC / 无事务管理器的轻量测试上下文
     * （例如 WebMvcTest 的认证装配切片）不会因为这个平台面 bean 启动失败。</p>
     */
    @Bean
    @ConditionalOnMissingBean(TenantLifecycleService.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
            {JdbcTemplate.class,
             org.springframework.transaction.PlatformTransactionManager.class})
    public TenantLifecycleService tenantLifecycleService(
            JdbcTemplate jdbcTemplate,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            RagProperties properties) {
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        return new TenantLifecycleService(
                jdbcTemplate, template,
                properties.getTenant().getMode(),
                new TenantLifecycleAudit.Logging());
    }

    /**
     * WP6/WP9：平台 capability 注册表（最小投影 + 白名单 + 审计）。
     *
     * <p>WP9 把 {@link PlatformCapability#TENANT_LIFECYCLE} 真正接线到
     * {@link TenantLifecycleService#describe(String)}：controller 在每次生命周期调用前
     * 先经本注册表做 actor 白名单校验并记 {@link PlatformAuditEvent}；handler 只返回
     * 最小身份投影，不返回私有正文。未登记 capability / 白名单外 actor 一律拒绝。</p>
     */
    @Bean
    @ConditionalOnMissingBean(PlatformCapabilityRegistry.class)
    public PlatformCapabilityRegistry platformCapabilityRegistry(
            RagProperties properties,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            TenantLifecycleService lifecycleService) {
        PlatformCapabilityRegistry registry = new PlatformCapabilityRegistry();
        for (String actor : properties.getTenant().getLifecycleActors()) {
            registry.allowActor(actor);
        }
        if (lifecycleService != null) {
            registry.register(PlatformCapability.TENANT_LIFECYCLE,
                    lifecycleService::describe);
        }
        return registry;
    }

    /** WP6：租户成员/状态守卫。 */
    @Bean
    @ConditionalOnMissingBean(TenantMembershipGuard.class)
    public TenantMembershipGuard tenantMembershipGuard() {
        return new TenantMembershipGuard();
    }

    /**
     * WP12：端点 tenant + ACL 声明覆盖校验。
     *
     * <p>声明集合不再由装配层注入空集合（WP6 待办），而是由
     * {@code tenantStartupGuardRunner} 从 {@code ApiRoutePolicyRegistry.describe()}
     * 生成并在启动期 validate；保留 {@code @ConditionalOnMissingBean} 让宿主/测试
     * 可覆盖为显式声明集合。</p>
     */
    @Bean
    @ConditionalOnMissingBean(TenantEndpointGuard.class)
    public TenantEndpointGuard tenantEndpointGuard(
            org.springframework.beans.factory.ObjectProvider<com.springairag.core.security.ApiRoutePolicyRegistry> routePolicies) {
        com.springairag.core.security.ApiRoutePolicyRegistry registry = routePolicies.getIfAvailable();
        if (registry == null) {
            return new TenantEndpointGuard(java.util.Set.of());
        }
        return TenantEndpointDeclarationCatalog.fromDescriptions(registry.describe()).guard();
    }

    /**
     * 凭据加载契约说明（WP5）：{@link ApiKeyManagementService#authenticate(String)} 的
     * 投影需带凭据表 tenant_id 与租户状态，由 {@link PrincipalTenantResolver} 校验。
     * 旧 {@link AuthenticatedApiPrincipal} 10 参构造器默认落 default，
     * 不用于 STRICT 凭据解析；WP9 生命周期绑定后由服务层显式传绑定 tenant。
     */
    static final String PRINCIPAL_ATTRIBUTE =
            ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE;

    /** WP7：按 ACTIVE 租户枚举逐租户派发后台任务的统一 dispatcher。 */
    @Bean
    @ConditionalOnMissingBean(TenantJobDispatcher.class)
    public TenantJobDispatcher tenantJobDispatcher() {
        return new TenantJobDispatcher();
    }

    /** WP7：ACTIVE 租户目录（rag_tenant 平台表，无 RLS；枚举失败 fail-closed）。 */
    @Bean
    @ConditionalOnMissingBean(TenantDirectory.class)
    public TenantDirectory tenantDirectory(JdbcTemplate jdbcTemplate) {
        return new JdbcTenantDirectory(jdbcTemplate);
    }
}
