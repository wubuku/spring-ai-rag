package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiKeyManagementService 供给并发重试长尾（Batch 642，JaCoCo 驱
 * 动）：DataIntegrityViolationException 在重试预算内耗尽时的
 * SERVICE_UNAVAILABLE 收敛错误，以及重试退避休眠被中断时的
 * interrupted 恢复路径（中断标志复位）。
 */
class ApiKeyManagementProvisionRetryTailTest {

    private RagApiPrincipalRepository principalRepository;
    private ApiKeyProvisioningOperationRepository provisioningRepository;

    @BeforeEach
    void setUp() {
        principalRepository = mock(RagApiPrincipalRepository.class);
        provisioningRepository = mock(ApiKeyProvisioningOperationRepository.class);
    }

    @AfterEach
    void tearDown() {
        // 清理可能残留的中断标志，避免污染同线程的后续用例。
        Thread.interrupted();
    }

    private ApiKeyManagementService service(int retryAttempts) {
        RagProperties properties = new RagProperties();
        properties.getApiKeyProvisioning().setEnabled(true);
        properties.getApiKeyProvisioning()
                .setConcurrentRetryAttempts(retryAttempts);
        return new ApiKeyManagementService(
                mock(RagApiKeyRepository.class),
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                provisioningRepository,
                mock(ApiKeyRotationOperationRepository.class),
                properties,
                mock(PlatformTransactionManager.class),
                mock(ApiPrincipalLifecycleEventPublisher.class));
    }

    private ApiKeyCreateRequest request() {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setName("Prod Key");
        return request;
    }

    @Test
    void concurrentProvisioningRaceExhaustsRetryBudget() {
        when(provisioningRepository.findByOwnerIdAndIdempotencyKeyHash(
                any(), any()))
                .thenReturn(Optional.empty());
        when(principalRepository.save(any(RagApiPrincipal.class)))
                .thenThrow(new DataIntegrityViolationException("并发插入冲突"));

        RagException error = assertThrows(RagException.class,
                () -> service(2).generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, "owner-1", "hash-1",
                        false));

        assertEquals(RagException.class, error.getClass());
        assertTrue(error.getMessage()
                .contains("Unable to resolve a concurrent provisioning request"));
    }

    @Test
    void interruptedRetryBackoffRestoresInterruptFlag() {
        when(provisioningRepository.findByOwnerIdAndIdempotencyKeyHash(
                any(), any()))
                .thenReturn(Optional.empty());
        when(principalRepository.save(any(RagApiPrincipal.class)))
                .thenThrow(new DataIntegrityViolationException("并发插入冲突"));
        Thread.currentThread().interrupt();

        RagException error = assertThrows(RagException.class,
                () -> service(2).generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, "owner-1", "hash-1",
                        false));

        assertTrue(error.getMessage().contains("interrupted"));
        assertTrue(Thread.currentThread().isInterrupted(),
                "中断标志应被保留");
    }
}
