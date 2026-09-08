package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.util.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.Map;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭证轮换台账生命周期：prepare 的幂等重放与指纹冲突、pending 互斥、
 * 当前凭证一致性校验、overlap 边界与主体到期钳制、complete/cancel
 * 的 CAS 与终态语义。
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyRotationLifecycleTest {

    private static final String PRINCIPAL_ID = "rag_p_owner";
    private static final String CURRENT_KEY = "rag_k_current";

    @Mock RagApiKeyRepository apiKeyRepository;
    @Mock RagApiPrincipalRepository principalRepository;
    @Mock ApiKeyRotationOperationRepository rotationOperationRepository;
    @Mock JdbcTemplate jdbcTemplate;

    private ApiKeyManagementService service;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        service = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                null,
                jdbcTemplate,
                mock(ApiKeyProvisioningOperationRepository.class),
                rotationOperationRepository,
                properties,
                transactionManager,
                null);
    }

    private RagApiKey credential(String keyId, int version, boolean enabled) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId(PRINCIPAL_ID);
        key.setCredentialVersion(version);
        key.setEnabled(enabled);
        return key;
    }

    private RagApiPrincipal principal() {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(PRINCIPAL_ID);
        principal.setNextCredentialVersion(2);
        principal.setExpiresAt(null);
        return principal;
    }

    private ApiKeyRotationOperation operation(
            ApiKeyRotationStatus status,
            String sourceKeyId,
            String targetKeyId,
            LocalDateTime expiresAt) {
        ApiKeyRotationOperation operation = new ApiKeyRotationOperation();
        operation.setRotationId(UUID.randomUUID());
        operation.setPrincipalId(PRINCIPAL_ID);
        operation.setIdempotencyKeyHash("hash");
        operation.setRequestFingerprintSha256("fingerprint");
        operation.setSourceCredentialId(sourceKeyId);
        operation.setTargetCredentialId(targetKeyId);
        operation.setOverlapSeconds(300);
        operation.setExpiresAt(expiresAt);
        operation.setStatus(status);
        operation.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        operation.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        return operation;
    }

    private String fingerprint(Integer overlapSeconds) {
        String overlap = overlapSeconds == null
                ? "DEFAULT" : Integer.toString(overlapSeconds);
        return DigestUtils.sha256(CURRENT_KEY + "\n" + overlap);
    }

    // ─── prepareRotation ─────────────────────────────────────────

    @Test
    void prepareRejectsBlankIdempotencyKey() {
        assertThrows(IllegalArgumentException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, null, " ", null, true));
    }

    @Test
    void prepareReturnsNullForUnknownCurrentKey() {
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.empty());

        assertNull(service.prepareRotation(
                CURRENT_KEY, null, "hash", null, true));
    }

    @Test
    void prepareReplaysMatchingFingerprintWithoutNewSecret() {
        ApiKeyRotationOperation replay = operation(
                ApiKeyRotationStatus.PENDING,
                "src", "tgt", LocalDateTime.now().plusMinutes(5));
        replay.setRequestFingerprintSha256(fingerprint(null));
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1, true)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        PRINCIPAL_ID, "hash"))
                .thenReturn(Optional.of(replay));
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal()));
        // rotationResponse 需要解析台账引用的源/目标凭证。
        when(apiKeyRepository.findByKeyId("src"))
                .thenReturn(Optional.of(credential("src", 1, true)));
        when(apiKeyRepository.findByKeyId("tgt"))
                .thenReturn(Optional.of(credential("tgt", 2, true)));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                PRINCIPAL_ID))
                .thenReturn(Optional.of(credential("tgt", 2, true)));

        ApiKeyManagementService.RotationResult result =
                service.prepareRotation(CURRENT_KEY, null, "hash", null, true);

        assertTrue(result.replay());
        assertFalse(result.response().getSecretAvailable());
        verify(rotationOperationRepository, org.mockito.Mockito.never())
                .saveAndFlush(any());
    }

    @Test
    void prepareRejectsReusedKeyWithDifferentFingerprint() {
        ApiKeyRotationOperation replay = operation(
                ApiKeyRotationStatus.PENDING,
                "src", "tgt", LocalDateTime.now().plusMinutes(5));
        replay.setRequestFingerprintSha256("different");
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1, true)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        PRINCIPAL_ID, "hash"))
                .thenReturn(Optional.of(replay));

        RagException error = assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, null, "hash", null, true));

        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                error.getErrorCodeEnum());
    }

    @Test
    void prepareRejectsWhenAnotherRotationIsPending() {
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1, true)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        PRINCIPAL_ID, "hash"))
                .thenReturn(Optional.empty());
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                PRINCIPAL_ID, ApiKeyRotationStatus.PENDING))
                .thenReturn(Optional.of(operation(
                        ApiKeyRotationStatus.PENDING,
                        "src", "tgt", LocalDateTime.now().plusMinutes(5))));
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal()));

        RagException error = assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 300, "hash", null, true));

        assertEquals(ErrorCode.CREDENTIAL_ROTATION_PENDING,
                error.getErrorCodeEnum());
    }

    @Test
    void prepareRejectsWhenCurrentCredentialIsNotTheCallerKey() {
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1, true)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        PRINCIPAL_ID, "hash"))
                .thenReturn(Optional.empty());
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                PRINCIPAL_ID, ApiKeyRotationStatus.PENDING))
                .thenReturn(Optional.empty());
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal()));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                PRINCIPAL_ID))
                .thenReturn(Optional.of(credential("rag_k_other", 1, true)));

        RagException error = assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 300, "hash", null, true));

        assertEquals(ErrorCode.CREDENTIAL_NOT_CURRENT,
                error.getErrorCodeEnum());
    }

    @Test
    void prepareRejectsOverlapSecondsOutsideTheAllowedRange() {
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1, true)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        PRINCIPAL_ID, "hash"))
                .thenReturn(Optional.empty());
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                PRINCIPAL_ID, ApiKeyRotationStatus.PENDING))
                .thenReturn(Optional.empty());
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal()));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                PRINCIPAL_ID))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1, true)));

        assertThrows(IllegalArgumentException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 0, "hash", null, true));
        assertThrows(IllegalArgumentException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY,
                        properties.getApiKeyRotation().maxOverlapSeconds() + 1,
                        "hash", null, true));
    }

    @Test
    void prepareRejectsPrincipalExpiringBeforeTheOverlap() {
        RagApiPrincipal expiring = principal();
        // 主体已过期：ensureActive 与 deadline 钳制共同拒绝新轮换。
        expiring.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1, true)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        PRINCIPAL_ID, "hash"))
                .thenReturn(Optional.empty());
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                PRINCIPAL_ID, ApiKeyRotationStatus.PENDING))
                .thenReturn(Optional.empty());
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(expiring));
        // ensureActive 先于当前凭证解析拒绝，后一桩不会触达。
        lenient().when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                PRINCIPAL_ID))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1, true)));

        RagException error = assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 300, "hash", null, true));

        assertEquals(ErrorCode.PRINCIPAL_NOT_ACTIVE,
                error.getErrorCodeEnum());
    }

    @Test
    void prepareCreatesTargetCredentialAndPendingOperation() {
        RagApiKey current = credential(CURRENT_KEY, 1, true);
        RagApiPrincipal principal = principal();
        // 凭证仓库按 keyId 动态解析，saveAndFlush 同步登记生成的新凭证。
        java.util.Map<String, RagApiKey> keyStore =
                new java.util.HashMap<>(Map.of(CURRENT_KEY, current));
        when(apiKeyRepository.findByKeyId(anyString()))
                .thenAnswer(invocation ->
                        java.util.Optional.ofNullable(keyStore.get(
                                invocation.getArgument(0, String.class))));
        when(apiKeyRepository.saveAndFlush(any(RagApiKey.class)))
                .thenAnswer(invocation -> {
                    RagApiKey saved = invocation.getArgument(0);
                    keyStore.put(saved.getKeyId(), saved);
                    return saved;
                });
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        PRINCIPAL_ID, "hash"))
                .thenReturn(Optional.empty());
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                PRINCIPAL_ID, ApiKeyRotationStatus.PENDING))
                .thenReturn(Optional.empty());
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                PRINCIPAL_ID))
                .thenReturn(Optional.of(current));

        ApiKeyManagementService.RotationResult result =
                service.prepareRotation(CURRENT_KEY, 300, "hash", null, true);

        assertFalse(result.replay());
        assertNotNull(result.response().getRawKey());

        ArgumentCaptor<RagApiKey> credentialCaptor =
                ArgumentCaptor.forClass(RagApiKey.class);
        verify(apiKeyRepository, org.mockito.Mockito.times(2))
                .saveAndFlush(credentialCaptor.capture());
        // 第一次保存的是设置了退役期限的当前凭证，第二次是新目标凭证。
        assertEquals(CURRENT_KEY,
                credentialCaptor.getAllValues().get(0).getKeyId());
        assertNotNull(credentialCaptor.getAllValues().get(0).getRetireAt());
        RagApiKey target =
                credentialCaptor.getAllValues().get(1);
        assertEquals(Integer.valueOf(2), target.getCredentialVersion());
        // 主体版本号已前进，预留给目标凭证。
        assertEquals(Integer.valueOf(3), principal.getNextCredentialVersion());

        ArgumentCaptor<ApiKeyRotationOperation> operationCaptor =
                ArgumentCaptor.forClass(ApiKeyRotationOperation.class);
        verify(rotationOperationRepository)
                .saveAndFlush(operationCaptor.capture());
        assertEquals(ApiKeyRotationStatus.PENDING,
                operationCaptor.getValue().getStatus());
        assertEquals(300, operationCaptor.getValue().getOverlapSeconds());
        assertEquals(CURRENT_KEY,
                operationCaptor.getValue().getSourceCredentialId());
    }

    // ─── completeRotation / cancelRotation ───────────────────────

    private void stubRotationLookup(ApiKeyRotationOperation operation) {
        lenient().when(rotationOperationRepository.findById(operation.getRotationId()))
                .thenReturn(Optional.of(operation));
        lenient().when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        lenient().when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal()));
        lenient().when(apiKeyRepository.findByKeyId("src"))
                .thenReturn(Optional.of(credential("src", 1, true)));
        lenient().when(apiKeyRepository.findByKeyId("tgt"))
                .thenReturn(Optional.of(credential("tgt", 2, true)));
        lenient().when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                PRINCIPAL_ID))
                .thenReturn(Optional.of(credential("tgt", 2, true)));
    }

    @Test
    void completeRotationDisablesSourceAndMarksCompleted() {
        ApiKeyRotationOperation pending = operation(
                ApiKeyRotationStatus.PENDING, "src", "tgt",
                LocalDateTime.now().plusMinutes(5));
        stubRotationLookup(pending);
        when(apiKeyRepository.disableByKeyId(eq("src"), any()))
                .thenReturn(1);

        var response = service.completeRotation(
                pending.getRotationId(), null, true);

        assertEquals("COMPLETED", response.getStatus());
        verify(apiKeyRepository).disableByKeyId(eq("src"), any());
        assertEquals(ApiKeyRotationStatus.COMPLETED, pending.getStatus());
        assertNotNull(pending.getTerminalAt());
    }

    @Test
    void completeRotationIsIdempotentWhenAlreadyCompleted() {
        ApiKeyRotationOperation completed = operation(
                ApiKeyRotationStatus.COMPLETED, "src", "tgt",
                LocalDateTime.now().plusMinutes(5));
        stubRotationLookup(completed);

        var response = service.completeRotation(
                completed.getRotationId(), null, true);

        assertEquals("COMPLETED", response.getStatus());
        verify(apiKeyRepository, org.mockito.Mockito.never())
                .disableByKeyId(any(), any());
    }

    @Test
    void completeRotationRejectsExpiredOverlap() {
        ApiKeyRotationOperation expired = operation(
                ApiKeyRotationStatus.PENDING, "src", "tgt",
                LocalDateTime.now().minusMinutes(1));
        stubRotationLookup(expired);

        RagException error = assertThrows(RagException.class,
                () -> service.completeRotation(
                        expired.getRotationId(), null, true));

        assertEquals(ErrorCode.CREDENTIAL_ROTATION_EXPIRED,
                error.getErrorCodeEnum());
        assertEquals(ApiKeyRotationStatus.EXPIRED, expired.getStatus());
    }

    @Test
    void cancelRotationDisablesTargetAndRestoresSource() {
        RagApiKey source = credential("src", 1, true);
        RagApiKey target = credential("tgt", 2, true);
        ApiKeyRotationOperation pending = operation(
                ApiKeyRotationStatus.PENDING, "src", "tgt",
                LocalDateTime.now().plusMinutes(5));
        when(rotationOperationRepository.findById(pending.getRotationId()))
                .thenReturn(Optional.of(pending));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal()));
        when(apiKeyRepository.findByKeyId("src"))
                .thenReturn(Optional.of(source));
        when(apiKeyRepository.findByKeyId("tgt"))
                .thenReturn(Optional.of(target));
        when(apiKeyRepository.disableByKeyId(eq("tgt"), any()))
                .thenReturn(1);

        var response = service.cancelRotation(
                pending.getRotationId(), null, true);

        assertEquals("CANCELED", response.getStatus());
        verify(apiKeyRepository).disableByKeyId(eq("tgt"), any());
        assertNull(source.getRetireAt());
        assertEquals(ApiKeyRotationStatus.CANCELED, pending.getStatus());
    }
}
