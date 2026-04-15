package com.springairag.core.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiKeyResponse;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.repository.RagApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * API Key management service.
 *
 * <p>Handles creation, validation, revocation, and rotation of API keys.
 * Raw keys are never stored — only their SHA-256 hashes.
 */
@Service
public class ApiKeyManagementService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyManagementService.class);
    private static final String KEY_PREFIX = "rag_sk_";
    /** Short-lived cache for validated keys: avoids DB round-trip on every authenticated request. */
    private static final Cache<String, RagApiKey> VALIDATED_KEY_CACHE = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    private final RagApiKeyRepository apiKeyRepository;

    public ApiKeyManagementService(RagApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * Generate a new API key.
     *
     * @param request creation request with name and optional expiration
     * @return created key metadata including the raw key (shown only once)
     */
    @Transactional
    public ApiKeyCreatedResponse generateKey(ApiKeyCreateRequest request) {
        String rawKey = generateRawKey();
        String keyId = generateKeyId();
        String keyHash = sha256(rawKey);

        RagApiKey entity = new RagApiKey();
        entity.setKeyId(keyId);
        entity.setKeyHash(keyHash);
        entity.setName(request.getName());
        entity.setExpiresAt(request.getExpiresAt());
        entity.setEnabled(true);

        apiKeyRepository.save(entity);
        log.info("API key created: keyId={}, name={}", keyId, request.getName());

        return new ApiKeyCreatedResponse(keyId, rawKey, request.getName(), request.getExpiresAt());
    }

    /**
     * Revoke (disable) an API key.
     *
     * @param keyId public key identifier
     * @return true if key was found and revoked, false if not found
     */
    @Transactional
    public boolean revokeKey(String keyId) {
        int updated = apiKeyRepository.disableByKeyId(keyId);
        if (updated > 0) {
            // Invalidate cache entries for this keyId (we invalidate all since we don't store reverse mapping)
            VALIDATED_KEY_CACHE.invalidateAll();
            log.info("API key revoked: keyId={}", keyId);
            return true;
        }
        return false;
    }

    /**
     * Rotate an API key: disable the old one and create a new one.
     *
     * @param keyId public key identifier of the key to rotate
     * @return new key metadata including raw key (shown only once), or null if key not found
     */
    @Transactional
    public ApiKeyCreatedResponse rotateKey(String keyId) {
        Optional<RagApiKey> existing = apiKeyRepository.findByKeyId(keyId);
        if (existing.isEmpty()) {
            return null;
        }

        // Disable the old key
        apiKeyRepository.disableByKeyId(keyId);
        // Invalidate cache entries for this keyId (short TTL makes this acceptable)
        VALIDATED_KEY_CACHE.invalidateAll();
        log.info("API key rotated (old key disabled): keyId={}", keyId);

        // Create a new key with the same name and expiration
        RagApiKey oldKey = existing.get();
        ApiKeyCreateRequest request = new ApiKeyCreateRequest(oldKey.getName(), oldKey.getExpiresAt());
        return generateKey(request);
    }

    /**
     * List all API keys (metadata only, raw keys never returned).
     */
    @Transactional(readOnly = true)
    public List<ApiKeyResponse> listKeys() {
        return apiKeyRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Validate a raw API key against stored hashes.
     *
     * @param rawKey the raw key value from the request header
     * @return keyId if valid, null if invalid or disabled or expired
     */
    @Transactional
    public String validateKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        // Only DB-stored keys (rag_sk_ prefix) use hash lookup;
        // legacy configured keys are handled by ApiKeyAuthFilter directly
        if (!rawKey.startsWith(KEY_PREFIX)) {
            return null;
        }
        String keyHash = sha256(rawKey);
        return apiKeyRepository.findByKeyHash(keyHash)
                .filter(k -> k.getEnabled() && !isExpired(k))
                .map(k -> {
                    touchLastUsed(k);
                    return k.getKeyId();
                })
                .orElse(null);
    }

    /**
     * Validate a raw key and return the RagApiKey entity if valid.
     * Uses short-lived in-memory cache (30s TTL) to avoid repeated DB lookups.
     *
     * @param rawKey the raw key value from the request header
     * @return RagApiKey entity if valid, null if invalid, disabled, or expired
     */
    @Transactional
    public RagApiKey validateKeyEntity(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        if (!rawKey.startsWith(KEY_PREFIX)) {
            return null;
        }
        String keyHash = sha256(rawKey);

        // Check cache first
        RagApiKey cached = VALIDATED_KEY_CACHE.getIfPresent(keyHash);
        if (cached != null) {
            if (cached.getEnabled() && !isExpired(cached)) {
                touchLastUsed(cached);
                return cached;
            }
            // Cached as invalid — short-circuit DB lookup
            return null;
        }

        // Cache miss: query DB
        Optional<RagApiKey> result = apiKeyRepository.findByKeyHash(keyHash)
                .filter(k -> k.getEnabled() && !isExpired(k));

        if (result.isPresent()) {
            RagApiKey validKey = result.get();
            VALIDATED_KEY_CACHE.put(keyHash, validKey);
            touchLastUsed(validKey);
            return validKey;
        }

        // Do NOT cache invalid results — next request goes to DB again (allows quick un-revoke)
        return null;
    }

    private void touchLastUsed(RagApiKey key) {
        apiKeyRepository.updateLastUsed(key.getKeyId(), LocalDateTime.now());
    }

    private boolean isExpired(RagApiKey key) {
        return key.getExpiresAt() != null && key.getExpiresAt().isBefore(LocalDateTime.now());
    }

    private ApiKeyResponse toResponse(RagApiKey entity) {
        ApiKeyResponse r = new ApiKeyResponse(
                entity.getKeyId(),
                entity.getName(),
                entity.getCreatedAt(),
                entity.getLastUsedAt(),
                entity.getExpiresAt(),
                entity.getEnabled()
        );
        r.setRole(entity.getRole() != null ? entity.getRole().name() : null);
        return r;
    }

    private String generateRawKey() {
        return KEY_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    private String generateKeyId() {
        return "rag_k_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
