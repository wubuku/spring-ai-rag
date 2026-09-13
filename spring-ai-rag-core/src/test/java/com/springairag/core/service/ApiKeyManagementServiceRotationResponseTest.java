package com.springairag.core.service;

import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.repository.RagApiKeyRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiPrincipalResponse 组装（Batch 387）：ACTIVE/REVOKED/EXPIRED
 * 状态归并、当前凭据与 retiring 字段、PENDING 轮换的透传窗口。
 */
class ApiKeyManagementServiceRotationResponseTest {

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
                mock(ApiPrincipalLifecycleEventPublisher.class));
    }

    private RagApiPrincipal principal(LocalDateTime revokedAt,
                                      LocalDateTime expiresAt) {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(PRINCIPAL_ID);
        principal.setName("Owner");
        principal.setRole(ApiKeyRole.NORMAL);
        principal.setRevokedAt(revokedAt);
        principal.setExpiresAt(expiresAt);
        principal.setCapabilities(
                com.springairag.core.security.ApiCapabilitySupport.FULL_SERIALIZED);
        principal.setPolicyVersion(1L);
        principal.setRequestsPerMinute(60);
        principal.setAllowedCollectionIds("");
        principal.setCreatedAt(LocalDateTime.now().minusDays(1));
        principal.setUpdatedAt(LocalDateTime.now());
        principal.setLastUsedAt(LocalDateTime.now());
        return principal;
    }

    private RagApiKey credential(String keyId, boolean enabled,
                                 LocalDateTime retireAt) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId(PRINCIPAL_ID);
        key.setEnabled(enabled);
        key.setRetireAt(retireAt);
        key.setCredentialVersion(1);
        return key;
    }

    private ApiKeyRotationOperation pendingOperation() {
        ApiKeyRotationOperation operation =
                mock(ApiKeyRotationOperation.class);
        when(operation.getExpiresAt())
                .thenReturn(LocalDateTime.now().plusMinutes(10));
        when(operation.getRotationId()).thenReturn(UUID.randomUUID());
        return operation;
    }

    private void stubPrincipal(RagApiPrincipal principal) {
        when(principalRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(principal));
    }

    @Test
    void activePrincipalWithCurrentCredentialReportsActiveStatus() {
        RagApiPrincipal principal = principal(null, null);
        stubPrincipal(principal);
        when(apiKeyRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(credential(
                        "rag_sk_current", true, null)));
        when(apiKeyRepository.findLiveRetiring(
                eq(PRINCIPAL_ID), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                eq(PRINCIPAL_ID), eq(ApiKeyRotationStatus.PENDING)))
                .thenReturn(Optional.empty());

        var principals = service.listPrincipals();

        assertEquals(1, principals.size());
        assertEquals("ACTIVE", principals.getFirst().getStatus());
        assertEquals("rag_sk_current",
                principals.getFirst().getCurrentCredentialId());
        assertFalse(principals.getFirst().getRotationPending());
    }

    @Test
    void revokedPrincipalWithPendingRotationReportsRotationWindow() {
        RagApiPrincipal principal =
                principal(LocalDateTime.now().minusHours(1), null);
        stubPrincipal(principal);
        when(apiKeyRepository.findLiveRetiring(
                eq(PRINCIPAL_ID), any(LocalDateTime.class)))
                .thenReturn(Optional.of(credential(
                        "rag_sk_retiring", true,
                        LocalDateTime.now().plusMinutes(10))));
        ApiKeyRotationOperation pending = pendingOperation();
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                eq(PRINCIPAL_ID), eq(ApiKeyRotationStatus.PENDING)))
                .thenReturn(Optional.of(pending));

        var principals = service.listPrincipals();

        assertEquals("REVOKED", principals.getFirst().getStatus());
        assertTrue(principals.getFirst().getRotationPending());
        assertEquals("rag_sk_retiring",
                principals.getFirst().getRetiringCredentialId());
        // rotationPending 与 retiring 凭据互为条件。
        assertNotNull(principals.getFirst().getPendingRotationId());
        assertNull(principals.getFirst().getCurrentCredentialId());
    }

    @Test
    void expiredPrincipalReportsExpiredStatus() {
        stubPrincipal(principal(null, LocalDateTime.now().minusDays(1)));
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                eq(PRINCIPAL_ID), eq(ApiKeyRotationStatus.PENDING)))
                .thenReturn(Optional.empty());

        var principals = service.listPrincipals();

        assertEquals("EXPIRED", principals.getFirst().getStatus());
    }
}
