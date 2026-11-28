package com.springairag.core.tenant;

import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;
import java.util.Set;

/**
 * WP5：把 Spring Boot 自动配置的 app DataSource 包装为
 * {@link TenantGucDataSource}（实施规划 §5.3.4 / §6 WP5）。
 *
 * <p>只包装一次；{@code TenantGucDataSource} 自身不再包装。平台专用连接池
 * （如 {@code platformDataSource}）与迁移连接池（{@code flywayDataSource}）显式排除，
 * 避免运营方跨租户聚合连接被误包进租户 GUC 拦截器。</p>
 */
public class TenantDataSourceBeanPostProcessor implements BeanPostProcessor {

    public static final Set<String> DEFAULT_EXCLUDED_BEAN_NAMES =
            Set.of("platformDataSource", "flywayDataSource");

    private final Set<String> excludedBeanNames;

    public TenantDataSourceBeanPostProcessor() {
        this(DEFAULT_EXCLUDED_BEAN_NAMES);
    }

    public TenantDataSourceBeanPostProcessor(Set<String> excludedBeanNames) {
        this.excludedBeanNames = excludedBeanNames == null
                ? DEFAULT_EXCLUDED_BEAN_NAMES
                : Set.copyOf(excludedBeanNames);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource) || bean instanceof TenantGucDataSource) {
            return bean;
        }
        if (beanName != null && excludedBeanNames.contains(beanName)) {
            return bean;
        }
        return new TenantGucDataSource((DataSource) bean);
    }
}
