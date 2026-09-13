package com.springairag.core.service;

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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiKeyManagementService 撤销路径与构造器重载（Batch 373）：
 * revoke 主流程成功/各类冲突、ADMIN 守卫（root 与非 root）、
 * PENDING 轮换终止、已撤销 principal 的幂等与冲突分支。
 */
class ApiKeyManagementServiceRevokeTest {

    private static final String KEY_ID = "rag_sk_current";
    private static final String PRINCIPAL_ID = "principal-1";

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private JdbcTemplate jdbcTemplate;
    private ApiKeyRotationOperationRepository rotationOperationRepository;
    private ApiPrincipalLifecycleEventPublisher lifecycleEventPublisher;
    private ApiKeyManagementService service;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        rotationOperationRepository =
                mock(ApiKeyRotationOperationRepository.class);
        lifecycleEventPublisher =
                mock(ApiPrincipalLifecycleEventPublisher.class);
        service = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                jdbcTemplate,
                mock(ApiKeyProvisioningOperationRepository.class),
                rotationOperationRepository,
                new RagProperties(),
                mock(PlatformTransactionManager.class),
                lifecycleEventPublisher);
    }

    private RagApiKey key(String keyId, String principalId,
                           LocalDateTime revokedAt) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId(principalId);
        key.setRevokedAt(revokedAt);
        return key;
    }

    private RagApiPrincipal principal(ApiKeyRole role, LocalDateTime revokedAt) {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(PRINCIPAL_ID);
        principal.setRole(role);
        principal.setRevokedAt(revokedAt);
        principal.setNextCredentialVersion(2);
        return principal;
    }

    private void stubHappyPath(RagApiPrincipal principal) {
        when(apiKeyRepository.findByKeyId(KEY_ID))
                .thenReturn(Optional.of(key(KEY_ID, PRINCIPAL_ID, null)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal));
        when(apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(key(KEY_ID, PRINCIPAL_ID, null)));
        when(apiKeyRepository.disableAllActiveByPrincipalId(
                eq(PRINCIPAL_ID), any(LocalDateTime.class))).thenReturn(1);
        lenient().when(rotationOperationRepository.findByPrincipalIdAndStatus(
                eq(PRINCIPAL_ID), eq(ApiKeyRotationStatus.PENDING)))
                .thenReturn(Optional.empty());
    }

    @Test
    void constructorOverloadsSmoke() {
        assertNotNull(new ApiKeyManagementService(
                apiKeyRepository, principalRepository, null, jdbcTemplate));
        assertNotNull(new ApiKeyManagementService(
                apiKeyRepository, principalRepository, null, jdbcTemplate,
                null, new RagProperties(), null));
        assertNotNull(new ApiKeyManagementService(
                apiKeyRepository, principalRepository, null, jdbcTemplate,
                null, null, new RagProperties(), null));
    }

    @Test
    void revokeKeyReturnsFalseWhenKeyIdUnknown() {
        when(apiKeyRepository.findByKeyId("missing"))
                .thenReturn(Optional.empty());

        assertFalse(service.revokeKey("missing"));
    }

    @Test
    void revokeReturnsTrueWhenAlreadyRevokedWithSameKey() {
        LocalDateTime revokedAt = LocalDateTime.now().minusHours(1);
        when(apiKeyRepository.findByKeyId(KEY_ID))
                .thenReturn(Optional.of(key(KEY_ID, PRINCIPAL_ID, revokedAt)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal(ApiKeyRole.NORMAL,
                        revokedAt)));

        assertTrue(service.revokeKey(KEY_ID));
    }

    @Test
    void revokeThrowsCredentialNotCurrentWhenRevokedWithDifferentKey() {
        LocalDateTime principalRevokedAt = LocalDateTime.now().minusHours(1);
        when(apiKeyRepository.findByKeyId(KEY_ID))
                .thenReturn(Optional.of(key(KEY_ID, PRINCIPAL_ID,
                        principalRevokedAt.plusMinutes(1))));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal(ApiKeyRole.NORMAL,
                        principalRevokedAt)));

        RagException error = assertThrows(RagException.class,
                () -> service.revokeKey(KEY_ID));
        assertEquals(ErrorCode.CREDENTIAL_NOT_CURRENT,
                error.getErrorCodeEnum());
    }

    @Test
    void revokeSuccessNormalRoleRevokesPendingRotation() {
        RagApiPrincipal principal = principal(ApiKeyRole.NORMAL, null);
        stubHappyPath(principal);
        ApiKeyRotationOperation pending =
                mock(ApiKeyRotationOperation.class);
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                eq(PRINCIPAL_ID), eq(ApiKeyRotationStatus.PENDING)))
                .thenReturn(Optional.of(pending));

        assertTrue(service.revokeKey(KEY_ID));

        verify(pending).setStatus(ApiKeyRotationStatus.REVOKED);
        verify(rotationOperationRepository).save(pending);
        verify(principalRepository).saveAndFlush(principal);
    }

    @Test
    void revokeAdminWithoutRootThrowsLastAdminWhenGuardUpdateFails() {
        RagApiPrincipal principal = principal(ApiKeyRole.ADMIN, null);
        stubHappyPath(principal);
        when(jdbcTemplate.update(anyString())).thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> service.revokeKey(KEY_ID));
        assertEquals(ErrorCode.LAST_ADMIN_REQUIRED,
                error.getErrorCodeEnum());
    }

    @Test
    void revokeManagedAdminDecrementsGuardUnconditionally() {
        // root 模式无条件扣减守卫计数：update=1 → 撤销成功。
        RagApiPrincipal principal = principal(ApiKeyRole.ADMIN, null);
        stubHappyPath(principal);
        when(jdbcTemplate.update(anyString())).thenReturn(1);

        assertTrue(service.revokeManagedKey(KEY_ID));
    }

    @Test
    void revokeManagedAdminThrowsLastAdminWhenGuardUpdateFails() {
        // root 模式 update=0 → 守卫计数异常 → LAST_ADMIN_REQUIRED。
        RagApiPrincipal principal = principal(ApiKeyRole.ADMIN, null);
        stubHappyPath(principal);
        when(jdbcTemplate.update(anyString())).thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> service.revokeManagedKey(KEY_ID));
        assertEquals(ErrorCode.LAST_ADMIN_REQUIRED,
                error.getErrorCodeEnum());
    }

    @Test
    void revokeThrowsRotationConflictWhenDisableCountZero() {
        RagApiPrincipal principal = principal(ApiKeyRole.NORMAL, null);
        stubHappyPath(principal);
        when(apiKeyRepository.disableAllActiveByPrincipalId(
                eq(PRINCIPAL_ID), any(LocalDateTime.class))).thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> service.revokeKey(KEY_ID));
        assertEquals(ErrorCode.CONCURRENT_MODIFICATION,
                error.getErrorCodeEnum());
    }

    @Test
    void revokeThrowsCredentialNotCurrentWhenRequestedNotCurrent() {
        RagApiPrincipal principal = principal(ApiKeyRole.NORMAL, null);
        stubHappyPath(principal);
        when(apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(key("rag_sk_other", PRINCIPAL_ID, null)));

        RagException error = assertThrows(RagException.class,
                () -> service.revokeKey(KEY_ID));
        assertEquals(ErrorCode.CREDENTIAL_NOT_CURRENT,
                error.getErrorCodeEnum());
    }
}
