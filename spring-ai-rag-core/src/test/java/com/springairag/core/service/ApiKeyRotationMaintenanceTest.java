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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 轮换台账的读取与回收维护：getRotation 的惰性过期与 retiring 映射、
 * 未知轮换 NOT_FOUND、cleanupCredentialRotations 的逐台账过期与终态
 * 清理、台账或事务缺失时的静默降级。
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyRotationMaintenanceTest {

    private static final String PRINCIPAL_ID = "rag_p_owner";

    @Mock RagApiKeyRepository apiKeyRepository;
    @Mock RagApiPrincipalRepository principalRepository;
    @Mock ApiKeyRotationOperationRepository rotationOperationRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock PlatformTransactionManager transactionManager;

    private ApiKeyManagementService service;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
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
        stubCommon();
    }

    private void stubCommon() {
        lenient().when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal()));
        lenient().when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                PRINCIPAL_ID))
                .thenReturn(Optional.of(credential("tgt", 2, true)));
        lenient().when(apiKeyRepository.findLiveRetiring(
                eq(PRINCIPAL_ID), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
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
        return principal;
    }

    private ApiKeyRotationOperation operation(
            ApiKeyRotationStatus status,
            LocalDateTime expiresAt) {
        ApiKeyRotationOperation operation = new ApiKeyRotationOperation();
        operation.setRotationId(UUID.randomUUID());
        operation.setPrincipalId(PRINCIPAL_ID);
        operation.setIdempotencyKeyHash("hash");
        operation.setRequestFingerprintSha256("fingerprint");
        operation.setSourceCredentialId("src");
        operation.setTargetCredentialId("tgt");
        operation.setOverlapSeconds(300);
        operation.setExpiresAt(expiresAt);
        operation.setStatus(status);
        operation.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        operation.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        return operation;
    }

    private void stubRotationLookup(ApiKeyRotationOperation operation) {
        lenient().when(rotationOperationRepository.findById(operation.getRotationId()))
                .thenReturn(Optional.of(operation));
        lenient().when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        lenient().when(apiKeyRepository.findByKeyId("src"))
                .thenReturn(Optional.of(credential("src", 1, true)));
        lenient().when(apiKeyRepository.findByKeyId("tgt"))
                .thenReturn(Optional.of(credential("tgt", 2, true)));
    }

    @Test
    void getRotationLazilyExpiresAnOverduePendingOverlap() {
        ApiKeyRotationOperation overdue = operation(
                ApiKeyRotationStatus.PENDING, LocalDateTime.now().minusMinutes(1));
        stubRotationLookup(overdue);
        when(apiKeyRepository.disableByKeyId(eq("src"), any()))
                .thenReturn(1);

        var response = service.getRotation(overdue.getRotationId(), null, true);

        // 读取路径也会惰性过期：状态翻转为 EXPIRED 并回传。
        assertEquals("EXPIRED", response.getStatus());
        assertEquals(ApiKeyRotationStatus.EXPIRED, overdue.getStatus());
    }

    @Test
    void getRotationMapsRetiringCredentialAndPendingFlag() {
        ApiKeyRotationOperation pending = operation(
                ApiKeyRotationStatus.PENDING, LocalDateTime.now().plusMinutes(5));
        stubRotationLookup(pending);
        RagApiKey retiring = credential("tgt", 2, true);
        retiring.setRetireAt(LocalDateTime.now().plusMinutes(5));
        when(apiKeyRepository.findLiveRetiring(
                eq(PRINCIPAL_ID), any(LocalDateTime.class)))
                .thenReturn(Optional.of(retiring));

        var response = service.getRotation(pending.getRotationId(), null, true);

        assertEquals("PENDING", response.getStatus());
        assertTrue(response.getRotationPending());
        assertEquals("tgt", response.getRetiringCredentialId());
        assertEquals(Integer.valueOf(2), response.getRetiringCredentialVersion());
    }

    @Test
    void getRotationThrowsNotFoundForUnknownRotationId() {
        when(rotationOperationRepository.findById(any(UUID.class)))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.getRotation(UUID.randomUUID(), null, true));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void cleanupExpiresEachOverduePendingAndDeletesTerminalRows() {
        UUID overdueId = UUID.randomUUID();
        UUID terminalId = UUID.randomUUID();
        ApiKeyRotationOperation overdue = operation(
                ApiKeyRotationStatus.PENDING, LocalDateTime.now().minusMinutes(1));
        overdue.setRotationId(overdueId);
        when(rotationOperationRepository.findExpiredRotationIds(
                eq(ApiKeyRotationStatus.PENDING), any(LocalDateTime.class),
                any(PageRequest.class)))
                .thenReturn(List.of(overdueId, terminalId));
        when(rotationOperationRepository.findById(overdueId))
                .thenReturn(Optional.of(overdue));
        when(rotationOperationRepository.findById(terminalId))
                .thenReturn(Optional.of(operation(
                        ApiKeyRotationStatus.COMPLETED,
                        LocalDateTime.now().plusMinutes(5))));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(apiKeyRepository.findByKeyId("src"))
                .thenReturn(Optional.of(credential("src", 1, true)));
        when(apiKeyRepository.findByKeyId("tgt"))
                .thenReturn(Optional.of(credential("tgt", 2, true)));
        when(apiKeyRepository.disableByKeyId(eq("src"), any()))
                .thenReturn(1);

        service.cleanupCredentialRotations();

        // 过期 PENDING 被逐个翻转，终态行按保留期批量删除。
        assertEquals(ApiKeyRotationStatus.EXPIRED, overdue.getStatus());
        verify(rotationOperationRepository).deleteTerminalBefore(
                any(LocalDateTime.class), anyInt());
    }

    @Test
    void cleanupSkipsOperationsThatAreNoLongerOverdue() {
        UUID stillValidId = UUID.randomUUID();
        ApiKeyRotationOperation stillValid = operation(
                ApiKeyRotationStatus.PENDING, LocalDateTime.now().plusMinutes(5));
        stillValid.setRotationId(stillValidId);
        when(rotationOperationRepository.findExpiredRotationIds(
                eq(ApiKeyRotationStatus.PENDING), any(LocalDateTime.class),
                any(PageRequest.class)))
                .thenReturn(List.of(stillValidId));
        when(rotationOperationRepository.findById(stillValidId))
                .thenReturn(Optional.of(stillValid));

        service.cleanupCredentialRotations();

        // 二次校验发现未到期：不消费凭证、不改状态。
        verify(apiKeyRepository, never()).disableByKeyId(any(), any());
        verify(rotationOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    void cleanupIsSilentWhenRotationLedgerIsMissing() {
        ApiKeyManagementService bare = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                null,
                jdbcTemplate,
                null,
                null,
                properties,
                null,
                null);

        bare.cleanupCredentialRotations();

        verify(rotationOperationRepository, never())
                .deleteTerminalBefore(any(LocalDateTime.class), anyInt());
    }
}