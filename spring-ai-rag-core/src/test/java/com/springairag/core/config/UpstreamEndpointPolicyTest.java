package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上游 base-url 启动期校验与归一化的回归。
 */
class UpstreamEndpointPolicyTest {

    @Test
    void blankValuesPassThrough() {
        assertNull(UpstreamEndpointPolicy.normalizeAndValidate(
                "rag.embedding.base-url", null, false));
        assertEquals("", UpstreamEndpointPolicy.normalizeAndValidate(
                "rag.embedding.base-url", "", false));
    }

    @Test
    void trailingSlashIsRemoved() {
        assertEquals("https://api.example.com",
                UpstreamEndpointPolicy.normalizeAndValidate(
                        "app.models.providers.x.base-url",
                        "https://api.example.com/", false));
    }

    @Test
    void trailingV1IsRemovedBecauseSpringAiAppendsIt() {
        assertEquals("https://api.example.com",
                UpstreamEndpointPolicy.normalizeAndValidate(
                        "spring.ai.openai.base-url",
                        "https://api.example.com/v1", false));
        assertEquals("https://api.example.com",
                UpstreamEndpointPolicy.normalizeAndValidate(
                        "spring.ai.openai.base-url",
                        "https://api.example.com/v1/", false));
    }

    @Test
    void unresolvedPlaceholderFailsFast() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> UpstreamEndpointPolicy.normalizeAndValidate(
                        "spring.ai.openai.base-url",
                        "${OPENAI_BASE_URL}", false));
        assertTrue(error.getMessage().contains("spring.ai.openai.base-url"));
        assertTrue(error.getMessage().contains("unresolved placeholder"));
    }

    @Test
    void nonHttpSchemeIsRejected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> UpstreamEndpointPolicy.normalizeAndValidate(
                        "spring.ai.openai.base-url", "ftp://api.example.com", false));
        assertTrue(error.getMessage().contains("http(s)"));
    }

    @Test
    void missingHostIsRejected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> UpstreamEndpointPolicy.normalizeAndValidate(
                        "spring.ai.openai.base-url", "https:///path", false));
        assertTrue(error.getMessage().contains("no host"));
    }

    @Test
    void embeddedCredentialsAreRejected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> UpstreamEndpointPolicy.normalizeAndValidate(
                        "spring.ai.openai.base-url",
                        "https://user:pass@api.example.com", false));
        assertTrue(error.getMessage().contains("credentials"));
    }

    @Test
    void queryAndFragmentAreRejected() {
        assertThrows(IllegalStateException.class,
                () -> UpstreamEndpointPolicy.normalizeAndValidate(
                        "spring.ai.openai.base-url",
                        "https://api.example.com?x=1", false));
        assertThrows(IllegalStateException.class,
                () -> UpstreamEndpointPolicy.normalizeAndValidate(
                        "spring.ai.openai.base-url",
                        "https://api.example.com#frag", false));
    }

    @Test
    void remotePlaintextHttpIsRejectedByDefault() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> UpstreamEndpointPolicy.normalizeAndValidate(
                        "app.models.providers.local.base-url",
                        "http://api.example.com", false));
        assertTrue(error.getMessage().contains("plaintext http"));
        assertTrue(error.getMessage().contains("rag.security.allow-insecure-upstream"));
    }

    @Test
    void loopbackPlaintextHttpIsAllowed() {
        assertDoesNotThrow(() -> UpstreamEndpointPolicy.normalizeAndValidate(
                "rag.embedding.base-url", "http://127.0.0.1:8080", false));
        assertDoesNotThrow(() -> UpstreamEndpointPolicy.normalizeAndValidate(
                "rag.embedding.base-url", "http://localhost:8080", false));
        assertDoesNotThrow(() -> UpstreamEndpointPolicy.normalizeAndValidate(
                "rag.embedding.base-url", "http://[::1]:8080", false));
        assertDoesNotThrow(() -> UpstreamEndpointPolicy.normalizeAndValidate(
                "rag.embedding.base-url", "http://127.0.0.2:8080", false));
    }

    @Test
    void explicitOptInAllowsRemotePlaintextHttp() {
        assertEquals("http://api.example.com",
                UpstreamEndpointPolicy.normalizeAndValidate(
                        "app.models.providers.local.base-url",
                        "http://api.example.com", true));
    }

    @Test
    void remoteHttpsIsAlwaysAllowed() {
        assertEquals("https://api.example.com",
                UpstreamEndpointPolicy.normalizeAndValidate(
                        "spring.ai.openai.base-url",
                        "https://api.example.com", false));
    }
}
