package com.springairag.core.service;

import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiKeyManagementService.expireRotationById 长尾（Batch 459）：
 * 缺失/非 PENDING/未到期早退、管理写竞争失败早退、到期 PENDING
 * 的过期落账与源密钥禁用。
 */
class ApiKeyManagementServiceExpireRotationTailTest {

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyRotationOperationRepository rotationOperationRepository;
    private ApiKeyManagementService service;
    private UUID rotationId;

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
                mock(ApiPrincipalLifecycleEventPublisher.class));
        rotationId = UUID.randomUUID();
    }

    private ApiKeyRotationOperation operation(
            ApiKeyRotationStatus status,
            LocalDateTime expiresAt,
            String sourceId,
            String targetId) {
        ApiKeyRotationOperation operation = new ApiKeyRotationOperation();
        operation.setRotationId(rotationId);
        operation.setPrincipalId("principal-1");
        operation.setStatus(status);
        operation.setExpiresAt(expiresAt);
        operation.setSourceCredentialId(sourceId);
        operation.setTargetCredentialId(targetId);
        return operation;
    }

    private void stubOperation(ApiKeyRotationStatus status,
                               LocalDateTime expiresAt,
                               String sourceId, String targetId) {
        ApiKeyRotationOperation operation = operation(status, expiresAt,
                sourceId, targetId);
        when(rotationOperationRepository.findById(rotationId))
                .thenReturn(Optional.of(operation));
        when(apiKeyRepository.findByKeyId("src-1")).thenReturn(Optional.of(
                key("src-1", 1, true)));
        when(apiKeyRepository.findByKeyId("tgt-1")).thenReturn(Optional.of(
                key("tgt-1", 2, true)));
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

    private void expireRotationById(UUID id) throws Exception {
        Method method = ApiKeyManagementService.class.getDeclaredMethod(
                "expireRotationById", UUID.class);
        method.setAccessible(true);
        method.invoke(service, id);
    }

    @Test
    void missingOperationIsIgnored() throws Exception {
        when(rotationOperationRepository.findById(rotationId))
                .thenReturn(Optional.empty());

        expireRotationById(rotationId);

        verify(principalRepository, never()).acquireManagementWrite(anyString());
    }

    @Test
    void nonPendingOperationIsIgnored() throws Exception {
        stubOperation(ApiKeyRotationStatus.COMPLETED,
                LocalDateTime.now().minusMinutes(5), "src-1", "tgt-1");

        expireRotationById(rotationId);

        verify(principalRepository, never()).acquireManagementWrite(anyString());
    }

    @Test
    void pendingButNotYetExpiredIsIgnored() throws Exception {
        stubOperation(ApiKeyRotationStatus.PENDING,
                LocalDateTime.now().plusMinutes(5), "src-1", "tgt-1");

        expireRotationById(rotationId);

        verify(principalRepository, never()).acquireManagementWrite(anyString());
    }

    @Test
    void managementWriteLossIsIgnored() throws Exception {
        stubOperation(ApiKeyRotationStatus.PENDING,
                LocalDateTime.now().minusMinutes(1), "src-1", "tgt-1");
        when(principalRepository.acquireManagementWrite("principal-1"))
                .thenReturn(0);

        expireRotationById(rotationId);

        verify(rotationOperationRepository, never())
                .saveAndFlush(any(ApiKeyRotationOperation.class));
    }

    @Test
    void expiredPendingIsMarkedExpiredWithSourceDisabled() throws Exception {
        stubOperation(ApiKeyRotationStatus.PENDING,
                LocalDateTime.now().minusMinutes(1), "src-1", "tgt-1");

        expireRotationById(rotationId);

        ArgumentCaptor<ApiKeyRotationOperation> captor =
                ArgumentCaptor.forClass(ApiKeyRotationOperation.class);
        verify(rotationOperationRepository).saveAndFlush(captor.capture());
        assertEquals(ApiKeyRotationStatus.EXPIRED,
                captor.getValue().getStatus());
        verify(apiKeyRepository).disableByKeyId(eq("src-1"), any());
    }
}
