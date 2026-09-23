package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.entity.ApiKeyProvisioningOperation;
import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.service.ApiKeyManagementService.ProvisioningResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * API Key 供给与吊销长尾（Batch 592，JaCoCo 驱动）：generateKey 委派、
 * 无事务模板下的幂等重放、账本清理守卫与删除、凭证轮换清理的过期
 * 处理与空操作跳过、吊销对缺失凭证/管理锁/主体行/当前凭证的降级。
 */
class ApiKeyManagementProvisionRevokeTailTest {

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyProvisioningOperationRepository provisioningRepository;
    private ApiKeyRotationOperationRepository rotationRepository;
    private ApiPrincipalLifecycleEventPublisher lifecyclePublisher;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);
        provisioningRepository = mock(ApiKeyProvisioningOperationRepository.class);
        rotationRepository = mock(ApiKeyRotationOperationRepository.class);
        lifecyclePublisher = mock(ApiPrincipalLifecycleEventPublisher.class);
    }

    private ApiKeyManagementService service(boolean withTransactions) {
        RagProperties properties = new RagProperties();
        properties.getApiKeyProvisioning().setEnabled(true);
        return new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                provisioningRepository,
                rotationRepository,
                properties,
                withTransactions ? mock(PlatformTransactionManager.class) : null,
                lifecyclePublisher);
    }

    private ApiKeyCreateRequest request() {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setName("Prod Key");
        return request;
    }

    private RagApiPrincipal principal() {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId("principal-1");
        principal.setName("Prod Key");
        principal.setRole(ApiKeyRole.NORMAL);
        principal.setAllowedCollectionIds("7,8");
        principal.setPolicyVersion(1L);
        principal.setRequestsPerMinute(120);
        return principal;
    }

    // ── 供给路径 ─────────────────────────────────────────────────────

    @Test
    void generateKeyCreatesPrincipalAndCredential() {
        when(principalRepository.save(any(RagApiPrincipal.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(apiKeyRepository.save(any(RagApiKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApiKeyCreatedResponse response = service(true).generateKey(request());

        assertNotNull(response.getKeyId());
        verify(principalRepository).save(any(RagApiPrincipal.class));
        verify(apiKeyRepository).save(any(RagApiKey.class));
        verify(lifecyclePublisher).publishAfterCommit(response.getPrincipalId());
    }

    @Test
    void idempotentReplayWorksWithoutTransactionTemplate() {
        ApiKeyManagementService bare = service(false);
        ApiKeyCreateRequest request = request();
        String fingerprint = ApiKeyProvisioningFingerprint.sha256(
                request, ApiKeyRole.NORMAL.name());
        ApiKeyProvisioningOperation operation =
                mock(ApiKeyProvisioningOperation.class);
        when(operation.getRequestFingerprintSha256()).thenReturn(fingerprint);
        when(operation.getPrincipalId()).thenReturn("principal-1");
        when(provisioningRepository.findByOwnerIdAndIdempotencyKeyHash(
                "owner-1", "hash-1"))
                .thenReturn(Optional.of(operation));
        RagApiPrincipal principal = principal();
        when(principalRepository.findByPrincipalId("principal-1"))
                .thenReturn(Optional.of(principal));
        RagApiKey current = new RagApiKey();
        current.setKeyId("key-1");
        current.setCredentialVersion(1);
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                "principal-1"))
                .thenReturn(Optional.of(current));

        ProvisioningResult result = bare.generateIdempotentKey(
                request, ApiKeyRole.NORMAL, "owner-1", "hash-1", false);

        assertTrue(result.replay());
        assertEquals(Boolean.TRUE, responseOf(result).getIdempotentReplay());
        assertEquals("key-1", responseOf(result).getKeyId());
    }

    @Test
    void replayWithUnrestrictedPrincipalOmitsCollectionKeys() {
        ApiKeyManagementService bare = service(false);
        ApiKeyCreateRequest request = request();
        String fingerprint = ApiKeyProvisioningFingerprint.sha256(
                request, ApiKeyRole.NORMAL.name());
        ApiKeyProvisioningOperation operation =
                mock(ApiKeyProvisioningOperation.class);
        when(operation.getRequestFingerprintSha256()).thenReturn(fingerprint);
        when(operation.getPrincipalId()).thenReturn("principal-1");
        when(provisioningRepository.findByOwnerIdAndIdempotencyKeyHash(
                "owner-1", "hash-1"))
                .thenReturn(Optional.of(operation));
        RagApiPrincipal principal = principal();
        principal.setAllowedCollectionIds(null);
        principal.setRevokedAt(LocalDateTime.now().minusDays(1));
        when(principalRepository.findByPrincipalId("principal-1"))
                .thenReturn(Optional.of(principal));

        ProvisioningResult result = bare.generateIdempotentKey(
                request, ApiKeyRole.NORMAL, "owner-1", "hash-1", false);

        assertTrue(result.replay());
        assertNull(responseOf(result).getKeyId());
    }

    private ApiKeyCreatedResponse responseOf(ProvisioningResult result) {
        return result.response();
    }

    // ── 账本与轮换清理 ───────────────────────────────────────────────

    @Test
    void cleanupProvisioningLedgerSkipsWhenDisabledOrMissing() {
        // 默认配置未开启幂等账本 → 清理直接跳过。
        ApiKeyManagementService disabled =
                new ApiKeyManagementService(
                        apiKeyRepository, principalRepository, null,
                        mock(JdbcTemplate.class));
        disabled.cleanupProvisioningLedger();
        verifyNoInteractions(provisioningRepository);

        // 账本仓库缺失（未注入）→ 即使开关打开也跳过。
        RagProperties properties = new RagProperties();
        properties.getApiKeyProvisioning().setEnabled(true);
        ApiKeyManagementService bareRepo = new ApiKeyManagementService(
                apiKeyRepository, principalRepository, null,
                mock(JdbcTemplate.class), null, null,
                properties, null, null);
        bareRepo.cleanupProvisioningLedger();
        verifyNoInteractions(apiKeyRepository);
    }

    @Test
    void cleanupProvisioningLedgerDeletesCompletedWhenEnabled() {
        service(true).cleanupProvisioningLedger();

        ArgumentCaptor<LocalDateTime> cutoff =
                ArgumentCaptor.forClass(LocalDateTime.class);
        verify(provisioningRepository).deleteCompletedBefore(
                cutoff.capture(), anyInt());
        assertTrue(cutoff.getValue().isBefore(LocalDateTime.now()));
    }

    @Test
    void cleanupCredentialRotationsSkipsWithoutRotationTransaction() {
        service(false).cleanupCredentialRotations();
        verifyNoInteractions(rotationRepository);
    }

    @Test
    void cleanupCredentialRotationsExpiresPendingAndDeletesTerminal() {
        ApiKeyManagementService managed = service(true);
        UUID rotationId = UUID.randomUUID();
        when(rotationRepository.findExpiredRotationIds(
                eq(ApiKeyRotationStatus.PENDING), any(LocalDateTime.class),
                any(Pageable.class)))
                .thenReturn(List.of(rotationId));
        ApiKeyRotationOperation operation = new ApiKeyRotationOperation();
        operation.setRotationId(rotationId);
        operation.setStatus(ApiKeyRotationStatus.PENDING);
        operation.setPrincipalId("principal-1");
        operation.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        operation.setSourceCredentialId("key-old");
        operation.setTargetCredentialId("key-new");
        when(rotationRepository.findById(rotationId))
                .thenReturn(Optional.of(operation));
        when(rotationRepository.saveAndFlush(any(ApiKeyRotationOperation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(principalRepository.acquireManagementWrite("principal-1"))
                .thenReturn(1);
        RagApiKey source = new RagApiKey();
        source.setKeyId("key-old");
        source.setPrincipalId("principal-1");
        source.setCredentialVersion(1);
        source.setEnabled(Boolean.TRUE);
        RagApiKey target = new RagApiKey();
        target.setKeyId("key-new");
        target.setPrincipalId("principal-1");
        target.setCredentialVersion(2);
        target.setEnabled(Boolean.TRUE);
        when(apiKeyRepository.findByKeyId("key-old"))
                .thenReturn(Optional.of(source));
        when(apiKeyRepository.findByKeyId("key-new"))
                .thenReturn(Optional.of(target));
        when(apiKeyRepository.disableByKeyId(
                eq("key-old"), any(LocalDateTime.class))).thenReturn(1);

        managed.cleanupCredentialRotations();

        assertEquals(ApiKeyRotationStatus.EXPIRED, operation.getStatus());
        verify(rotationRepository).saveAndFlush(operation);
        verify(rotationRepository).deleteTerminalBefore(
                any(LocalDateTime.class), anyInt());
    }

    @Test
    void cleanupCredentialRotationsToleratesVanishedOperation() {
        ApiKeyManagementService managed = service(true);
        UUID rotationId = UUID.randomUUID();
        when(rotationRepository.findExpiredRotationIds(
                eq(ApiKeyRotationStatus.PENDING), any(LocalDateTime.class),
                any(Pageable.class)))
                .thenReturn(List.of(rotationId));
        // 第二次读取时操作已消失 → 静默跳过。
        when(rotationRepository.findById(rotationId))
                .thenReturn(Optional.empty());

        managed.cleanupCredentialRotations();

        verify(rotationRepository).deleteTerminalBefore(
                any(LocalDateTime.class), anyInt());
    }

    // ── 吊销路径 ─────────────────────────────────────────────────────

    @Test
    void revokeReturnsFalseWhenKeyMissingOrLockUnavailable() {
        when(apiKeyRepository.findByKeyId("missing"))
                .thenReturn(Optional.empty());
        assertFalse(service(true).revokeKey("missing"));

        RagApiKey key = new RagApiKey();
        key.setKeyId("key-1");
        key.setPrincipalId("principal-1");
        when(apiKeyRepository.findByKeyId("key-1"))
                .thenReturn(Optional.of(key));
        when(principalRepository.acquireManagementWrite("principal-1"))
                .thenReturn(0);
        assertFalse(service(true).revokeKey("key-1"));
    }

    @Test
    void revokeFailsClosedWhenPrincipalRowMissing() {
        RagApiKey key = new RagApiKey();
        key.setKeyId("key-1");
        key.setPrincipalId("principal-1");
        when(apiKeyRepository.findByKeyId("key-1"))
                .thenReturn(Optional.of(key));
        when(principalRepository.acquireManagementWrite("principal-1"))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId("principal-1"))
                .thenReturn(Optional.empty());

        RagException missingPrincipal = assertThrows(RagException.class,
                () -> service(true).revokeKey("key-1"));
        assertEquals(ErrorCode.NOT_FOUND, missingPrincipal.getErrorCodeEnum());
        assertTrue(missingPrincipal.getMessage().contains("principal"));
    }

    @Test
    void revokeFailsClosedWhenCredentialRowVanished() {
        RagApiKey key = new RagApiKey();
        key.setKeyId("key-1");
        key.setPrincipalId("principal-1");
        // 首次读取（幂等预检）命中，二次权威读取消失。
        when(apiKeyRepository.findByKeyId("key-1"))
                .thenReturn(Optional.of(key))
                .thenReturn(Optional.empty());
        when(principalRepository.acquireManagementWrite("principal-1"))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId("principal-1"))
                .thenReturn(Optional.of(principal()));
        when(rotationRepository.findByPrincipalIdAndStatus(
                "principal-1", ApiKeyRotationStatus.PENDING))
                .thenReturn(Optional.empty());
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNotNull(
                "principal-1"))
                .thenReturn(Optional.empty());

        RagException missingCredential = assertThrows(RagException.class,
                () -> service(true).revokeKey("key-1"));
        assertEquals(ErrorCode.NOT_FOUND, missingCredential.getErrorCodeEnum());
        assertTrue(missingCredential.getMessage().contains("credential"));
    }

    @Test
    void revokeRejectsWhenNoCurrentCredential() {
        RagApiKey key = new RagApiKey();
        key.setKeyId("key-1");
        key.setPrincipalId("principal-1");
        when(apiKeyRepository.findByKeyId("key-1"))
                .thenReturn(Optional.of(key));
        when(principalRepository.acquireManagementWrite("principal-1"))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId("principal-1"))
                .thenReturn(Optional.of(principal()));
        when(rotationRepository.findByPrincipalIdAndStatus(
                "principal-1", ApiKeyRotationStatus.PENDING))
                .thenReturn(Optional.empty());
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNotNull(
                "principal-1"))
                .thenReturn(Optional.empty());
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                "principal-1"))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service(true).revokeKey("key-1"));
        assertEquals(ErrorCode.CREDENTIAL_NOT_CURRENT,
                error.getErrorCodeEnum());
    }
}
