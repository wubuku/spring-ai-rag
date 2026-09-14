package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * rotation 全生命周期（Batch 392）：prepare 的守卫/成功/幂等重放
 * 与冲突、complete 的 EXPIRED/非 PENDING/成功、cancel 的幂等与成
 * 功取消、getRotation 查询、expirePendingIfNecessary 自动过期、
 * cleanupCredentialRotations 的空集/异常容错。
 */
class ApiKeyManagementServiceRotationLifecycleTest {

    private static final String PRINCIPAL_ID = "principal-1";
    private static final String CURRENT_KEY = "rag_sk_current";
    private static final String SOURCE_KEY = "rag_sk_source";
    private static final String TARGET_KEY = "rag_sk_target";

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyRotationOperationRepository rotationOperationRepository;
    private PlatformTransactionManager transactionManager;
    private ApiKeyManagementService service;
    private ApiKeyRotationOperation operation;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);
        rotationOperationRepository =
                mock(ApiKeyRotationOperationRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));

        operation = mock(ApiKeyRotationOperation.class);
        when(operation.getPrincipalId()).thenReturn(PRINCIPAL_ID);
        when(operation.getRotationId()).thenReturn(UUID.randomUUID());
        when(operation.getExpiresAt()).thenReturn(
                LocalDateTime.now().plusMinutes(10));
        java.util.concurrent.atomic.AtomicReference<ApiKeyRotationStatus> status =
                new java.util.concurrent.atomic.AtomicReference<>(
                        ApiKeyRotationStatus.PENDING);
        when(operation.getStatus()).thenAnswer(inv -> status.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            status.set(invocation.getArgument(0));
            return null;
        }).when(operation).setStatus(any());
        when(operation.getSourceCredentialId()).thenReturn(SOURCE_KEY);
        when(operation.getTargetCredentialId()).thenReturn(TARGET_KEY);
        when(operation.getRequestFingerprintSha256())
                .thenReturn("fp-1");
        when(rotationOperationRepository.findById(any(UUID.class)))
                .thenReturn(Optional.of(operation));
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                eq(PRINCIPAL_ID), eq(ApiKeyRotationStatus.PENDING)))
                .thenReturn(Optional.empty());
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        eq(PRINCIPAL_ID), anyString()))
                .thenReturn(Optional.empty());

        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(activePrincipal()));
        when(apiKeyRepository.findByKeyId(SOURCE_KEY))
                .thenReturn(Optional.of(credential(SOURCE_KEY, 1, true)));
        when(apiKeyRepository.findByKeyId(TARGET_KEY))
                .thenReturn(Optional.of(credential(TARGET_KEY, 2, false)));
        lenient().when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                PRINCIPAL_ID))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 2, true)));
        lenient().when(apiKeyRepository.findLiveRetiring(
                eq(PRINCIPAL_ID), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        lenient().when(apiKeyRepository.disableByKeyId(anyString(),
                any(LocalDateTime.class))).thenReturn(1);
        lenient().when(apiKeyRepository.saveAndFlush(any(RagApiKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(principalRepository.saveAndFlush(
                any(RagApiPrincipal.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(rotationOperationRepository.saveAndFlush(
                any(ApiKeyRotationOperation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                mock(ApiKeyProvisioningOperationRepository.class),
                rotationOperationRepository,
                new RagProperties(),
                transactionManager,
                mock(ApiPrincipalLifecycleEventPublisher.class));
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
        principal.setNextCredentialVersion(5);
        return principal;
    }

    private RagApiKey credential(String keyId, int version, boolean enabled) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId(PRINCIPAL_ID);
        key.setCredentialVersion(version);
        key.setEnabled(enabled);
        return key;
    }

    // ── prepareRotation ─────────────────────────────────────────────

    @Test
    void prepareRejectsMissingIdempotencyKey() {
        assertThrows(IllegalArgumentException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, null, " ", null, true));
    }

    @Test
    void prepareReturnsNullForUnknownCurrentKey() {
        when(apiKeyRepository.findByKeyId("rag_sk_ghost"))
                .thenReturn(Optional.empty());

        assertNull(service.prepareRotation(
                "rag_sk_ghost", null, "idem-1", null, true));
    }

    @Test
    void prepareThrowsWhenRequestedKeyIsNotCurrent() {
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1, true)));
        when(apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(credential("rag_sk_other", 2, true)));

        RagException error = assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, null, "idem-1", null, true));
        assertEquals(ErrorCode.CREDENTIAL_NOT_CURRENT,
                error.getErrorCodeEnum());
    }

    @Test
    void prepareRejectsOverlapSecondsOutOfRange() {
        RagApiKey current = credential(CURRENT_KEY, 1, true);
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(current));
        when(apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(current));

        assertThrows(IllegalArgumentException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 0, "idem-1", null, true));
        assertThrows(IllegalArgumentException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 100_000, "idem-1", null, true));
    }

    @Test
    void prepareRejectsPrincipalExpiringBeforeOverlapDeadline() {
        RagApiPrincipal principal = activePrincipal();
        principal.setExpiresAt(LocalDateTime.now().minusSeconds(30));
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal));
        RagApiKey current = credential(CURRENT_KEY, 1, true);
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(current));
        when(apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(current));

        RagException error = assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 3_600, "idem-1", null, true));
        assertEquals(ErrorCode.PRINCIPAL_NOT_ACTIVE,
                error.getErrorCodeEnum());
    }

    @Test
    void prepareSuccessCreatesPendingOperationWithRawKey() {
        RagApiKey current = credential(CURRENT_KEY, 1, true);
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(current));
        when(apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(current));
        // prepare 新建 target 凭据使用随机 keyId——catch-all 兜底解析。
        when(apiKeyRepository.findByKeyId(anyString()))
                .thenAnswer(invocation -> Optional.of(credential(
                        invocation.getArgument(0),
                        CURRENT_KEY.equals(invocation.getArgument(0)) ? 1 : 2,
                        true)));

        var result = service.prepareRotation(
                CURRENT_KEY, null, "idem-1", null, true);

        assertNotNull(result);
        assertFalse(result.replay());
        assertEquals("PENDING", result.response().getStatus());
        assertNotNull(result.response().getRawKey());
        assertTrue(result.response().getSecretAvailable());
        verify(rotationOperationRepository).saveAndFlush(
                any(ApiKeyRotationOperation.class));
    }

    @Test
    void prepareReplaysSameIdempotencyKeyWithMatchingFingerprint() {
        RagApiKey current = credential(CURRENT_KEY, 1, true);
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(current));
        when(apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(current));
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        eq(PRINCIPAL_ID), eq("idem-1")))
                .thenReturn(Optional.of(operation));
        when(operation.getRequestFingerprintSha256())
                .thenReturn(com.springairag.core.util.DigestUtils.sha256(
                        CURRENT_KEY + "\nDEFAULT"));

        var result = service.prepareRotation(
                CURRENT_KEY, null, "idem-1", null, true);

        assertTrue(result.replay());
        assertNotNull(result.response());
    }

    @Test
    void prepareRejectsReusedIdempotencyKeyWithDifferentFingerprint() {
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1, true)));
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        eq(PRINCIPAL_ID), eq("idem-1")))
                .thenReturn(Optional.of(operation));
        when(operation.getRequestFingerprintSha256()).thenReturn("fp-other");

        RagException error = assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, null, "idem-1", null, true));
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                error.getErrorCodeEnum());
    }

    // ── getRotation / completeRotation / cancelRotation ─────────────

    @Test
    void getRotationReturnsPendingResponseForRootCaller() {
        var response = service.getRotation(UUID.randomUUID(), null, true);

        assertEquals("PENDING", response.getStatus());
    }

    @Test
    void completeRotationRejectsExpiredOverlapAndExpiresPending() {
        // PENDING 但 expiresAt 已过 → 自动过期（source 失效 + EXPIRED
        // 保存）后拒绝完成。
        when(operation.getExpiresAt())
                .thenReturn(LocalDateTime.now().minusMinutes(1));

        RagException error = assertThrows(RagException.class,
                () -> service.completeRotation(UUID.randomUUID(), null, true));
        assertEquals(ErrorCode.CREDENTIAL_ROTATION_EXPIRED,
                error.getErrorCodeEnum());
        verify(operation).setStatus(ApiKeyRotationStatus.EXPIRED);
        verify(rotationOperationRepository).saveAndFlush(operation);
        verify(apiKeyRepository).disableByKeyId(
                eq(SOURCE_KEY), any(LocalDateTime.class));
    }

    @Test
    void completeRotationRejectsCanceledOperation() {
        when(operation.getStatus())
                .thenReturn(ApiKeyRotationStatus.CANCELED);

        RagException error = assertThrows(RagException.class,
                () -> service.completeRotation(UUID.randomUUID(), null, true));
        assertEquals(ErrorCode.CREDENTIAL_ROTATION_NOT_PENDING,
                error.getErrorCodeEnum());
    }

    @Test
    void completeRotationSuccessDisablesSourceAndCompletesOperation() {
        var response = service.completeRotation(UUID.randomUUID(), null, true);

        assertEquals("COMPLETED", response.getStatus());
        verify(operation).setStatus(ApiKeyRotationStatus.COMPLETED);
        verify(rotationOperationRepository).saveAndFlush(operation);
        verify(apiKeyRepository).disableByKeyId(
                eq(SOURCE_KEY), any(LocalDateTime.class));
    }

    @Test
    void cancelRotationIsIdempotentForCanceledOperation() {
        when(operation.getStatus())
                .thenReturn(ApiKeyRotationStatus.CANCELED);

        var response = service.cancelRotation(UUID.randomUUID(), null, true);

        assertEquals("CANCELED", response.getStatus());
        verify(rotationOperationRepository, never()).saveAndFlush(operation);
    }

    @Test
    void cancelRotationRejectsExpiredOverlap() {
        when(operation.getExpiresAt())
                .thenReturn(LocalDateTime.now().minusMinutes(1));

        RagException error = assertThrows(RagException.class,
                () -> service.cancelRotation(UUID.randomUUID(), null, true));
        assertEquals(ErrorCode.CREDENTIAL_ROTATION_EXPIRED,
                error.getErrorCodeEnum());
    }

    @Test
    void cancelRotationSuccessRestoresSourceAndMarksCanceled() {
        // target 凭据仍启用：取消时被禁用（disableByKeyId）。
        RagApiKey target = credential(TARGET_KEY, 2, true);
        when(apiKeyRepository.findByKeyId(TARGET_KEY))
                .thenReturn(Optional.of(target));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                PRINCIPAL_ID)).thenReturn(Optional.of(target));

        var response = service.cancelRotation(UUID.randomUUID(), null, true);

        assertEquals("CANCELED", response.getStatus());
        verify(operation).setStatus(ApiKeyRotationStatus.CANCELED);
        verify(rotationOperationRepository).saveAndFlush(operation);
        verify(apiKeyRepository).disableByKeyId(
                eq(TARGET_KEY), any(LocalDateTime.class));
    }

    // ── cleanup ─────────────────────────────────────────────────────

    @Test
    void cleanupRotationsRunsTerminalCleanupAfterExpirySweep() {
        when(rotationOperationRepository.findExpiredRotationIds(
                eq(ApiKeyRotationStatus.PENDING), any(LocalDateTime.class),
                any())).thenReturn(List.of());

        service.cleanupCredentialRotations();

        verify(rotationOperationRepository).deleteTerminalBefore(
                any(LocalDateTime.class), anyInt());
    }

    @Test
    void cleanupRotationsSwallowsDataAccessFailures() {
        when(rotationOperationRepository.findExpiredRotationIds(
                eq(ApiKeyRotationStatus.PENDING), any(LocalDateTime.class),
                any())).thenThrow(
                new DataAccessResourceFailureException("db down"));

        service.cleanupCredentialRotations();

        verify(rotationOperationRepository, never()).deleteTerminalBefore(
                any(LocalDateTime.class), anyInt());
    }
}
