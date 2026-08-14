package com.springairag.core.config;

import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.service.ApiKeyManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RagWebSecurityConfigurationTest {

    private static final String VALID_ROOT =
            "root-2026-08-14-9f4c2a7b6d1e8a3c";

    private final RagWebSecurityConfiguration configuration =
            new RagWebSecurityConfiguration();

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
        assertEquals(-10, registration.getOrder());
        assertNotNull(registration.getFilter());
    }
}
