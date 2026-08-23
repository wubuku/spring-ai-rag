package com.springairag.core.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiKeyResponse;
import com.springairag.api.dto.ApiPrincipalPolicyUpdateRequest;
import com.springairag.api.dto.ApiPrincipalResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 稳定 API principal、版本化 credential 与策略生命周期服务。 */
@Service
public class ApiKeyManagementService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyManagementService.class);
    private static final String KEY_PREFIX = "rag_sk_";
    private static final Duration LAST_USED_TOUCH_INTERVAL = Duration.ofMinutes(5);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 只抑制近似审计写入，不缓存任何认证或授权决定。 */
    private final Cache<String, Boolean> lastUsedTouchCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(LAST_USED_TOUCH_INTERVAL.toMinutes(), TimeUnit.MINUTES)
            .build();

    private final RagApiKeyRepository apiKeyRepository;
    private final RagApiPrincipalRepository principalRepository;
    private final CollectionIdentityResolver collectionIdentityResolver;
    private final JdbcTemplate jdbcTemplate;

    public ApiKeyManagementService(
            RagApiKeyRepository apiKeyRepository,
            RagApiPrincipalRepository principalRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            CollectionIdentityResolver collectionIdentityResolver,
            JdbcTemplate jdbcTemplate) {
        this.apiKeyRepository = apiKeyRepository;
        this.principalRepository = principalRepository;
        this.collectionIdentityResolver = collectionIdentityResolver;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ApiKeyCreatedResponse generateKey(ApiKeyCreateRequest request) {
        return createPrincipal(request, ApiKeyRole.NORMAL);
    }

    @Transactional
    public ApiKeyCreatedResponse generateManagedKey(ApiKeyCreateRequest request) {
        validateManagedExpiry(request.getExpiresAt());
        return createPrincipal(request, ApiKeyRole.NORMAL);
    }

    private ApiKeyCreatedResponse createPrincipal(
            ApiKeyCreateRequest request,
            ApiKeyRole role) {
        Objects.requireNonNull(request, "request must not be null");
        LocalDateTime now = LocalDateTime.now();
        String rawKey = generateRawKey();
        String keyId = generateKeyId();
        String allowedIds = ApiKeyCollectionAccess.serializeAllowedIds(
                request.getAllowedCollectionIds());

        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(keyId);
        principal.setName(request.getName());
        principal.setRole(role);
        principal.setAllowedCollectionIds(allowedIds);
        principal.setExpiresAt(request.getExpiresAt());
        principal.setRequestsPerMinute(request.getRequestsPerMinute());
        principal.setPolicyVersion(1L);
        principal.setNextCredentialVersion(2);
        principal.setCreatedAt(now);
        principal.setUpdatedAt(now);
        principalRepository.save(principal);

        RagApiKey credential = credential(keyId, rawKey, 1, principal, now);
        apiKeyRepository.save(credential);
        log.info("API principal created: principalId={}, credentialVersion=1", keyId);
        return createdResponse(rawKey, credential, principal);
    }

    @Transactional
    public boolean revokeKey(String keyId) {
        return revoke(keyId, false);
    }

    @Transactional
    public boolean revokeManagedKey(String keyId) {
        return revoke(keyId, true);
    }

    private boolean revoke(String keyId, boolean environmentRoot) {
        Objects.requireNonNull(keyId, "keyId must not be null");
        Optional<RagApiKey> initial = apiKeyRepository.findByKeyId(keyId);
        if (initial.isEmpty()) {
            return false;
        }
        String principalId = initial.get().getPrincipalId();
        if (principalRepository.acquireManagementWrite(principalId) == 0) {
            return false;
        }

        RagApiPrincipal principal = principalRepository.findByPrincipalId(principalId)
                .orElseThrow(() -> new RagException(
                        ErrorCode.NOT_FOUND, "API principal was not found"));
        RagApiKey requested = apiKeyRepository.findByKeyId(keyId)
                .orElseThrow(() -> new RagException(
                        ErrorCode.NOT_FOUND, "API credential was not found"));
        int latestVersion = principal.getNextCredentialVersion() - 1;

        if (principal.getRevokedAt() != null) {
            if (Objects.equals(requested.getCredentialVersion(), latestVersion)) {
                return true;
            }
            throw credentialNotCurrent();
        }
        RagApiKey current = apiKeyRepository
                .findFirstByPrincipalIdAndEnabledTrue(principalId)
                .orElse(null);
        if (current == null || !current.getKeyId().equals(keyId)
                || !Objects.equals(requested.getCredentialVersion(), latestVersion)) {
            throw credentialNotCurrent();
        }

        if (principal.getRole() == ApiKeyRole.ADMIN) {
            decrementAdminGuard(environmentRoot);
        }
        LocalDateTime now = LocalDateTime.now();
        principal.setRevokedAt(now);
        principal.setUpdatedAt(now);
        principalRepository.save(principal);
        if (apiKeyRepository.disableByKeyId(keyId, now) != 1) {
            throw credentialNotCurrent();
        }
        lastUsedTouchCache.invalidate(principalId);
        log.info("API principal revoked: principalId={}, credentialId={}", principalId, keyId);
        return true;
    }

    private void decrementAdminGuard(boolean environmentRoot) {
        int updated;
        if (environmentRoot) {
            updated = jdbcTemplate.update("""
                    UPDATE rag_api_admin_guard
                    SET non_revoked_admin_count = GREATEST(non_revoked_admin_count - 1, 0),
                        version = version + 1
                    WHERE singleton = TRUE
                    """);
        } else {
            updated = jdbcTemplate.update("""
                    UPDATE rag_api_admin_guard
                    SET non_revoked_admin_count = non_revoked_admin_count - 1,
                        version = version + 1
                    WHERE singleton = TRUE AND non_revoked_admin_count > 1
                    """);
        }
        if (updated != 1) {
            throw new RagException(
                    ErrorCode.LAST_ADMIN_REQUIRED,
                    "The last non-revoked ADMIN principal cannot be revoked without environment root mode");
        }
    }

    @Transactional
    public ApiKeyCreatedResponse rotateKey(String keyId) {
        return rotate(keyId);
    }

    @Transactional
    public ApiKeyCreatedResponse rotateManagedKey(String keyId) {
        return rotate(keyId);
    }

    private ApiKeyCreatedResponse rotate(String keyId) {
        Objects.requireNonNull(keyId, "keyId must not be null");
        Optional<RagApiKey> initial = apiKeyRepository.findByKeyId(keyId);
        if (initial.isEmpty()) {
            return null;
        }
        String principalId = initial.get().getPrincipalId();
        if (principalRepository.acquireManagementWrite(principalId) == 0) {
            return null;
        }

        RagApiPrincipal principal = principalRepository.findByPrincipalId(principalId)
                .orElseThrow(() -> new RagException(
                        ErrorCode.NOT_FOUND, "API principal was not found"));
        ensureActive(principal);
        RagApiKey current = apiKeyRepository
                .findFirstByPrincipalIdAndEnabledTrue(principalId)
                .orElse(null);
        if (current == null || !current.getKeyId().equals(keyId)) {
            throw credentialNotCurrent();
        }

        LocalDateTime now = LocalDateTime.now();
        int version = principal.getNextCredentialVersion();
        principal.setNextCredentialVersion(version + 1);
        principal.setUpdatedAt(now);
        principalRepository.saveAndFlush(principal);

        if (apiKeyRepository.disableByKeyId(keyId, now) != 1) {
            throw credentialNotCurrent();
        }
        String rawKey = generateRawKey();
        RagApiKey replacement = credential(
                generateKeyId(), rawKey, version, principal, now);
        apiKeyRepository.save(replacement);
        log.info("API credential rotated: principalId={}, credentialVersion={}",
                principalId, version);
        return createdResponse(rawKey, replacement, principal);
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> listKeys() {
        return apiKeyRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ApiPrincipalResponse> listPrincipals() {
        return principalRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toPrincipalResponse)
                .toList();
    }

    @Transactional
    public ApiPrincipalResponse updatePolicy(
            String principalId,
            ApiPrincipalPolicyUpdateRequest request,
            List<Long> allowedCollectionIds,
            boolean environmentRoot) {
        Objects.requireNonNull(principalId, "principalId must not be null");
        if (principalRepository.acquireManagementWrite(principalId) == 0) {
            return null;
        }
        RagApiPrincipal principal = principalRepository.findByPrincipalId(principalId)
                .orElse(null);
        if (principal == null) {
            return null;
        }
        ensureActive(principal);
        if (!Objects.equals(principal.getPolicyVersion(), request.getExpectedPolicyVersion())) {
            throw new RagException(
                    ErrorCode.POLICY_VERSION_CONFLICT,
                    "API principal policy version is " + principal.getPolicyVersion());
        }
        if (!environmentRoot && principal.getRole() == ApiKeyRole.ADMIN
                && !Objects.equals(principal.getExpiresAt(), request.getExpiresAt())) {
            throw new RagException(
                    ErrorCode.BAD_REQUEST,
                    "Legacy ADMIN expiry cannot be changed through the policy endpoint");
        }
        if (environmentRoot) {
            validateManagedExpiry(request.getExpiresAt());
        }

        LocalDateTime now = LocalDateTime.now();
        String serialized = ApiKeyCollectionAccess.serializeAllowedIds(allowedCollectionIds);
        principal.setName(request.getName());
        principal.setExpiresAt(request.getExpiresAt());
        principal.setAllowedCollectionIds(serialized);
        principal.setRequestsPerMinute(request.getRequestsPerMinute());
        principal.setPolicyVersion(principal.getPolicyVersion() + 1);
        principal.setUpdatedAt(now);
        principalRepository.saveAndFlush(principal);

        apiKeyRepository.findFirstByPrincipalIdAndEnabledTrue(principalId)
                .ifPresent(current -> {
                    current.setName(principal.getName());
                    current.setRole(principal.getRole());
                    current.setExpiresAt(principal.getExpiresAt());
                    current.setAllowedCollectionIds(principal.getAllowedCollectionIds());
                    apiKeyRepository.save(current);
                });
        return toPrincipalResponse(principal);
    }

    /** 每次调用都执行 credential/principal 权威联表查询。 */
    @Transactional
    public AuthenticatedApiPrincipal authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank() || !rawKey.startsWith(KEY_PREFIX)) {
            return null;
        }
        String keyHash = sha256(rawKey);
        LocalDateTime now = LocalDateTime.now();
        AuthenticatedApiPrincipal principal = apiKeyRepository.authenticate(keyHash, now)
                .map(p -> new AuthenticatedApiPrincipal(
                        p.getPrincipalId(),
                        p.getCredentialId(),
                        p.getCredentialVersion(),
                        ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                        p.getRole(),
                        p.getAllowedCollectionIds(),
                        p.getExpiresAt(),
                        p.getPolicyVersion(),
                        p.getRequestsPerMinute()))
                .orElse(null);
        if (principal != null) {
            touchLastUsed(principal.getPrincipalId(), now);
        }
        return principal;
    }

    @Transactional
    public String validateKey(String rawKey) {
        AuthenticatedApiPrincipal principal = authenticate(rawKey);
        return principal == null ? null : principal.getPrincipalId();
    }

    /** 旧测试/扩展兼容入口。生产 request context 不再使用 JPA entity。 */
    @Deprecated
    @Transactional
    public RagApiKey validateKeyEntity(String rawKey) {
        AuthenticatedApiPrincipal principal = authenticate(rawKey);
        return principal == null ? null
                : apiKeyRepository.findByKeyId(principal.getCredentialId()).orElse(null);
    }

    @Transactional(readOnly = true)
    public AuthenticatedApiPrincipal findActivePrincipal(String principalId) {
        RagApiPrincipal principal = principalRepository.findByPrincipalId(principalId).orElse(null);
        if (principal == null || principal.getRevokedAt() != null
                || isExpired(principal.getExpiresAt())) {
            return null;
        }
        RagApiKey current = apiKeyRepository
                .findFirstByPrincipalIdAndEnabledTrue(principalId)
                .orElse(null);
        if (current == null) {
            return null;
        }
        return new AuthenticatedApiPrincipal(
                principalId,
                current.getKeyId(),
                current.getCredentialVersion(),
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                principal.getRole(),
                principal.getAllowedCollectionIds(),
                principal.getExpiresAt(),
                principal.getPolicyVersion(),
                principal.getRequestsPerMinute());
    }

    private void touchLastUsed(String principalId, LocalDateTime now) {
        if (lastUsedTouchCache.getIfPresent(principalId) != null) {
            return;
        }
        try {
            principalRepository.touchLastUsedIfOlder(
                    principalId,
                    now,
                    now.minus(LAST_USED_TOUCH_INTERVAL));
            lastUsedTouchCache.put(principalId, Boolean.TRUE);
        } catch (DataAccessException e) {
            log.warn("API principal last-used audit update failed");
        }
    }

    private RagApiKey credential(
            String credentialId,
            String rawKey,
            int version,
            RagApiPrincipal principal,
            LocalDateTime now) {
        RagApiKey credential = new RagApiKey();
        credential.setKeyId(credentialId);
        credential.setKeyHash(sha256(rawKey));
        credential.setPrincipalId(principal.getPrincipalId());
        credential.setCredentialVersion(version);
        credential.setName(principal.getName());
        credential.setExpiresAt(principal.getExpiresAt());
        credential.setEnabled(true);
        credential.setRole(principal.getRole());
        credential.setAllowedCollectionIds(principal.getAllowedCollectionIds());
        credential.setCreatedAt(now);
        return credential;
    }

    private ApiKeyCreatedResponse createdResponse(
            String rawKey,
            RagApiKey credential,
            RagApiPrincipal principal) {
        List<Long> allowedIds = ApiKeyCollectionAccess.parseAllowedIds(
                principal.getAllowedCollectionIds());
        ApiKeyCreatedResponse response = new ApiKeyCreatedResponse(
                credential.getKeyId(),
                rawKey,
                principal.getName(),
                principal.getExpiresAt(),
                allowedIds.isEmpty() ? null : allowedIds);
        response.setPrincipalId(principal.getPrincipalId());
        response.setCredentialVersion(credential.getCredentialVersion());
        response.setPolicyVersion(principal.getPolicyVersion());
        response.setRequestsPerMinute(principal.getRequestsPerMinute());
        response.setAllowedCollectionKeys(collectionKeys(allowedIds));
        return response;
    }

    private ApiKeyResponse toResponse(RagApiKey credential) {
        RagApiPrincipal principal = principalRepository
                .findByPrincipalId(credential.getPrincipalId())
                .orElseThrow(() -> new IllegalStateException(
                        "Credential references a missing principal"));
        ApiKeyResponse response = new ApiKeyResponse(
                credential.getKeyId(),
                principal.getName(),
                credential.getCreatedAt(),
                principal.getLastUsedAt(),
                principal.getExpiresAt(),
                credential.getEnabled());
        response.setPrincipalId(principal.getPrincipalId());
        response.setCredentialVersion(credential.getCredentialVersion());
        response.setCurrentCredential(credential.isEnabled()
                && principal.getRevokedAt() == null);
        response.setPolicyVersion(principal.getPolicyVersion());
        response.setRequestsPerMinute(principal.getRequestsPerMinute());
        response.setRole(principal.getRole().name());
        List<Long> allowedIds = ApiKeyCollectionAccess.parseAllowedIds(
                principal.getAllowedCollectionIds());
        response.setAllowedCollectionIds(allowedIds.isEmpty() ? null : allowedIds);
        response.setAllowedCollectionKeys(collectionKeys(allowedIds));
        return response;
    }

    private ApiPrincipalResponse toPrincipalResponse(RagApiPrincipal principal) {
        RagApiKey current = apiKeyRepository
                .findFirstByPrincipalIdAndEnabledTrue(principal.getPrincipalId())
                .orElse(null);
        ApiPrincipalResponse response = new ApiPrincipalResponse();
        response.setPrincipalId(principal.getPrincipalId());
        response.setName(principal.getName());
        response.setRole(principal.getRole().name());
        response.setExpiresAt(principal.getExpiresAt());
        response.setRequestsPerMinute(principal.getRequestsPerMinute());
        response.setPolicyVersion(principal.getPolicyVersion());
        response.setLastUsedAt(principal.getLastUsedAt());
        response.setCreatedAt(principal.getCreatedAt());
        response.setUpdatedAt(principal.getUpdatedAt());
        response.setAllowedCollectionKeys(collectionKeys(
                ApiKeyCollectionAccess.parseAllowedIds(
                        principal.getAllowedCollectionIds())));
        if (principal.getRevokedAt() != null) {
            response.setStatus("REVOKED");
        } else if (isExpired(principal.getExpiresAt())) {
            response.setStatus("EXPIRED");
        } else {
            response.setStatus("ACTIVE");
        }
        if (current != null) {
            response.setCurrentCredentialId(current.getKeyId());
            response.setCurrentCredentialVersion(current.getCredentialVersion());
        }
        return response;
    }

    private List<String> collectionKeys(List<Long> allowedIds) {
        if (allowedIds.isEmpty() || collectionIdentityResolver == null) {
            return null;
        }
        return collectionIdentityResolver.mapKeys(allowedIds).values().stream().toList();
    }

    private void ensureActive(RagApiPrincipal principal) {
        if (principal.getRevokedAt() != null || isExpired(principal.getExpiresAt())) {
            throw new RagException(
                    ErrorCode.PRINCIPAL_NOT_ACTIVE,
                    "API principal is revoked or expired");
        }
    }

    private RagException credentialNotCurrent() {
        return new RagException(
                ErrorCode.CREDENTIAL_NOT_CURRENT,
                "The requested credential is not the current credential");
    }

    private boolean isExpired(LocalDateTime expiresAt) {
        return expiresAt != null && !expiresAt.isAfter(LocalDateTime.now());
    }

    private void validateManagedExpiry(LocalDateTime expiresAt) {
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required for managed API keys");
        }
        if (!expiresAt.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
    }

    String generateRawKey() {
        return KEY_PREFIX + randomHex(32);
    }

    String generateKeyId() {
        return "rag_k_" + randomHex(16);
    }

    private String randomHex(int byteCount) {
        byte[] random = new byte[byteCount];
        SECURE_RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
