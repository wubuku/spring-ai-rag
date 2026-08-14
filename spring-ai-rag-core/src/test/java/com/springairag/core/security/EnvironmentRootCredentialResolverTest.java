package com.springairag.core.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentRootCredentialResolverTest {

    private static final String VALID_ROOT =
            "root-2026-08-14-9f4c2a7b6d1e8a3c";

    @Test
    void blankCredential_disablesRootMode() {
        EnvironmentRootCredentialResolver resolver =
                new EnvironmentRootCredentialResolver("  ");

        assertFalse(resolver.isConfigured());
        assertFalse(resolver.matches(VALID_ROOT));
    }

    @Test
    void validCredential_matchesOnlyExactValue() {
        EnvironmentRootCredentialResolver resolver =
                new EnvironmentRootCredentialResolver(VALID_ROOT);

        assertTrue(resolver.isConfigured());
        assertTrue(resolver.matches(VALID_ROOT));
        assertFalse(resolver.matches(VALID_ROOT + "-wrong"));
        assertFalse(resolver.matches(null));
    }

    @Test
    void shortCredential_failsFast() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new EnvironmentRootCredentialResolver("too-short"));

        assertTrue(error.getMessage().contains("at least 32"));
    }

    @Test
    void nonAsciiCredential_failsFast() {
        assertThrows(
                IllegalStateException.class,
                () -> new EnvironmentRootCredentialResolver(
                        "root-credential-with-non-ascii-中文"));
    }

    @Test
    void whitespaceCredential_failsFast() {
        assertThrows(
                IllegalStateException.class,
                () -> new EnvironmentRootCredentialResolver(
                        "root credential with spaces 1234567890"));
    }

    @Test
    void placeholderCredential_failsFast() {
        assertThrows(
                IllegalStateException.class,
                () -> new EnvironmentRootCredentialResolver(
                        "replace-with-a-secure-random-value"));
    }
}
