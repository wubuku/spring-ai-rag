package com.springairag.core.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiKeyResponse;
import com.springairag.api.dto.ApiKeyRotationResponse;
import com.springairag.api.dto.ApiPrincipalPolicyUpdateRequest;
import com.springairag.api.dto.ApiPrincipalResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.config.RagApiKeyProvisioningProperties;
import com.springairag.core.config.RagApiKeyRotationProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.ApiKeyProvisioningOperation;
import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.UUID;
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
    private final ApiKeyProvisioningOperationRepository provisioningOperationRepository;
    private final ApiKeyRotationOperationRepository rotationOperationRepository;
    private final RagApiKeyProvisioningProperties provisioningProperties;
    private final RagApiKeyRotationProperties rotationProperties;
    private final TransactionTemplate provisioningTransaction;
    private final TransactionTemplate rotationTransaction;
    private final ApiPrincipalLifecycleEventPublisher lifecycleEventPublisher;

    public ApiKeyManagementService(
            RagApiKeyRepository apiKeyRepository,
            RagApiPrincipalRepository principalRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            CollectionIdentityResolver collectionIdentityResolver,
            JdbcTemplate jdbcTemplate) {
        this(apiKeyRepository, principalRepository, collectionIdentityResolver, jdbcTemplate,
                null, null, new RagProperties(), null, null);
    }

    public ApiKeyManagementService(
            RagApiKeyRepository apiKeyRepository,
            RagApiPrincipalRepository principalRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            CollectionIdentityResolver collectionIdentityResolver,
            JdbcTemplate jdbcTemplate,
            ApiKeyProvisioningOperationRepository provisioningOperationRepository,
            RagProperties ragProperties,
            PlatformTransactionManager transactionManager) {
        this(apiKeyRepository, principalRepository, collectionIdentityResolver,
                jdbcTemplate, provisioningOperationRepository, null, ragProperties,
                transactionManager, null);
    }

    public ApiKeyManagementService(
            RagApiKeyRepository apiKeyRepository,
            RagApiPrincipalRepository principalRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            CollectionIdentityResolver collectionIdentityResolver,
            JdbcTemplate jdbcTemplate,
            ApiKeyProvisioningOperationRepository provisioningOperationRepository,
            ApiKeyRotationOperationRepository rotationOperationRepository,
            RagProperties ragProperties,
            PlatformTransactionManager transactionManager) {
        this(apiKeyRepository, principalRepository, collectionIdentityResolver,
                jdbcTemplate, provisioningOperationRepository,
                rotationOperationRepository, ragProperties, transactionManager,
                null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ApiKeyManagementService(
            RagApiKeyRepository apiKeyRepository,
            RagApiPrincipalRepository principalRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            CollectionIdentityResolver collectionIdentityResolver,
            JdbcTemplate jdbcTemplate,
            ApiKeyProvisioningOperationRepository provisioningOperationRepository,
            ApiKeyRotationOperationRepository rotationOperationRepository,
            RagProperties ragProperties,
            PlatformTransactionManager transactionManager,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ApiPrincipalLifecycleEventPublisher lifecycleEventPublisher) {
        this.apiKeyRepository = apiKeyRepository;
        this.principalRepository = principalRepository;
        this.collectionIdentityResolver = collectionIdentityResolver;
        this.jdbcTemplate = jdbcTemplate;
        this.provisioningOperationRepository = provisioningOperationRepository;
        this.rotationOperationRepository = rotationOperationRepository;
        this.provisioningProperties = ragProperties.getApiKeyProvisioning();
        this.rotationProperties = ragProperties.getApiKeyRotation();
        this.provisioningTransaction = transactionManager == null
                ? null
                : new TransactionTemplate(transactionManager);
        this.rotationTransaction = transactionManager == null
                ? null
                : new TransactionTemplate(transactionManager);
        this.lifecycleEventPublisher = lifecycleEventPublisher;
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

    /**
     * Creates or replays a key-backed provisioning operation.
     *
     * <p>The ledger insert is in the same transaction as the principal and
     * credential. A unique constraint is the concurrency coordinator; a
     * losing transaction retries from a new transaction and reads the winner.
     */
    public ProvisioningResult generateIdempotentKey(
            ApiKeyCreateRequest request,
            ApiKeyRole role,
            String ownerId,
            String idempotencyKeyHash,
            boolean managed) {
        if (!provisioningProperties.isEnabled()) {
            throw new RagException(
                    ErrorCode.API_KEY_PROVISIONING_IDEMPOTENCY_DISABLED,
                    "API key provisioning idempotency is disabled");
        }
        if (provisioningOperationRepository == null) {
            throw new RagException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "API key provisioning ledger is unavailable");
        }
        Objects.requireNonNull(request, "request must not be null");
        if (managed) {
            validateManagedExpiry(request.getExpiresAt());
        }
        if (ownerId == null || ownerId.isBlank()
                || idempotencyKeyHash == null || idempotencyKeyHash.isBlank()) {
            throw new IllegalArgumentException("Provisioning owner and idempotency hash are required");
        }

        String fingerprint = ApiKeyProvisioningFingerprint.sha256(request, role.name());
        int attempts = provisioningProperties.getConcurrentRetryAttempts();
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                ProvisioningResult result = runProvisioningTransaction(
                        request, role, ownerId, idempotencyKeyHash, fingerprint);
                if (result != null) {
                    return result;
                }
            } catch (DataIntegrityViolationException race) {
                if (attempt + 1 >= attempts) {
                    throw new RagException(
                            ErrorCode.SERVICE_UNAVAILABLE,
                            "Unable to resolve a concurrent provisioning request", race);
                }
                try {
                    Thread.sleep(25L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RagException(
                            ErrorCode.SERVICE_UNAVAILABLE,
                            "Provisioning retry was interrupted", interrupted);
                }
            }
        }
        throw new RagException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "Unable to resolve API key provisioning");
    }

    private ProvisioningResult runProvisioningTransaction(
            ApiKeyCreateRequest request,
            ApiKeyRole role,
            String ownerId,
            String idempotencyKeyHash,
            String fingerprint) {
        if (provisioningTransaction == null) {
            return provisionInCurrentTransaction(
                    request, role, ownerId, idempotencyKeyHash, fingerprint);
        }
        return provisioningTransaction.execute(status ->
                provisionInCurrentTransaction(
                        request, role, ownerId, idempotencyKeyHash, fingerprint));
    }

    private ProvisioningResult provisionInCurrentTransaction(
            ApiKeyCreateRequest request,
            ApiKeyRole role,
            String ownerId,
            String idempotencyKeyHash,
            String fingerprint) {
        Optional<ApiKeyProvisioningOperation> existing =
                provisioningOperationRepository.findByOwnerIdAndIdempotencyKeyHash(
                        ownerId, idempotencyKeyHash);
        if (existing.isPresent()) {
            ApiKeyProvisioningOperation operation = existing.get();
            if (!fingerprint.equals(operation.getRequestFingerprintSha256())) {
                throw new RagException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "The Idempotency-Key was already used for a different request");
            }
            return new ProvisioningResult(replayResponse(operation), true);
        }

        ApiKeyCreatedResponse response = createPrincipal(request, role);
        LocalDateTime now = LocalDateTime.now();
        ApiKeyProvisioningOperation operation = new ApiKeyProvisioningOperation();
        operation.setOwnerId(ownerId);
        operation.setIdempotencyKeyHash(idempotencyKeyHash);
        operation.setRequestFingerprintSha256(fingerprint);
        operation.setPrincipalId(response.getPrincipalId());
        operation.setCredentialId(response.getKeyId());
        operation.setCredentialVersion(response.getCredentialVersion());
        operation.setCreatedAt(now);
        operation.setUpdatedAt(now);
        operation.setCompletedAt(now);
        provisioningOperationRepository.saveAndFlush(operation);
        return new ProvisioningResult(response, false);
    }

    private ApiKeyCreatedResponse replayResponse(ApiKeyProvisioningOperation operation) {
        RagApiPrincipal principal = principalRepository
                .findByPrincipalId(operation.getPrincipalId())
                .orElseThrow(() -> new RagException(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        "The provisioning result no longer has a principal"));
        RagApiKey current = apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                        operation.getPrincipalId())
                .orElse(null);
        boolean active = current != null
                && principal.getRevokedAt() == null
                && !isExpired(principal.getExpiresAt());
        ApiKeyCreatedResponse response = active
                ? new ApiKeyCreatedResponse(
                        current.getKeyId(), null, principal.getName(),
                        principal.getExpiresAt(),
                        allowedIds(principal))
                : new ApiKeyCreatedResponse(
                        null, null, principal.getName(),
                        principal.getExpiresAt(),
                        allowedIds(principal));
        response.setPrincipalId(principal.getPrincipalId());
        response.setCredentialVersion(active ? current.getCredentialVersion() : null);
        response.setPolicyVersion(principal.getPolicyVersion());
        response.setRequestsPerMinute(principal.getRequestsPerMinute());
        response.setCapabilities(ApiCapabilitySupport.effectiveForRole(
                principal.getRole(),
                ApiCapabilitySupport.normalizePersisted(principal.getCapabilities())));
        response.setAllowedCollectionKeys(
                active ? collectionKeys(allowedIds(principal)) : null);
        response.setSecretAvailable(false);
        response.setIdempotentReplay(true);
        response.setCurrentCredentialActive(active);
        response.setWarning(
                "The principal already exists. The raw credential cannot be shown again; "
                        + "rotate the current credential if the original secret was not saved.");
        return response;
    }

    private List<Long> allowedIds(RagApiPrincipal principal) {
        List<Long> ids = ApiKeyCollectionAccess.parseAllowedIds(
                principal.getAllowedCollectionIds());
        return ids.isEmpty() ? null : ids;
    }

    @Scheduled(
            fixedDelayString = "${rag.api-key-provisioning.cleanup-interval-ms:3600000}",
            zone = "${spring.task.scheduling.timezone:Asia/Shanghai}")
    public void cleanupProvisioningLedger() {
        if (!provisioningProperties.isEnabled()
                || provisioningOperationRepository == null) {
            return;
        }
        try {
            provisioningOperationRepository.deleteCompletedBefore(
                    LocalDateTime.now().minus(provisioningProperties.getRetention()),
                    provisioningProperties.getCleanupBatchSize());
        } catch (DataAccessException error) {
            log.warn("API key provisioning ledger cleanup failed");
        }
    }

    public record ProvisioningResult(
            ApiKeyCreatedResponse response,
            boolean replay) {
    }

    public record RotationResult(
            ApiKeyRotationResponse response,
            boolean replay) {
    }

    @Transactional
    public RotationResult prepareRotation(
            String currentKeyId,
            Integer requestedOverlapSeconds,
            String idempotencyKeyHash,
            ApiAccessPolicy caller,
            boolean environmentRoot) {
        requireRotationLedger();
        Objects.requireNonNull(currentKeyId, "currentKeyId must not be null");
        if (idempotencyKeyHash == null || idempotencyKeyHash.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        RagApiKey initial = apiKeyRepository.findByKeyId(currentKeyId).orElse(null);
        if (initial == null) {
            return null;
        }
        String principalId = initial.getPrincipalId();
        authorizeRotation(principalId, currentKeyId, caller, environmentRoot, true);
        if (principalRepository.acquireManagementWrite(principalId) == 0) {
            return null;
        }

        String fingerprint = rotationFingerprint(
                currentKeyId, requestedOverlapSeconds);
        Optional<ApiKeyRotationOperation> replay =
                rotationOperationRepository.findByPrincipalIdAndIdempotencyKeyHash(
                        principalId, idempotencyKeyHash);
        if (replay.isPresent()) {
            ApiKeyRotationOperation operation = replay.get();
            if (!fingerprint.equals(operation.getRequestFingerprintSha256())) {
                throw new RagException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "The Idempotency-Key was already used for a different rotation request");
            }
            expirePendingIfNecessary(operation, LocalDateTime.now());
            return new RotationResult(
                    rotationResponse(operation, null, true), true);
        }

        LocalDateTime now = LocalDateTime.now();
        cleanupExpiredRotationForPrincipal(principalId, now);
        RagApiPrincipal principal = principalRepository.findByPrincipalId(principalId)
                .orElseThrow(() -> new RagException(
                        ErrorCode.NOT_FOUND, "API principal was not found"));
        ensureActive(principal);
        if (rotationOperationRepository
                .findByPrincipalIdAndStatus(
                        principalId, ApiKeyRotationStatus.PENDING)
                .isPresent()) {
            throw rotationPending();
        }
        RagApiKey current = apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(principalId)
                .orElse(null);
        if (current == null || !current.getKeyId().equals(currentKeyId)) {
            throw credentialNotCurrent();
        }

        int overlapSeconds = requestedOverlapSeconds == null
                ? rotationProperties.defaultOverlapSeconds()
                : requestedOverlapSeconds;
        if (overlapSeconds < 1
                || overlapSeconds > rotationProperties.maxOverlapSeconds()) {
            throw new IllegalArgumentException(
                    "overlapSeconds must be between 1 and "
                            + rotationProperties.maxOverlapSeconds());
        }
        LocalDateTime deadline = now.plusSeconds(overlapSeconds);
        if (principal.getExpiresAt() != null
                && principal.getExpiresAt().isBefore(deadline)) {
            deadline = principal.getExpiresAt();
        }
        if (!deadline.isAfter(now)) {
            throw new RagException(
                    ErrorCode.PRINCIPAL_NOT_ACTIVE,
                    "API principal expires before a rotation overlap can begin");
        }

        current.setRetireAt(deadline);
        apiKeyRepository.saveAndFlush(current);

        int version = principal.getNextCredentialVersion();
        principal.setNextCredentialVersion(version + 1);
        principal.setUpdatedAt(now);
        principalRepository.saveAndFlush(principal);

        String rawKey = generateRawKey();
        RagApiKey target = credential(
                generateKeyId(), rawKey, version, principal, now);
        apiKeyRepository.saveAndFlush(target);

        ApiKeyRotationOperation operation = new ApiKeyRotationOperation();
        operation.setRotationId(UUID.randomUUID());
        operation.setPrincipalId(principalId);
        operation.setIdempotencyKeyHash(idempotencyKeyHash);
        operation.setRequestFingerprintSha256(fingerprint);
        operation.setSourceCredentialId(current.getKeyId());
        operation.setTargetCredentialId(target.getKeyId());
        operation.setOverlapSeconds(overlapSeconds);
        operation.setExpiresAt(deadline);
        operation.setStatus(ApiKeyRotationStatus.PENDING);
        operation.setCreatedAt(now);
        operation.setUpdatedAt(now);
        rotationOperationRepository.saveAndFlush(operation);

        log.info(
                "API credential rotation prepared: principalId={}, rotationId={}, targetVersion={}, expiresAt={}",
                principalId, operation.getRotationId(), version, deadline);
        return new RotationResult(
                rotationResponse(operation, rawKey, false), false);
    }

    @Transactional
    public ApiKeyRotationResponse getRotation(
            UUID rotationId,
            ApiAccessPolicy caller,
            boolean environmentRoot) {
        ApiKeyRotationOperation initial = findRotation(rotationId);
        authorizeRotation(
                initial.getPrincipalId(), null, caller, environmentRoot, false);
        if (principalRepository.acquireManagementWrite(
                initial.getPrincipalId()) == 0) {
            throw new RagException(ErrorCode.NOT_FOUND, "API principal was not found");
        }
        ApiKeyRotationOperation operation = findRotation(rotationId);
        expirePendingIfNecessary(operation, LocalDateTime.now());
        return rotationResponse(operation, null, false);
    }

    @Transactional
    public ApiKeyRotationResponse completeRotation(
            UUID rotationId,
            ApiAccessPolicy caller,
            boolean environmentRoot) {
        ApiKeyRotationOperation initial = findRotation(rotationId);
        authorizeRotation(
                initial.getPrincipalId(), null, caller, environmentRoot, false);
        if (principalRepository.acquireManagementWrite(
                initial.getPrincipalId()) == 0) {
            throw new RagException(ErrorCode.NOT_FOUND, "API principal was not found");
        }
        ApiKeyRotationOperation operation = findRotation(rotationId);
        LocalDateTime now = LocalDateTime.now();
        if (operation.getStatus() == ApiKeyRotationStatus.COMPLETED) {
            return rotationResponse(operation, null, false);
        }
        if (operation.getStatus() == ApiKeyRotationStatus.EXPIRED
                || (operation.getStatus() == ApiKeyRotationStatus.PENDING
                && !operation.getExpiresAt().isAfter(now))) {
            expirePendingIfNecessary(operation, now);
            throw new RagException(
                    ErrorCode.CREDENTIAL_ROTATION_EXPIRED,
                    "The credential rotation overlap has expired");
        }
        if (operation.getStatus() != ApiKeyRotationStatus.PENDING) {
            throw rotationNotPending();
        }

        RagApiKey source = requiredRotationCredentials(operation).source();
        if (source.isEnabled()
                && apiKeyRepository.disableByKeyId(source.getKeyId(), now) != 1) {
            throw rotationConflict();
        }
        operation.setStatus(ApiKeyRotationStatus.COMPLETED);
        operation.setUpdatedAt(now);
        operation.setTerminalAt(now);
        rotationOperationRepository.saveAndFlush(operation);
        log.info("API credential rotation completed: principalId={}, rotationId={}",
                operation.getPrincipalId(), rotationId);
        return rotationResponse(operation, null, false);
    }

    @Transactional
    public ApiKeyRotationResponse cancelRotation(
            UUID rotationId,
            ApiAccessPolicy caller,
            boolean environmentRoot) {
        ApiKeyRotationOperation initial = findRotation(rotationId);
        authorizeRotation(
                initial.getPrincipalId(), null, caller, environmentRoot, false);
        if (principalRepository.acquireManagementWrite(
                initial.getPrincipalId()) == 0) {
            throw new RagException(ErrorCode.NOT_FOUND, "API principal was not found");
        }
        ApiKeyRotationOperation operation = findRotation(rotationId);
        LocalDateTime now = LocalDateTime.now();
        if (operation.getStatus() == ApiKeyRotationStatus.CANCELED) {
            return rotationResponse(operation, null, false);
        }
        if (operation.getStatus() == ApiKeyRotationStatus.EXPIRED
                || (operation.getStatus() == ApiKeyRotationStatus.PENDING
                && !operation.getExpiresAt().isAfter(now))) {
            expirePendingIfNecessary(operation, now);
            throw new RagException(
                    ErrorCode.CREDENTIAL_ROTATION_EXPIRED,
                    "The credential rotation overlap has expired");
        }
        if (operation.getStatus() != ApiKeyRotationStatus.PENDING) {
            throw rotationNotPending();
        }

        RotationCredentials credentials = requiredRotationCredentials(operation);
        RagApiKey target = credentials.target();
        if (target.isEnabled()
                && apiKeyRepository.disableByKeyId(target.getKeyId(), now) != 1) {
            throw rotationConflict();
        }
        apiKeyRepository.flush();
        RagApiKey source = credentials.source();
        if (!source.isEnabled()) {
            throw rotationConflict();
        }
        source.setRetireAt(null);
        apiKeyRepository.saveAndFlush(source);

        operation.setStatus(ApiKeyRotationStatus.CANCELED);
        operation.setUpdatedAt(now);
        operation.setTerminalAt(now);
        rotationOperationRepository.saveAndFlush(operation);
        log.info("API credential rotation canceled: principalId={}, rotationId={}",
                operation.getPrincipalId(), rotationId);
        return rotationResponse(operation, null, false);
    }

    @Scheduled(
            fixedDelayString = "${rag.api-key-rotation.cleanup-interval-ms:60000}",
            zone = "${spring.task.scheduling.timezone:Asia/Shanghai}")
    public void cleanupCredentialRotations() {
        if (rotationOperationRepository == null || rotationTransaction == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            List<UUID> expired = rotationOperationRepository.findExpiredRotationIds(
                    ApiKeyRotationStatus.PENDING,
                    now,
                    PageRequest.of(0, rotationProperties.getCleanupBatchSize()));
            for (UUID rotationId : expired) {
                rotationTransaction.executeWithoutResult(status ->
                        expireRotationById(rotationId));
            }
            rotationTransaction.executeWithoutResult(status ->
                    rotationOperationRepository.deleteTerminalBefore(
                            now.minus(rotationProperties.getOperationRetention()),
                            rotationProperties.getCleanupBatchSize()));
        } catch (DataAccessException error) {
            log.warn("API credential rotation cleanup failed");
        }
    }

    private void expireRotationById(UUID rotationId) {
        ApiKeyRotationOperation initial =
                rotationOperationRepository.findById(rotationId).orElse(null);
        if (initial == null
                || initial.getStatus() != ApiKeyRotationStatus.PENDING
                || initial.getExpiresAt().isAfter(LocalDateTime.now())) {
            return;
        }
        if (principalRepository.acquireManagementWrite(initial.getPrincipalId()) == 0) {
            return;
        }
        ApiKeyRotationOperation operation =
                rotationOperationRepository.findById(rotationId).orElse(null);
        if (operation != null) {
            expirePendingIfNecessary(operation, LocalDateTime.now());
        }
    }

    private ApiKeyCreatedResponse createPrincipal(
            ApiKeyCreateRequest request,
            ApiKeyRole role) {
        Objects.requireNonNull(request, "request must not be null");
        LocalDateTime now = LocalDateTime.now();
        String rawKey = generateRawKey();
        String keyId = generateKeyId();
        List<String> capabilities = ApiCapabilitySupport.normalizeRequested(
                request.getCapabilities());
        String allowedIds = ApiKeyCollectionAccess.serializeAllowedIds(
                request.getAllowedCollectionIds());

        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(keyId);
        principal.setName(request.getName());
        principal.setRole(role);
        principal.setAllowedCollectionIds(allowedIds);
        principal.setExpiresAt(request.getExpiresAt());
        principal.setRequestsPerMinute(request.getRequestsPerMinute());
        principal.setCapabilities(ApiCapabilitySupport.serialize(capabilities));
        principal.setPolicyVersion(1L);
        principal.setNextCredentialVersion(2);
        principal.setCreatedAt(now);
        principal.setUpdatedAt(now);
        principalRepository.save(principal);

        RagApiKey credential = credential(keyId, rawKey, 1, principal, now);
        apiKeyRepository.save(credential);
        publishLifecycleAfterCommit(keyId);
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

        if (principal.getRevokedAt() != null) {
            if (Objects.equals(requested.getRevokedAt(), principal.getRevokedAt())) {
                return true;
            }
            throw credentialNotCurrent();
        }
        LocalDateTime now = LocalDateTime.now();
        cleanupExpiredRotationForPrincipal(principalId, now);
        RagApiKey current = apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(principalId)
                .orElse(null);
        if (current == null || !current.getKeyId().equals(keyId)) {
            throw credentialNotCurrent();
        }

        if (principal.getRole() == ApiKeyRole.ADMIN) {
            decrementAdminGuard(environmentRoot);
        }
        principal.setRevokedAt(now);
        principal.setUpdatedAt(now);
        principalRepository.saveAndFlush(principal);
        if (apiKeyRepository.disableAllActiveByPrincipalId(principalId, now) < 1) {
            throw rotationConflict();
        }
        if (rotationOperationRepository != null) {
            rotationOperationRepository
                    .findByPrincipalIdAndStatus(
                            principalId, ApiKeyRotationStatus.PENDING)
                    .ifPresent(operation -> {
                        operation.setStatus(ApiKeyRotationStatus.REVOKED);
                        operation.setUpdatedAt(now);
                        operation.setTerminalAt(now);
                        rotationOperationRepository.save(operation);
                    });
        }
        lastUsedTouchCache.invalidate(principalId);
        publishLifecycleAfterCommit(principalId);
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
        LocalDateTime now = LocalDateTime.now();
        cleanupExpiredRotationForPrincipal(principalId, now);
        if (rotationOperationRepository != null
                && rotationOperationRepository
                .findByPrincipalIdAndStatus(
                        principalId, ApiKeyRotationStatus.PENDING)
                .isPresent()) {
            throw rotationPending();
        }
        RagApiKey current = apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(principalId)
                .orElse(null);
        if (current == null || !current.getKeyId().equals(keyId)) {
            throw credentialNotCurrent();
        }

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
        List<String> capabilities = request.getCapabilities() == null
                ? ApiCapabilitySupport.normalizePersisted(principal.getCapabilities())
                : ApiCapabilitySupport.normalizeRequested(request.getCapabilities());
        if (principal.getRole() == ApiKeyRole.ADMIN
                && !ApiCapabilitySupport.fullCapabilities().equals(capabilities)) {
            throw new RagException(
                    ErrorCode.BAD_REQUEST,
                    "ADMIN principals must retain full RAG capabilities");
        }

        LocalDateTime now = LocalDateTime.now();
        String serialized = ApiKeyCollectionAccess.serializeAllowedIds(allowedCollectionIds);
        principal.setName(request.getName());
        principal.setExpiresAt(request.getExpiresAt());
        principal.setAllowedCollectionIds(serialized);
        principal.setRequestsPerMinute(request.getRequestsPerMinute());
        principal.setCapabilities(ApiCapabilitySupport.serialize(
                ApiCapabilitySupport.effectiveForRole(principal.getRole(), capabilities)));
        principal.setPolicyVersion(principal.getPolicyVersion() + 1);
        principal.setUpdatedAt(now);
        principalRepository.saveAndFlush(principal);

        apiKeyRepository.updateActivePolicySnapshots(
                principalId,
                principal.getName(),
                principal.getRole(),
                principal.getExpiresAt(),
                principal.getAllowedCollectionIds());
        clampPendingRotationDeadline(principalId, principal.getExpiresAt(), now);
        ApiPrincipalResponse response = toPrincipalResponse(principal);
        publishLifecycleAfterCommit(principalId);
        return response;
    }

    private void publishLifecycleAfterCommit(String principalId) {
        if (lifecycleEventPublisher != null) {
            lifecycleEventPublisher.publishAfterCommit(principalId);
        }
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
                        p.getRequestsPerMinute(),
                        ApiCapabilitySupport.effectiveForRole(
                                p.getRole(),
                                ApiCapabilitySupport.normalizePersisted(
                                        p.getCapabilities()))))
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
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(principalId)
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
                principal.getRequestsPerMinute(),
                ApiCapabilitySupport.effectiveForRole(
                        principal.getRole(),
                        ApiCapabilitySupport.normalizePersisted(
                                principal.getCapabilities())));
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
        response.setCapabilities(ApiCapabilitySupport.effectiveForRole(
                principal.getRole(),
                ApiCapabilitySupport.normalizePersisted(principal.getCapabilities())));
        response.setAllowedCollectionKeys(collectionKeys(allowedIds));
        response.setSecretAvailable(rawKey != null);
        response.setIdempotentReplay(false);
        response.setCurrentCredentialActive(true);
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
                && credential.getRetireAt() == null
                && principal.getRevokedAt() == null);
        response.setRetiringCredential(credential.isEnabled()
                && credential.getRetireAt() != null
                && credential.getRetireAt().isAfter(LocalDateTime.now())
                && principal.getRevokedAt() == null);
        response.setRetireAt(credential.getRetireAt());
        response.setPolicyVersion(principal.getPolicyVersion());
        response.setRequestsPerMinute(principal.getRequestsPerMinute());
        response.setRole(principal.getRole().name());
        response.setCapabilities(ApiCapabilitySupport.effectiveForRole(
                principal.getRole(),
                ApiCapabilitySupport.normalizePersisted(principal.getCapabilities())));
        List<Long> allowedIds = ApiKeyCollectionAccess.parseAllowedIds(
                principal.getAllowedCollectionIds());
        response.setAllowedCollectionIds(allowedIds.isEmpty() ? null : allowedIds);
        response.setAllowedCollectionKeys(collectionKeys(allowedIds));
        return response;
    }

    private ApiPrincipalResponse toPrincipalResponse(RagApiPrincipal principal) {
        LocalDateTime now = LocalDateTime.now();
        RagApiKey current = apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                        principal.getPrincipalId())
                .orElse(null);
        RagApiKey retiring = apiKeyRepository
                .findLiveRetiring(principal.getPrincipalId(), now)
                .orElse(null);
        ApiKeyRotationOperation pending = rotationOperationRepository == null
                ? null
                : rotationOperationRepository
                        .findByPrincipalIdAndStatus(
                                principal.getPrincipalId(),
                                ApiKeyRotationStatus.PENDING)
                        .filter(operation -> operation.getExpiresAt().isAfter(now))
                        .orElse(null);
        ApiPrincipalResponse response = new ApiPrincipalResponse();
        response.setPrincipalId(principal.getPrincipalId());
        response.setName(principal.getName());
        response.setRole(principal.getRole().name());
        response.setExpiresAt(principal.getExpiresAt());
        response.setRequestsPerMinute(principal.getRequestsPerMinute());
        response.setPolicyVersion(principal.getPolicyVersion());
        response.setCapabilities(ApiCapabilitySupport.effectiveForRole(
                principal.getRole(),
                ApiCapabilitySupport.normalizePersisted(principal.getCapabilities())));
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
        response.setRotationPending(pending != null && retiring != null);
        if (pending != null && retiring != null) {
            response.setPendingRotationId(pending.getRotationId());
            response.setRetiringCredentialId(retiring.getKeyId());
            response.setRetiringCredentialVersion(retiring.getCredentialVersion());
            response.setRotationExpiresAt(pending.getExpiresAt());
        }
        return response;
    }

    private List<String> collectionKeys(List<Long> allowedIds) {
        if (allowedIds == null || allowedIds.isEmpty()
                || collectionIdentityResolver == null) {
            return null;
        }
        return collectionIdentityResolver.mapKeys(allowedIds).values().stream().toList();
    }

    private void requireRotationLedger() {
        if (rotationOperationRepository == null) {
            throw new RagException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "API credential rotation ledger is unavailable");
        }
    }

    private ApiKeyRotationOperation findRotation(UUID rotationId) {
        requireRotationLedger();
        return rotationOperationRepository.findById(rotationId)
                .orElseThrow(() -> new RagException(
                        ErrorCode.NOT_FOUND,
                        "API credential rotation was not found"));
    }

    private RagApiKey requiredCredential(
            ApiKeyRotationOperation operation,
            String credentialId) {
        RagApiKey credential = apiKeyRepository.findByKeyId(credentialId)
                .orElseThrow(() -> new RagException(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        "The rotation references a missing credential"));
        if (!Objects.equals(
                operation.getPrincipalId(), credential.getPrincipalId())) {
            throw new RagException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "The rotation references a credential owned by another principal");
        }
        return credential;
    }

    private RotationCredentials requiredRotationCredentials(
            ApiKeyRotationOperation operation) {
        RagApiKey source = requiredCredential(
                operation, operation.getSourceCredentialId());
        RagApiKey target = requiredCredential(
                operation, operation.getTargetCredentialId());
        if (source.getCredentialVersion() == null
                || target.getCredentialVersion() == null
                || target.getCredentialVersion()
                        <= source.getCredentialVersion()) {
            throw new RagException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "The rotation credential versions are inconsistent");
        }
        return new RotationCredentials(source, target);
    }

    private String rotationFingerprint(
            String sourceCredentialId,
            Integer requestedOverlapSeconds) {
        String overlap = requestedOverlapSeconds == null
                ? "DEFAULT"
                : Integer.toString(requestedOverlapSeconds);
        return sha256(sourceCredentialId + "\n" + overlap);
    }

    private void authorizeRotation(
            String principalId,
            String prepareCredentialId,
            ApiAccessPolicy caller,
            boolean environmentRoot,
            boolean prepare) {
        if (environmentRoot) {
            return;
        }
        if (caller == null || caller.getPrincipalId() == null) {
            throw new SecurityException(
                    "A database-backed ADMIN or owning credential is required");
        }
        if (caller.getRole() == ApiKeyRole.ADMIN) {
            return;
        }
        if (!principalId.equals(caller.getPrincipalId())) {
            throw new SecurityException(
                    "NORMAL credentials can only manage their own rotation");
        }
        if (prepare && !Objects.equals(
                prepareCredentialId, caller.getCredentialId())) {
            throw new SecurityException(
                    "NORMAL credentials must prepare rotation from their current credential");
        }
    }

    private void cleanupExpiredRotationForPrincipal(
            String principalId,
            LocalDateTime now) {
        if (rotationOperationRepository != null) {
            rotationOperationRepository
                    .findByPrincipalIdAndStatus(
                            principalId, ApiKeyRotationStatus.PENDING)
                    .ifPresent(operation ->
                            expirePendingIfNecessary(operation, now));
        }
        Optional<RagApiKey> retiring =
                apiKeyRepository
                        .findByPrincipalIdAndEnabledTrueAndRetireAtIsNotNull(
                                principalId);
        if (retiring != null) {
            retiring.filter(candidate -> !candidate.getRetireAt().isAfter(now))
                    .ifPresent(candidate ->
                            apiKeyRepository.disableByKeyId(
                                    candidate.getKeyId(), now));
        }
    }

    private void expirePendingIfNecessary(
            ApiKeyRotationOperation operation,
            LocalDateTime now) {
        if (operation.getStatus() != ApiKeyRotationStatus.PENDING
                || operation.getExpiresAt().isAfter(now)) {
            return;
        }
        RagApiKey source = requiredRotationCredentials(operation).source();
        if (source.isEnabled()) {
            apiKeyRepository.disableByKeyId(source.getKeyId(), now);
        }
        operation.setStatus(ApiKeyRotationStatus.EXPIRED);
        operation.setUpdatedAt(now);
        operation.setTerminalAt(now);
        rotationOperationRepository.saveAndFlush(operation);
    }

    private void clampPendingRotationDeadline(
            String principalId,
            LocalDateTime principalExpiresAt,
            LocalDateTime now) {
        if (rotationOperationRepository == null || principalExpiresAt == null) {
            return;
        }
        ApiKeyRotationOperation operation = rotationOperationRepository
                .findByPrincipalIdAndStatus(
                        principalId, ApiKeyRotationStatus.PENDING)
                .orElse(null);
        if (operation == null
                || !principalExpiresAt.isBefore(operation.getExpiresAt())) {
            return;
        }
        operation.setExpiresAt(principalExpiresAt);
        operation.setUpdatedAt(now);
        RagApiKey source = requiredRotationCredentials(operation).source();
        source.setRetireAt(principalExpiresAt);
        apiKeyRepository.saveAndFlush(source);
        if (!principalExpiresAt.isAfter(now)) {
            expirePendingIfNecessary(operation, now);
        } else {
            rotationOperationRepository.saveAndFlush(operation);
        }
    }

    private ApiKeyRotationResponse rotationResponse(
            ApiKeyRotationOperation operation,
            String rawKey,
            boolean replay) {
        requiredRotationCredentials(operation);
        RagApiPrincipal principal = principalRepository
                .findByPrincipalId(operation.getPrincipalId())
                .orElseThrow(() -> new RagException(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        "The rotation references a missing principal"));
        LocalDateTime now = LocalDateTime.now();
        RagApiKey current = apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                        operation.getPrincipalId())
                .orElse(null);
        RagApiKey retiring = apiKeyRepository
                .findLiveRetiring(operation.getPrincipalId(), now)
                .orElse(null);

        ApiKeyRotationResponse response = new ApiKeyRotationResponse();
        response.setRotationId(operation.getRotationId());
        response.setStatus(operation.getStatus().name());
        response.setPrincipalId(operation.getPrincipalId());
        if (current != null) {
            response.setKeyId(current.getKeyId());
            response.setCredentialVersion(current.getCredentialVersion());
        }
        response.setRawKey(rawKey);
        response.setSecretAvailable(rawKey != null);
        response.setIdempotentReplay(replay);
        response.setCurrentCredentialActive(current != null
                && principal.getRevokedAt() == null
                && !isExpired(principal.getExpiresAt()));
        response.setRotationPending(
                operation.getStatus() == ApiKeyRotationStatus.PENDING
                        && operation.getExpiresAt().isAfter(now)
                        && retiring != null);
        if (retiring != null) {
            response.setRetiringCredentialId(retiring.getKeyId());
            response.setRetiringCredentialVersion(
                    retiring.getCredentialVersion());
        }
        response.setRotationExpiresAt(operation.getExpiresAt());
        return response;
    }

    private record RotationCredentials(
            RagApiKey source,
            RagApiKey target) {
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

    private RagException rotationPending() {
        return new RagException(
                ErrorCode.CREDENTIAL_ROTATION_PENDING,
                "A credential rotation is already pending for this principal");
    }

    private RagException rotationNotPending() {
        return new RagException(
                ErrorCode.CREDENTIAL_ROTATION_NOT_PENDING,
                "The credential rotation is not pending");
    }

    private RagException rotationConflict() {
        return new RagException(
                ErrorCode.CONCURRENT_MODIFICATION,
                "Credential rotation state changed concurrently");
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
