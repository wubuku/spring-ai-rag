package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.security.ApiKeyCollectionAccess;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the semantic fingerprint used by API key provisioning idempotency.
 */
public final class ApiKeyProvisioningFingerprint {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ApiKeyProvisioningFingerprint() {
    }

    public static String sha256(ApiKeyCreateRequest request, String role) {
        try {
            return HexFormatHolder.format(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson(request, role).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static String canonicalJson(ApiKeyCreateRequest request, String role) {
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("name", request.getName());
        semantic.put("expiresAt", request.getExpiresAt());
        List<Long> ids = request.getAllowedCollectionIds();
        semantic.put("allowedCollectionIds",
                ids == null || ids.isEmpty()
                        ? null
                        : ApiKeyCollectionAccess.parseAllowedIds(
                                ApiKeyCollectionAccess.serializeAllowedIds(ids)));
        semantic.put("capabilities",
                ApiCapabilitySupport.normalizeRequested(request.getCapabilities()));
        semantic.put("requestsPerMinute", request.getRequestsPerMinute());
        semantic.put("role", role);
        try {
            return MAPPER.writeValueAsString(semantic);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build provisioning fingerprint", e);
        }
    }

    private static final class HexFormatHolder {
        private static String format(byte[] bytes) {
            return java.util.HexFormat.of().formatHex(bytes);
        }
    }
}
