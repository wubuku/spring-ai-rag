package com.springairag.core.tenant;

import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;

/**
 * WP5：把 Spring Boot 自动配置的 app DataSource 包装为
 * {@link TenantGucDataSource}（实施规划 §5.3.4 / §6 WP5）。
 *
 * <p>只包装一次；{@code TenantGucDataSource} 自身不再包装。Flyway 使用
 * {@code FlywayDataSource} 或直接配置时不会被包装。</p>
 */
public class TenantDataSourceBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource) || bean instanceof TenantGucDataSource) {
            return bean;
        }
        return new TenantGucDataSource((DataSource) bean);
    }
}
