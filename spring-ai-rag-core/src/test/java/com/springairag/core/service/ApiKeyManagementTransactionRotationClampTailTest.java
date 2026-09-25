package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiKeyManagementService 事务模板与轮换截止钳制长尾（Batch 638，
 * JaCoCo 驱动）：供给幂等路径在事务模板内执行（非重放新建）、
 * prepareRotation 将 retireAt 钳制到主体到期时间。
 */
class ApiKeyManagementTransactionRotationClampTailTest {

    private static final String PRINCIPAL_ID = "principal-1";
    private static final String CURRENT_KEY = "rag_sk_current";

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyProvisioningOperationRepository provisioningRepository;
    private ApiKeyRotationOperationRepository rotationOperationRepository;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);
        provisioningRepository = mock(ApiKeyProvisioningOperationRepository.class);
        rotationOperationRepository = mock(ApiKeyRotationOperationRepository.class);
    }

    private ApiKeyManagementService service() {
        RagProperties properties = new RagProperties();
        properties.getApiKeyProvisioning().setEnabled(true);
        return new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                provisioningRepository,
                rotationOperationRepository,
                properties,
                mock(PlatformTransactionManager.class),
                mock(ApiPrincipalLifecycleEventPublisher.class));
    }

    private RagApiKey credential(String keyId, int version) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId(PRINCIPAL_ID);
        key.setCredentialVersion(version);
        key.setEnabled(true);
        return key;
    }

    @Test
    void idempotentProvisioningRunsInsideTransactionTemplate() {
        when(provisioningRepository.findByOwnerIdAndIdempotencyKeyHash(
                "owner-1", "hash-1"))
                .thenReturn(Optional.empty());
        when(principalRepository.save(any(RagApiPrincipal.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(apiKeyRepository.save(any(RagApiKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setName("Prod Key");

        ApiKeyManagementService.ProvisioningResult result =
                service().generateIdempotentKey(
                        request, ApiKeyRole.NORMAL, "owner-1", "hash-1",
                        false);

        assertNotNull(result);
        assertFalse(result.replay());
        verify(principalRepository).save(any(RagApiPrincipal.class));
    }

    @Test
    void prepareRotationClampsDeadlineToPrincipalExpiry() {
        RagApiKey current = credential(CURRENT_KEY, 2);
        when(apiKeyRepository.findByKeyId(anyString())).thenAnswer(
                invocation -> {
                    String keyId = invocation.getArgument(0);
                    if (CURRENT_KEY.equals(keyId)) {
                        return Optional.of(current);
                    }
                    return Optional.of(credential(keyId, 3));
                });
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        eq(PRINCIPAL_ID), anyString()))
                .thenReturn(Optional.empty());
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(PRINCIPAL_ID);
        principal.setRole(ApiKeyRole.NORMAL);
        principal.setCapabilities(
                com.springairag.core.security.ApiCapabilitySupport.FULL_SERIALIZED);
        principal.setPolicyVersion(1L);
        principal.setRequestsPerMinute(60);
        principal.setNextCredentialVersion(5);
        principal.setExpiresAt(LocalDateTime.now().plusSeconds(30));
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal));
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                PRINCIPAL_ID, ApiKeyRotationStatus.PENDING))
                .thenReturn(Optional.empty());
        when(apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(current));
        lenient().when(apiKeyRepository.findLiveRetiring(
                        eq(PRINCIPAL_ID), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        lenient().when(apiKeyRepository.disableByKeyId(anyString(),
                any(LocalDateTime.class))).thenReturn(1);
        lenient().when(apiKeyRepository.saveAndFlush(any(RagApiKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(principalRepository.saveAndFlush(
                        any(RagApiPrincipal.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(rotationOperationRepository.saveAndFlush(
                        any(ApiKeyRotationOperation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApiKeyManagementService.RotationResult result = service()
                .prepareRotation(CURRENT_KEY, 3600, "idem-1", null, true);

        assertNotNull(result);
        assertFalse(result.replay());
        ArgumentCaptor<RagApiKey> captor =
                ArgumentCaptor.forClass(RagApiKey.class);
        verify(apiKeyRepository, org.mockito.Mockito.times(2))
                .saveAndFlush(captor.capture());
        RagApiKey clamped = captor.getAllValues().stream()
                .filter(key -> CURRENT_KEY.equals(key.getKeyId()))
                .findFirst()
                .orElseThrow();
        assertEquals(principal.getExpiresAt(), clamped.getRetireAt());
    }
}
