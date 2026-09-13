package com.springairag.core.service;

import com.springairag.api.dto.ApiPrincipalPolicyUpdateRequest;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiKeyManagementService rotate 尾部与 updatePolicy 守卫
 * （Batch 374）：轮换的未知 key/管理锁失败/PENDING 冲突/disable
 * 计数不符与成功路径；列表查询；策略更新的锁失败/缺失/版本冲突/
 * ADMIN 过期变更拒绝。
 */
class ApiKeyManagementServiceRotatePolicyTest {

    private static final String KEY_ID = "rag_sk_current";
    private static final String PRINCIPAL_ID = "principal-1";

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyRotationOperationRepository rotationOperationRepository;
    private ApiKeyManagementService service;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);
        rotationOperationRepository =
                mock(ApiKeyRotationOperationRepository.class);
        service = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                mock(ApiKeyProvisioningOperationRepository.class),
                rotationOperationRepository,
                new RagProperties(),
                mock(PlatformTransactionManager.class),
                mock(com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher.class));
    }

    private RagApiKey key(String keyId) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId(PRINCIPAL_ID);
        return key;
    }

    private RagApiPrincipal principal() {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(PRINCIPAL_ID);
        principal.setRole(ApiKeyRole.NORMAL);
        principal.setNextCredentialVersion(2);
        return principal;
    }

    private void stubRotateHappyPathHead() {
        when(apiKeyRepository.findByKeyId(KEY_ID))
                .thenReturn(Optional.of(key(KEY_ID)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal()));
        // cleanup 阶段（首次查询）无 PENDING。
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                eq(PRINCIPAL_ID), eq(ApiKeyRotationStatus.PENDING)))
                .thenReturn(Optional.empty());
    }

    @Test
    void rotateReturnsNullWhenKeyIdUnknown() {
        when(apiKeyRepository.findByKeyId("missing"))
                .thenReturn(Optional.empty());

        assertNull(service.rotateKey("missing"));
    }

    @Test
    void rotateReturnsNullWhenManagementLockUnavailable() {
        when(apiKeyRepository.findByKeyId(KEY_ID))
                .thenReturn(Optional.of(key(KEY_ID)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(0);

        assertNull(service.rotateKey(KEY_ID));
    }

    @Test
    void rotateThrowsRotationPendingWhenPendingExists() {
        stubRotateHappyPathHead();
        // 轮换检查阶段（第二次查询）命中 PENDING。
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                eq(PRINCIPAL_ID), eq(ApiKeyRotationStatus.PENDING)))
                .thenReturn(Optional.of(mock(ApiKeyRotationOperation.class)));

        RagException error = assertThrows(RagException.class,
                () -> service.rotateKey(KEY_ID));
        assertEquals(ErrorCode.CREDENTIAL_ROTATION_PENDING,
                error.getErrorCodeEnum());
    }

    @Test
    void rotateThrowsCredentialNotCurrentWhenDisableCountMismatch() {
        stubRotateHappyPathHead();
        when(apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(key(KEY_ID)));
        when(apiKeyRepository.disableByKeyId(eq(KEY_ID),
                any(LocalDateTime.class))).thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> service.rotateKey(KEY_ID));
        assertEquals(ErrorCode.CREDENTIAL_NOT_CURRENT,
                error.getErrorCodeEnum());
    }

    @Test
    void rotateSuccessSavesReplacementCredential() {
        stubRotateHappyPathHead();
        when(apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(key(KEY_ID)));
        when(apiKeyRepository.disableByKeyId(eq(KEY_ID),
                any(LocalDateTime.class))).thenReturn(1);

        var response = service.rotateKey(KEY_ID);

        org.junit.jupiter.api.Assertions.assertNotNull(response);
        verify(apiKeyRepository).save(any(RagApiKey.class));
    }

    @Test
    void listQueriesReturnEmptyLists() {
        when(apiKeyRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of());
        when(principalRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of());

        assertEquals(List.of(), service.listKeys());
        assertEquals(List.of(), service.listPrincipals());
    }

    @Test
    void updatePolicyReturnsNullWhenLockUnavailableOrPrincipalMissing() {
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(0);
        assertNull(service.updatePolicy(PRINCIPAL_ID, request(), null, false));

        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.empty());
        assertNull(service.updatePolicy(PRINCIPAL_ID, request(), null, false));
    }

    @Test
    void updatePolicyThrowsVersionConflict() {
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        RagApiPrincipal principal = principal();
        principal.setPolicyVersion(4L);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal));

        RagException error = assertThrows(RagException.class,
                () -> service.updatePolicy(
                        PRINCIPAL_ID, request(1L), null, false));
        assertEquals(ErrorCode.POLICY_VERSION_CONFLICT,
                error.getErrorCodeEnum());
    }

    @Test
    void updatePolicyRejectsAdminExpiryChangeWithoutRoot() {
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        RagApiPrincipal principal = principal();
        principal.setRole(ApiKeyRole.ADMIN);
        principal.setPolicyVersion(1L);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal));
        ApiPrincipalPolicyUpdateRequest req = request(1L);
        req.setExpiresAt(LocalDateTime.now().plusDays(30));

        RagException error = assertThrows(RagException.class,
                () -> service.updatePolicy(
                        PRINCIPAL_ID, req, null, false));
        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCodeEnum());
    }

    private ApiPrincipalPolicyUpdateRequest request() {
        return request(1L);
    }

    private ApiPrincipalPolicyUpdateRequest request(Long expectedVersion) {
        ApiPrincipalPolicyUpdateRequest request =
                new ApiPrincipalPolicyUpdateRequest();
        request.setExpectedPolicyVersion(expectedVersion);
        return request;
    }
}
