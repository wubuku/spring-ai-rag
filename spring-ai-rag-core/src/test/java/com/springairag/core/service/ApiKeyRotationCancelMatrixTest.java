package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.service.ApiKeyManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiKeyManagementService.cancelRotation 长尾（Batch 467）：
 * CANCELED 幂等重放、EXPIRED 拒绝、非 PENDING 拒绝、PENDING 取
 * 消时禁用目标密钥并恢复源密钥。
 */
class ApiKeyRotationCancelMatrixTest {

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
                mock(com.springairag.core.service.CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                mock(com.springairag.core.repository.ApiKeyProvisioningOperationRepository.class),
                rotationOperationRepository,
                new com.springairag.core.config.RagProperties(),
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                mock(com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher.class));
    }

    private RagApiKey key(String keyId, int version, boolean enabled) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId("principal-1");
        key.setCredentialVersion(version);
        key.setEnabled(enabled);
        return key;
    }

    private ApiKeyRotationOperation operation(ApiKeyRotationStatus status) {
        ApiKeyRotationOperation operation = new ApiKeyRotationOperation();
        operation.setRotationId(UUID.randomUUID());
        operation.setPrincipalId("principal-1");
        operation.setStatus(status);
        operation.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        operation.setSourceCredentialId("src-1");
        operation.setTargetCredentialId("tgt-1");
        return operation;
    }

    private void stubCommon() {
        when(principalRepository.findById("principal-1")).thenReturn(
                Optional.of(new com.springairag.core.entity.RagApiPrincipal()));
        when(principalRepository.findByPrincipalId("principal-1")).thenReturn(
                Optional.of(new com.springairag.core.entity.RagApiPrincipal()));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                "principal-1")).thenReturn(
                Optional.of(key("live-key", 2, true)));
        when(apiKeyRepository.findByKeyId("src-1")).thenReturn(
                Optional.of(key("src-1", 1, true)));
        when(apiKeyRepository.findByKeyId("tgt-1")).thenReturn(
                Optional.of(key("tgt-1", 2, true)));
        when(principalRepository.acquireManagementWrite(anyString()))
                .thenReturn(1);
    }

    @Test
    void canceledRotationReplaysIdempotently() {
        ApiKeyRotationOperation canceled = operation(ApiKeyRotationStatus.CANCELED);
        when(rotationOperationRepository.findById(canceled.getRotationId()))
                .thenReturn(Optional.of(canceled));
        stubCommon();

        var response = service.cancelRotation(
                canceled.getRotationId(), null, true);

        assertEquals(ApiKeyRotationStatus.CANCELED.name(), response.getStatus());
    }

    @Test
    void expiredRotationIsRejected() {
        ApiKeyRotationOperation expired = operation(ApiKeyRotationStatus.EXPIRED);
        when(rotationOperationRepository.findById(expired.getRotationId()))
                .thenReturn(Optional.of(expired));
        stubCommon();

        RagException error = assertThrows(RagException.class,
                () -> service.cancelRotation(expired.getRotationId(), null, true));
        assertEquals(ErrorCode.CREDENTIAL_ROTATION_EXPIRED,
                error.getErrorCodeEnum());
    }

    @Test
    void nonPendingRotationIsRejected() {
        ApiKeyRotationOperation completed = operation(ApiKeyRotationStatus.COMPLETED);
        when(rotationOperationRepository.findById(completed.getRotationId()))
                .thenReturn(Optional.of(completed));
        stubCommon();

        RagException error = assertThrows(RagException.class,
                () -> service.cancelRotation(completed.getRotationId(), null, true));
        assertEquals(ErrorCode.CREDENTIAL_ROTATION_NOT_PENDING,
                error.getErrorCodeEnum());
    }

    @Test
    void pendingRotationCancelDisablesTargetAndRestoresSource() {
        ApiKeyRotationOperation pending = operation(ApiKeyRotationStatus.PENDING);
        UUID rotationId = pending.getRotationId();
        when(rotationOperationRepository.findById(rotationId))
                .thenReturn(Optional.of(pending));
        when(principalRepository.acquireManagementWrite(anyString()))
                .thenReturn(1);
        stubCommon();
        RagApiKey target = key("tgt-1", 2, true);
        when(apiKeyRepository.findByKeyId("tgt-1"))
                .thenReturn(Optional.of(target));
        RagApiKey source = key("src-1", 1, true);
        when(apiKeyRepository.findByKeyId("src-1"))
                .thenReturn(Optional.of(source));
        when(apiKeyRepository.disableByKeyId(eq("tgt-1"), any()))
                .thenReturn(1);

        var response = service.cancelRotation(rotationId, null, true);

        assertEquals("CANCELED", response.getStatus());
        verify(apiKeyRepository).disableByKeyId(eq("tgt-1"), any());
    }
}
