package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据源连接池参数启动期校验。
 */
class RagDataSourceStartupValidatorTest {

    private static MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://localhost:5432/test");
    }

    @Test
    void noDataSourceConfiguredSkipsValidation() {
        MockEnvironment environment = new MockEnvironment();
        var runner = new RagDataSourceStartupValidator()
                .dataSourcePoolStartupCheck(environment);
        assertDoesNotThrow(() -> runner.run(null));
    }

    @Test
    void validPoolConfigurationPasses() {
        var runner = new RagDataSourceStartupValidator().dataSourcePoolStartupCheck(
                environment()
                        .withProperty("spring.datasource.hikari.maximum-pool-size", "20")
                        .withProperty("spring.datasource.hikari.minimum-idle", "5")
                        .withProperty("spring.datasource.hikari.connection-timeout", "10000"));
        assertDoesNotThrow(() -> runner.run(null));
    }

    @Test
    void maximumPoolSizeMustBePositive() {
        var runner = new RagDataSourceStartupValidator().dataSourcePoolStartupCheck(
                environment().withProperty(
                        "spring.datasource.hikari.maximum-pool-size", "0"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runner.run(null));
        assertTrue(error.getMessage().contains("spring.datasource.hikari.maximum-pool-size"));
    }

    @Test
    void minimumIdleMustNotExceedMaximumPoolSize() {
        var runner = new RagDataSourceStartupValidator().dataSourcePoolStartupCheck(
                environment()
                        .withProperty("spring.datasource.hikari.maximum-pool-size", "5")
                        .withProperty("spring.datasource.hikari.minimum-idle", "8"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runner.run(null));
        assertTrue(error.getMessage().contains("spring.datasource.hikari.minimum-idle"));
        assertTrue(error.getMessage().contains("maximum-pool-size"));
    }

    @Test
    void connectionTimeoutBelowHikariMinimumFails() {
        var runner = new RagDataSourceStartupValidator().dataSourcePoolStartupCheck(
                environment().withProperty(
                        "spring.datasource.hikari.connection-timeout", "249"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runner.run(null));
        assertTrue(error.getMessage().contains("spring.datasource.hikari.connection-timeout"));
    }

    @Test
    void nonNumericValuesFailWithPropertyName() {
        var runner = new RagDataSourceStartupValidator().dataSourcePoolStartupCheck(
                environment().withProperty(
                        "spring.datasource.hikari.maximum-pool-size", "abc"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runner.run(null));
        assertTrue(error.getMessage().contains("spring.datasource.hikari.maximum-pool-size"));
    }
}
