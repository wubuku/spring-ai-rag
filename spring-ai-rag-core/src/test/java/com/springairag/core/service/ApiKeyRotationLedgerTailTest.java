package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiKeyManagementService 轮换台账长尾（Batch 525，JaCoCo 驱动）：
 * prepareRotation 重放指纹失配/命中、重放引用缺失 principal、
 * cleanup 过期 retiring 凭证的禁用与保留两分支、generateIdem
 * potentKey 竞态重试被中断。
 */
class ApiKeyRotationLedgerTailTest {

    private static final String PRINCIPAL_ID = "principal-1";
    private static final String CURRENT_KEY = "rag_sk_current";

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyRotationOperationRepository rotationOperationRepository;
    private ApiKeyProvisioningOperationRepository provisioningOperationRepository;
    private RagProperties ragProperties;
    private ApiKeyManagementService service;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);
        rotationOperationRepository =
                mock(ApiKeyRotationOperationRepository.class);
        provisioningOperationRepository =
                mock(ApiKeyProvisioningOperationRepository.class);
        ragProperties = new RagProperties();
        service = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                provisioningOperationRepository,
                rotationOperationRepository,
                ragProperties,
                mock(PlatformTransactionManager.class),
                mock(ApiPrincipalLifecycleEventPublisher.class));
    }

    private String fingerprint(Integer overlap) {
        String overlapText = overlap == null
                ? "DEFAULT"
                : Integer.toString(overlap);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (CURRENT_KEY + "\n" + overlapText)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RagApiKey credential(String keyId, LocalDateTime retireAt) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId(PRINCIPAL_ID);
        key.setEnabled(true);
        key.setRetireAt(retireAt);
        key.setCredentialVersion(2);
        return key;
    }

    private void stubAcquirableCurrentKey() {
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, null)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
    }

    private ApiKeyRotationOperation operation(String fingerprintValue) {
        ApiKeyRotationOperation operation =
                mock(ApiKeyRotationOperation.class);
        when(operation.getRequestFingerprintSha256())
                .thenReturn(fingerprintValue);
        when(operation.getPrincipalId()).thenReturn(PRINCIPAL_ID);
        when(operation.getRotationId()).thenReturn(UUID.randomUUID());
        when(operation.getExpiresAt())
                .thenReturn(LocalDateTime.now().plusMinutes(10));
        when(operation.getStatus()).thenReturn(ApiKeyRotationStatus.PENDING);
        when(operation.getSourceCredentialId()).thenReturn("cred-src");
        when(operation.getTargetCredentialId()).thenReturn("cred-target");
        RagApiKey source = credential("cred-src", null);
        source.setCredentialVersion(1);
        RagApiKey target = credential("cred-target", null);
        target.setCredentialVersion(2);
        when(apiKeyRepository.findByKeyId("cred-src"))
                .thenReturn(Optional.of(source));
        when(apiKeyRepository.findByKeyId("cred-target"))
                .thenReturn(Optional.of(target));
        return operation;
    }

    private RagApiPrincipal activePrincipal() {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(PRINCIPAL_ID);
        principal.setName("Owner");
        principal.setRole(ApiKeyRole.NORMAL);
        principal.setCapabilities(
                com.springairag.core.security.ApiCapabilitySupport.FULL_SERIALIZED);
        principal.setPolicyVersion(1L);
        principal.setRequestsPerMinute(60);
        principal.setAllowedCollectionIds("");
        principal.setCreatedAt(LocalDateTime.now().minusDays(1));
        principal.setUpdatedAt(LocalDateTime.now());
        return principal;
    }

    @Test
    void rotationReplayWithDriftedFingerprintRejectsReuse() {
        stubAcquirableCurrentKey();
        ApiKeyRotationOperation drifted = operation("drifted");
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        eq(PRINCIPAL_ID), eq("idem-1")))
                .thenReturn(Optional.of(drifted));

        var error = assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, null, "idem-1", null, true));

        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                error.getErrorCodeEnum());
    }

    @Test
    void rotationReplayReturnsPersistedResponse() {
        stubAcquirableCurrentKey();
        ApiKeyRotationOperation replayed = operation(fingerprint(null));
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        eq(PRINCIPAL_ID), eq("idem-1")))
                .thenReturn(Optional.of(replayed));
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(activePrincipal()));

        var result = service.prepareRotation(
                CURRENT_KEY, null, "idem-1", null, true);

        assertTrue(result.replay());
        assertEquals(PRINCIPAL_ID, result.response().getPrincipalId());
        assertEquals(Boolean.TRUE, result.response().getIdempotentReplay());
    }

    @Test
    void rotationReplayWithMissingPrincipalSurfacesUnavailable() {
        stubAcquirableCurrentKey();
        ApiKeyRotationOperation orphan = operation(fingerprint(null));
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        eq(PRINCIPAL_ID), eq("idem-1")))
                .thenReturn(Optional.of(orphan));
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.empty());

        var error = assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, null, "idem-1", null, true));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("missing principal"));
    }

    @Test
    void cleanupDisablesExpiredRetiringCredentialBeforePendingConflict() {
        stubAcquirableCurrentKey();
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        eq(PRINCIPAL_ID), eq("idem-2")))
                .thenReturn(Optional.empty());
        // PENDING 已存在 → cleanup 之后抛 rotationPending。
        ApiKeyRotationOperation pending = operation(fingerprint(null));
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                eq(PRINCIPAL_ID), eq(ApiKeyRotationStatus.PENDING)))
                .thenReturn(Optional.of(pending));
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(activePrincipal()));
        RagApiKey retiring = credential(
                "rag_sk_old", LocalDateTime.now().minusMinutes(5));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNotNull(
                PRINCIPAL_ID))
                .thenReturn(Optional.of(retiring));

        assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 0, "idem-2", null, true));

        verify(apiKeyRepository).disableByKeyId(
                eq("rag_sk_old"), any(LocalDateTime.class));
    }

    @Test
    void cleanupKeepsFutureRetiringCredential() {
        stubAcquirableCurrentKey();
        ApiKeyRotationOperation pendingFuture = operation(fingerprint(0));
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        eq(PRINCIPAL_ID), eq("idem-3")))
                .thenReturn(Optional.empty());
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                eq(PRINCIPAL_ID), eq(ApiKeyRotationStatus.PENDING)))
                .thenReturn(Optional.of(pendingFuture));
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(activePrincipal()));
        RagApiKey retiring = credential(
                "rag_sk_future", LocalDateTime.now().plusMinutes(5));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNotNull(
                PRINCIPAL_ID))
                .thenReturn(Optional.of(retiring));

        assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 0, "idem-3", null, true));

        verify(apiKeyRepository, never()).disableByKeyId(
                anyString(), any(LocalDateTime.class));
    }

    @Test
    void provisioningRetryInterruptionSurfacesUnavailable() {
        ragProperties.getApiKeyProvisioning()
                .setConcurrentRetryAttempts(2);
        when(provisioningOperationRepository
                .findByOwnerIdAndIdempotencyKeyHash(anyString(), anyString()))
                .thenThrow(new DataIntegrityViolationException("uniq"));
        Thread.currentThread().interrupt();
        try {
            ApiKeyCreateRequest request = new ApiKeyCreateRequest();
            request.setName("ops-key");

            var error = assertThrows(RagException.class,
                    () -> service.generateIdempotentKey(
                            request, ApiKeyRole.NORMAL,
                            "owner-1", "hash", false));

            assertEquals(ErrorCode.SERVICE_UNAVAILABLE,
                    error.getErrorCodeEnum());
            assertTrue(error.getMessage()
                    .contains("Provisioning retry was interrupted"));
        } finally {
            // 服务端约定保留中断标志；这里仅清理避免污染后续测试。
            Thread.interrupted();
        }
    }
}
