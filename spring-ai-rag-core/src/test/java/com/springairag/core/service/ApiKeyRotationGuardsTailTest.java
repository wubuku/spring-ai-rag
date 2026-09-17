package com.springairag.core.service;

import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiKeyManagementService 轮换守卫长尾（Batch 484，JaCoCo 驱动）：
 * 管理写竞争的 NOT_FOUND、completeRotation 的已完成重放 / 源密钥
 * 已禁用跳过 / 禁用失败冲突、cancelRotation 的目标禁用冲突与源密
 * 钥已禁用冲突，以及轮换凭证缺失 / 属主不符 / 版本倒挂的
 * SERVICE_UNAVAILABLE 拒绝。
 */
class ApiKeyRotationGuardsTailTest {

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyRotationOperationRepository rotationOperationRepository;
    private ApiKeyManagementService service;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);
        rotationOperationRepository = mock(ApiKeyRotationOperationRepository.class);
        service = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                mock(ApiKeyProvisioningOperationRepository.class),
                rotationOperationRepository,
                new com.springairag.core.config.RagProperties(),
                mock(PlatformTransactionManager.class),
                mock(com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher.class));
        when(principalRepository.findById("principal-1")).thenReturn(
                Optional.of(new RagApiPrincipal()));
        when(principalRepository.findByPrincipalId("principal-1")).thenReturn(
                Optional.of(new RagApiPrincipal()));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                "principal-1")).thenReturn(
                Optional.of(key("live-key", 2, true)));
        when(principalRepository.acquireManagementWrite("principal-1"))
                .thenReturn(1);
    }

    private RagApiKey key(String keyId, int version, boolean enabled) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId("principal-1");
        key.setCredentialVersion(version);
        key.setEnabled(enabled);
        return key;
    }

    private ApiKeyRotationOperation operation() {
        ApiKeyRotationOperation operation = new ApiKeyRotationOperation();
        operation.setRotationId(UUID.randomUUID());
        operation.setPrincipalId("principal-1");
        operation.setStatus(ApiKeyRotationStatus.PENDING);
        operation.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        operation.setSourceCredentialId("src-1");
        operation.setTargetCredentialId("tgt-1");
        return operation;
    }

    private void stubCredentials(String sourceKeyId, int sourceVersion,
                                 boolean sourceEnabled,
                                 String targetKeyId, int targetVersion,
                                 boolean targetEnabled) {
        when(apiKeyRepository.findByKeyId("src-1")).thenReturn(
                Optional.of(key(sourceKeyId, sourceVersion, sourceEnabled)));
        when(apiKeyRepository.findByKeyId("tgt-1")).thenReturn(
                Optional.of(key(targetKeyId, targetVersion, targetEnabled)));
    }

    @Test
    void getRotationPrincipalWriteConflictIsNotFound() {
        ApiKeyRotationOperation operation = operation();
        when(rotationOperationRepository.findById(operation.getRotationId()))
                .thenReturn(Optional.of(operation));
        when(principalRepository.acquireManagementWrite("principal-1"))
                .thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> service.getRotation(operation.getRotationId(), null, true));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void completeRotationPrincipalWriteConflictIsNotFound() {
        ApiKeyRotationOperation operation = operation();
        when(rotationOperationRepository.findById(operation.getRotationId()))
                .thenReturn(Optional.of(operation));
        when(principalRepository.acquireManagementWrite("principal-1"))
                .thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> service.completeRotation(operation.getRotationId(), null, true));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void cancelRotationPrincipalWriteConflictIsNotFound() {
        ApiKeyRotationOperation operation = operation();
        when(rotationOperationRepository.findById(operation.getRotationId()))
                .thenReturn(Optional.of(operation));
        when(principalRepository.acquireManagementWrite("principal-1"))
                .thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> service.cancelRotation(operation.getRotationId(), null, true));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void completeRotationDisablesSourceAndCompletes() {
        ApiKeyRotationOperation operation = operation();
        when(rotationOperationRepository.findById(operation.getRotationId()))
                .thenReturn(Optional.of(operation));
        stubCredentials("src-1", 1, true, "tgt-1", 2, true);
        when(apiKeyRepository.disableByKeyId(eq("src-1"), any()))
                .thenReturn(1);

        var response = service.completeRotation(
                operation.getRotationId(), null, true);

        assertEquals(ApiKeyRotationStatus.COMPLETED.name(),
                response.getStatus());
        verify(apiKeyRepository).disableByKeyId(eq("src-1"), any());
    }

    @Test
    void completeRotationSkipsDisableWhenSourceAlreadyDisabled() {
        ApiKeyRotationOperation operation = operation();
        when(rotationOperationRepository.findById(operation.getRotationId()))
                .thenReturn(Optional.of(operation));
        stubCredentials("src-1", 1, false, "tgt-1", 2, true);

        var response = service.completeRotation(
                operation.getRotationId(), null, true);

        assertEquals(ApiKeyRotationStatus.COMPLETED.name(),
                response.getStatus());
        verify(apiKeyRepository, never()).disableByKeyId(eq("src-1"), any());
    }

    @Test
    void completeRotationDisableConflictIsRejected() {
        ApiKeyRotationOperation operation = operation();
        when(rotationOperationRepository.findById(operation.getRotationId()))
                .thenReturn(Optional.of(operation));
        stubCredentials("src-1", 1, true, "tgt-1", 2, true);
        when(apiKeyRepository.disableByKeyId(eq("src-1"), any()))
                .thenReturn(2);

        RagException error = assertThrows(RagException.class,
                () -> service.completeRotation(operation.getRotationId(), null, true));
        assertEquals(ErrorCode.CONCURRENT_MODIFICATION,
                error.getErrorCodeEnum());
    }

    @Test
    void cancelRotationTargetDisableConflictIsRejected() {
        ApiKeyRotationOperation operation = operation();
        when(rotationOperationRepository.findById(operation.getRotationId()))
                .thenReturn(Optional.of(operation));
        stubCredentials("src-1", 1, true, "tgt-1", 2, true);
        when(apiKeyRepository.disableByKeyId(eq("tgt-1"), any()))
                .thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> service.cancelRotation(operation.getRotationId(), null, true));
        assertEquals(ErrorCode.CONCURRENT_MODIFICATION,
                error.getErrorCodeEnum());
    }

    @Test
    void cancelRotationWithDisabledSourceIsRejected() {
        ApiKeyRotationOperation operation = operation();
        when(rotationOperationRepository.findById(operation.getRotationId()))
                .thenReturn(Optional.of(operation));
        // 目标已禁用 → 跳过禁用；源已禁用 → 冲突。
        stubCredentials("src-1", 1, false, "tgt-1", 2, false);

        RagException error = assertThrows(RagException.class,
                () -> service.cancelRotation(operation.getRotationId(), null, true));
        assertEquals(ErrorCode.CONCURRENT_MODIFICATION,
                error.getErrorCodeEnum());
    }

    @Test
    void missingRotationCredentialIsServiceUnavailable() {
        ApiKeyRotationOperation operation = operation();
        when(rotationOperationRepository.findById(operation.getRotationId()))
                .thenReturn(Optional.of(operation));
        when(apiKeyRepository.findByKeyId("src-1"))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.completeRotation(operation.getRotationId(), null, true));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("missing credential"));
    }

    @Test
    void foreignOwnedRotationCredentialIsRejected() {
        ApiKeyRotationOperation operation = operation();
        when(rotationOperationRepository.findById(operation.getRotationId()))
                .thenReturn(Optional.of(operation));
        RagApiKey foreign = key("src-1", 1, true);
        foreign.setPrincipalId("someone-else");
        when(apiKeyRepository.findByKeyId("src-1"))
                .thenReturn(Optional.of(foreign));

        RagException error = assertThrows(RagException.class,
                () -> service.completeRotation(operation.getRotationId(), null, true));
        assertTrue(error.getMessage().contains("another principal"));
    }

    @Test
    void inconsistentCredentialVersionsAreRejected() {
        ApiKeyRotationOperation operation = operation();
        when(rotationOperationRepository.findById(operation.getRotationId()))
                .thenReturn(Optional.of(operation));
        // 目标版本不高于源版本 → 版本倒挂。
        stubCredentials("src-1", 2, true, "tgt-1", 2, true);

        RagException error = assertThrows(RagException.class,
                () -> service.completeRotation(operation.getRotationId(), null, true));
        assertTrue(error.getMessage().contains("versions are inconsistent"));
    }
}
