package com.springairag.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Message digest utilities for content hashing.
 */
public final class DigestUtils {

    private DigestUtils() {}

    /**
     * Computes the SHA-256 hash of the given text.
     *
     * @param content the text to hash
     * @return lowercase hex string (64 characters)
     */
    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
