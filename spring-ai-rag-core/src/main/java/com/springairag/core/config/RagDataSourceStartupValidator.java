package com.springairag.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 数据源连接池参数的启动期校验。
 *
 * <p>HikariCP 自己会校验一部分参数，但 {@code minimum-idle > maximum-pool-size}
 * 只打 WARN 并把 minimumIdle 压到 maximumPoolSize —— 进程照常启动，实际池大小
 * 与配置不符。这里把这类“能启动但静默降级”的配置直接变成启动失败，并明确指出
 * 配置项。
 *
 * <p>数据源不可达、账号错误不在这里处理：它们由 Flyway/连接池初始化在启动阶段
 * 抛出，本身就是快速失败。
 */
@Configuration(proxyBeanMethods = false)
public class RagDataSourceStartupValidator {

    private static final Logger log =
            LoggerFactory.getLogger(RagDataSourceStartupValidator.class);

    static final String MAXIMUM_POOL_SIZE = "spring.datasource.hikari.maximum-pool-size";
    static final String MINIMUM_IDLE = "spring.datasource.hikari.minimum-idle";
    static final String CONNECTION_TIMEOUT = "spring.datasource.hikari.connection-timeout";
    static final String VALIDATION_TIMEOUT = "spring.datasource.hikari.validation-timeout";

    static void validate(Integer maximumPoolSize,
                         Integer minimumIdle,
                         Long connectionTimeout,
                         Long validationTimeout) {
        if (maximumPoolSize != null && maximumPoolSize < 1) {
            throw new IllegalStateException(
                    MAXIMUM_POOL_SIZE + " must be >= 1 but was " + maximumPoolSize);
        }
        if (minimumIdle != null && minimumIdle < 0) {
            throw new IllegalStateException(
                    MINIMUM_IDLE + " must be >= 0 but was " + minimumIdle);
        }
        if (maximumPoolSize != null && minimumIdle != null
                && minimumIdle > maximumPoolSize) {
            throw new IllegalStateException(
                    MINIMUM_IDLE + " must be <= " + MAXIMUM_POOL_SIZE + " (minimum-idle="
                            + minimumIdle + ", maximum-pool-size=" + maximumPoolSize + ")");
        }
        if (connectionTimeout != null && connectionTimeout < 250) {
            throw new IllegalStateException(
                    CONNECTION_TIMEOUT + " must be >= 250 milliseconds but was "
                            + connectionTimeout);
        }
        if (validationTimeout != null && validationTimeout < 0) {
            throw new IllegalStateException(
                    VALIDATION_TIMEOUT + " must be >= 0 milliseconds but was "
                            + validationTimeout);
        }
    }

    @Bean
    public ApplicationRunner dataSourcePoolStartupCheck(Environment environment) {
        return args -> {
            if (isBlank(environment.getProperty("spring.datasource.url"))) {
                return;
            }
            Integer maximumPoolSize = readInteger(environment, MAXIMUM_POOL_SIZE);
            Integer minimumIdle = readInteger(environment, MINIMUM_IDLE);
            Long connectionTimeout = readLong(environment, CONNECTION_TIMEOUT);
            Long validationTimeout = readLong(environment, VALIDATION_TIMEOUT);

            validate(maximumPoolSize, minimumIdle, connectionTimeout, validationTimeout);
            log.debug("DataSource pool configuration validated: max={}, minIdle={}, "
                            + "connectionTimeout={}, validationTimeout={}",
                    maximumPoolSize, minimumIdle, connectionTimeout, validationTimeout);
        };
    }

    private static Integer readInteger(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (isBlank(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    key + " must be an integer but was '" + value + "'", e);
        }
    }

    private static Long readLong(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    key + " must be an integer (milliseconds) but was '" + value + "'", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
