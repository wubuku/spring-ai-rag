package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiKeyManagementService 供应台账长尾（Batch 500，JaCoCo 驱动）：
 * generateIdempotentKey 的门禁矩阵（功能关闭 / 台账缺失 / 空请求 /
 * 空白 owner 或哈希 / managed 过期时间非法），以及 cleanup
 * ProvisioningLedger 的关闭跳过、按保留期清理与数据访问异常吞噬。
 */
class ApiKeyProvisioningKeyTailTest {

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyProvisioningOperationRepository provisioningOperationRepository;
    private RagProperties ragProperties;
    private ApiKeyManagementService service;
    private ApiKeyManagementService noLedgerService;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);
        provisioningOperationRepository =
                mock(ApiKeyProvisioningOperationRepository.class);
        ragProperties = new RagProperties();
        var rotationRepository =
                mock(ApiKeyRotationOperationRepository.class);
        service = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                provisioningOperationRepository,
                rotationRepository,
                ragProperties,
                mock(PlatformTransactionManager.class),
                mock(com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher.class));
        noLedgerService = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                null,
                rotationRepository,
                ragProperties,
                mock(PlatformTransactionManager.class),
                mock(com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher.class));
    }

    private ApiKeyCreateRequest request() {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setName("ops-key");
        return request;
    }

    // ── generateIdempotentKey 门禁 ────────────────────────────────

    @Test
    void disabledProvisioningRejectsKeyGeneration() {
        ragProperties.getApiKeyProvisioning().setEnabled(false);

        RagException error = assertThrows(RagException.class,
                () -> service.generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, "owner-1", "hash", false));
        assertEquals(ErrorCode.API_KEY_PROVISIONING_IDEMPOTENCY_DISABLED,
                error.getErrorCodeEnum());
    }

    @Test
    void missingLedgerRejectsKeyGeneration() {
        RagException error = assertThrows(RagException.class,
                () -> noLedgerService.generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, "owner-1", "hash", false));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("ledger is unavailable"));
    }

    @Test
    void nullRequestIsRejected() {
        assertThrows(NullPointerException.class,
                () -> service.generateIdempotentKey(
                        null, ApiKeyRole.NORMAL, "owner-1", "hash", false));
    }

    @Test
    void blankOwnerOrHashIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, "  ", "hash", false));
        assertThrows(IllegalArgumentException.class,
                () -> service.generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, "owner-1", " ", false));
    }

    @Test
    void managedExpiryMustBeFutureDated() {
        assertThrows(IllegalArgumentException.class,
                () -> service.generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, "owner-1", "hash", true));

        ApiKeyCreateRequest expired = request();
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        assertThrows(IllegalArgumentException.class,
                () -> service.generateIdempotentKey(
                        expired, ApiKeyRole.NORMAL, "owner-1", "hash", true));
    }

    // ── cleanupProvisioningLedger ─────────────────────────────────

    @Test
    void cleanupSkipsWhenProvisioningDisabled() {
        ragProperties.getApiKeyProvisioning().setEnabled(false);

        service.cleanupProvisioningLedger();

        verify(provisioningOperationRepository, never())
                .deleteCompletedBefore(any(), anyInt());
    }

    @Test
    void cleanupDeletesCompletedBeforeRetentionHorizon() {
        service.cleanupProvisioningLedger();

        verify(provisioningOperationRepository).deleteCompletedBefore(
                any(LocalDateTime.class), eq(500));
    }

    @Test
    void cleanupSwallowsDataAccessFailures() {
        when(provisioningOperationRepository.deleteCompletedBefore(
                any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        service.cleanupProvisioningLedger();

        verify(provisioningOperationRepository).deleteCompletedBefore(
                any(), anyInt());
    }

}
