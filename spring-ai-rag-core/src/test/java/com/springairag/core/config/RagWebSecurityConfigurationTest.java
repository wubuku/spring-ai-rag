package com.springairag.core.config;

import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.ratelimit.PostgresRateLimitStore;
import com.springairag.core.ratelimit.RateLimitObservability;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.service.ApiKeyManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagWebSecurityConfigurationTest {

    private static final String VALID_ROOT =
            "root-2026-08-14-9f4c2a7b6d1e8a3c";

    private final RagWebSecurityConfiguration configuration =
            new RagWebSecurityConfiguration();

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            RagWebSecurityConfiguration.class,
                            BoundPropertiesConfiguration.class);

    @Test
    void resolverHashesConfiguredRootAndClearsBoundPlaintext() {
        RagProperties properties = new RagProperties();
        properties.getSecurity().setRootApiKey(VALID_ROOT);

        EnvironmentRootCredentialResolver resolver =
                configuration.environmentRootCredentialResolver(properties);

        assertTrue(resolver.isConfigured());
        assertTrue(resolver.matches(VALID_ROOT));
        assertEquals("", properties.getSecurity().getRootApiKey());
    }

    @Test
    void weakRootFailsConfiguration() {
        RagProperties properties = new RagProperties();
        properties.getSecurity().setRootApiKey("weak");

        assertThrows(IllegalStateException.class,
                () -> configuration.environmentRootCredentialResolver(properties));
        assertEquals("", properties.getSecurity().getRootApiKey());
    }

    @Test
    void filterRegistrationCoversApiAndRunsBeforeRateLimit() {
        RagProperties properties = new RagProperties();
        EnvironmentRootCredentialResolver resolver =
                new EnvironmentRootCredentialResolver("");

        FilterRegistrationBean<ApiKeyAuthFilter> registration =
                configuration.apiKeyAuthFilterRegistration(
                        properties,
                        resolver,
                        mock(ApiKeyManagementService.class));

        assertTrue(registration.getUrlPatterns().contains("/api/*"));
        assertTrue(registration.getUrlPatterns().contains("/v1/*"));
        assertEquals(-10, registration.getOrder());
        assertNotNull(registration.getFilter());
    }

    @Test
    void localRateLimitDoesNotRequirePostgresStore() {
        RagProperties properties = new RagProperties();
        ObjectProvider<PostgresRateLimitStore> stores = mock(ObjectProvider.class);

        assertDoesNotThrow(() -> configuration.rateLimitFilterRegistration(
                properties,
                stores,
                RateLimitObservability.noop()));
    }

    @Test
    void postgresqlRateLimitFailsStartupWithoutJdbcBackedStore() {
        RagProperties properties = new RagProperties();
        properties.getRateLimit().setBackend("postgresql");
        properties.getRateLimit().setStrategy("principal");
        ObjectProvider<PostgresRateLimitStore> stores = mock(ObjectProvider.class);
        when(stores.getIfAvailable()).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> configuration.rateLimitFilterRegistration(
                        properties,
                        stores,
                        RateLimitObservability.noop()));
    }

    @Test
    void postgresqlBackendRegistersJdbcBackedStoreInApplicationContext() {
        contextRunner
                .withUserConfiguration(JdbcConfiguration.class)
                .withPropertyValues(
                        "rag.rate-limit.backend=postgresql",
                        "rag.rate-limit.strategy=principal")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PostgresRateLimitStore.class);
                });
    }

    @Test
    void postgresqlBackendFailsApplicationContextWithoutJdbcTemplate() {
        contextRunner
                .withPropertyValues(
                        "rag.rate-limit.backend=postgresql",
                        "rag.rate-limit.strategy=principal")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RagProperties.class)
    static class BoundPropertiesConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    static class JdbcConfiguration {
        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }
    }
}
