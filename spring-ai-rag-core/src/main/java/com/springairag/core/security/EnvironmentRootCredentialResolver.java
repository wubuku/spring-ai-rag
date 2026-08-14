package com.springairag.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;

/**
 * 验证由部署环境注入的 root API Key。
 *
 * <p>实例只保存 SHA-256 派生值，不保存原始凭据。未配置时保持 legacy 运行模式。
 */
public final class EnvironmentRootCredentialResolver {

    public static final String PRINCIPAL_ID = "environment-root";
    private static final int MIN_LENGTH = 32;
    private static final Set<String> PLACEHOLDERS = Set.of(
            "change-me-change-me-change-me-change-me",
            "changeme-changeme-changeme-changeme",
            "replace-with-a-secure-random-value",
            "replace-with-your-root-api-key",
            "your-root-api-key-goes-here-now",
            "your-32-character-root-api-key-here",
            "example-root-api-key-example-root-key"
    );

    private final byte[] credentialHash;

    public EnvironmentRootCredentialResolver(String rawCredential) {
        if (rawCredential == null || rawCredential.isBlank()) {
            this.credentialHash = null;
            return;
        }
        validate(rawCredential);
        this.credentialHash = sha256(rawCredential);
    }

    public boolean isConfigured() {
        return credentialHash != null;
    }

    public boolean matches(String candidate) {
        if (!isConfigured() || candidate == null || candidate.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(credentialHash, sha256(candidate));
    }

    private static void validate(String credential) {
        if (credential.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "RAG_ROOT_API_KEY must contain at least 32 ASCII characters");
        }
        for (int i = 0; i < credential.length(); i++) {
            char value = credential.charAt(i);
            if (value < 0x21 || value > 0x7e) {
                throw new IllegalStateException(
                        "RAG_ROOT_API_KEY must contain printable ASCII characters without whitespace");
            }
        }
        String normalized = credential.toLowerCase(Locale.ROOT);
        if (PLACEHOLDERS.contains(normalized)) {
            throw new IllegalStateException(
                    "RAG_ROOT_API_KEY must not use a documented placeholder value");
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
