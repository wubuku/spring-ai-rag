package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyProvisioningOperation;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * generateIdempotentKey 的守卫与幂等回放（Batch 310）：开关与
 * 账本可用性、归属与哈希必填、托管过期校验、幂等键复用拒绝、
 * 命中指纹回放、并发竞态重试耗尽。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiKeyProvisioningIdempotentKeyTest {

    private static final String OWNER = "owner-1";
    private static final String HASH = "hash-1";

    @Mock RagApiKeyRepository apiKeyRepository;
    @Mock RagApiPrincipalRepository principalRepository;
    @Mock ApiKeyProvisioningOperationRepository provisioningOperationRepository;

    @Test
    void disabledIdempotencyRejected() {
        RagProperties properties = new RagProperties();
        properties.getApiKeyProvisioning().setEnabled(false);
        ApiKeyManagementService service = service(properties, true);

        RagException error = assertThrows(RagException.class,
                () -> service.generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, OWNER, HASH, false));

        assertEquals(ErrorCode.API_KEY_PROVISIONING_IDEMPOTENCY_DISABLED,
                error.getErrorCodeEnum());
    }

    @Test
    void unavailableLedgerRejected() {
        ApiKeyManagementService service = service(new RagProperties(), false);

        RagException error = assertThrows(RagException.class,
                () -> service.generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, OWNER, HASH, false));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("ledger is unavailable"));
    }

    @Test
    void ownerAndHashRequired() {
        ApiKeyManagementService service = service(new RagProperties(), true);

        assertThrows(IllegalArgumentException.class,
                () -> service.generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, null, HASH, false));
        assertThrows(IllegalArgumentException.class,
                () -> service.generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, "  ", HASH, false));
        assertThrows(IllegalArgumentException.class,
                () -> service.generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, OWNER, null, false));
        assertThrows(NullPointerException.class,
                () -> service.generateIdempotentKey(
                        null, ApiKeyRole.NORMAL, OWNER, HASH, false));
    }

    @Test
    void managedExpiryMustBeFuture() {
        ApiKeyManagementService service = service(new RagProperties(), true);

        // managed=true：过期时间为空或已过均拒绝。
        assertThrows(IllegalArgumentException.class,
                () -> service.generateIdempotentKey(
                        request(null), ApiKeyRole.NORMAL, OWNER, HASH, true));
        assertThrows(IllegalArgumentException.class,
                () -> service.generateIdempotentKey(
                        request(LocalDateTime.now().minusDays(1)),
                        ApiKeyRole.NORMAL, OWNER, HASH, true));
    }

    @Test
    void reusedIdempotencyKeyWithDifferentRequestRejected() {
        ApiKeyManagementService service = service(new RagProperties(), true);
        ApiKeyProvisioningOperation existing = new ApiKeyProvisioningOperation();
        existing.setRequestFingerprintSha256("different-fingerprint");
        when(provisioningOperationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, HASH))
                .thenReturn(Optional.of(existing));

        RagException error = assertThrows(RagException.class,
                () -> service.generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, OWNER, HASH, false));

        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, error.getErrorCodeEnum());
    }

    @Test
    void matchingFingerprintReplaysCachedResponse() {
        ApiKeyManagementService service = service(new RagProperties(), true);
        RagApiPrincipal principal = principal();
        // 指纹必须基于传给服务的同一 request 实例（过期时间参与指纹）。
        ApiKeyCreateRequest request = request();
        ApiKeyProvisioningOperation existing = new ApiKeyProvisioningOperation();
        existing.setPrincipalId(principal.getPrincipalId());
        existing.setRequestFingerprintSha256(
                com.springairag.core.service.ApiKeyProvisioningFingerprint
                        .sha256(request, "NORMAL"));
        when(provisioningOperationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, HASH))
                .thenReturn(Optional.of(existing));
        when(principalRepository.findByPrincipalId(principal.getPrincipalId()))
                .thenReturn(Optional.of(principal));
        RagApiKey current = new RagApiKey();
        current.setKeyId("rag_k_live");
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                principal.getPrincipalId()))
                .thenReturn(Optional.of(current));

        ApiKeyManagementService.ProvisioningResult result =
                service.generateIdempotentKey(
                        request, ApiKeyRole.NORMAL, OWNER, HASH, false);

        assertTrue(result.replay());
        assertEquals("rag_k_live", result.response().getKeyId());
        assertEquals("Indexer", result.response().getName());
    }

    @Test
    void missingPrincipalOnReplayRejected() {
        ApiKeyManagementService service = service(new RagProperties(), true);
        ApiKeyCreateRequest request = request();
        ApiKeyProvisioningOperation existing = new ApiKeyProvisioningOperation();
        existing.setPrincipalId("rag_k_missing");
        existing.setRequestFingerprintSha256(
                com.springairag.core.service.ApiKeyProvisioningFingerprint
                        .sha256(request, "NORMAL"));
        when(provisioningOperationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, HASH))
                .thenReturn(Optional.of(existing));
        when(principalRepository.findByPrincipalId("rag_k_missing"))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.generateIdempotentKey(
                        request, ApiKeyRole.NORMAL, OWNER, HASH, false));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("no longer has a principal"));
    }

    @Test
    void concurrentRaceExhaustsRetriesAndFails() {
        RagProperties properties = new RagProperties();
        // 竞态重试收敛为 1 次：立即耗尽，不触发退避等待。
        properties.getApiKeyProvisioning().setConcurrentRetryAttempts(1);
        ApiKeyManagementService service = service(properties, true);
        when(provisioningOperationRepository.findByOwnerIdAndIdempotencyKeyHash(
                anyString(), anyString()))
                .thenThrow(new DataIntegrityViolationException("race"));

        RagException error = assertThrows(RagException.class,
                () -> service.generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, OWNER, HASH, false));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
        assertTrue(error.getMessage()
                .contains("Unable to resolve a concurrent provisioning"));
    }

    @Test
    void repeatedRacesEventuallyExhaustDefaultRetries() {
        ApiKeyManagementService service = service(new RagProperties(), true);
        when(provisioningOperationRepository.findByOwnerIdAndIdempotencyKeyHash(
                anyString(), anyString()))
                .thenThrow(new DataIntegrityViolationException("race"));

        RagException error = assertThrows(RagException.class,
                () -> service.generateIdempotentKey(
                        request(), ApiKeyRole.NORMAL, OWNER, HASH, false));

        // 默认 3 次尝试全部耗尽后转为 SERVICE_UNAVAILABLE。
        assertNotNull(error);
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
        assertFalse(error.getMessage().contains("Idempotency"));
    }

    // ── fixture ─────────────────────────────────────────────────────

    private ApiKeyManagementService service(
            RagProperties properties, boolean withLedger) {
        return new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                withLedger ? provisioningOperationRepository : null,
                null,
                properties,
                null,
                null);
    }

    private ApiKeyCreateRequest request() {
        return request(LocalDateTime.now().plusDays(30));
    }

    private ApiKeyCreateRequest request(LocalDateTime expiresAt) {
        return new ApiKeyCreateRequest("Indexer", expiresAt);
    }

    private RagApiPrincipal principal() {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId("rag_k_replay");
        principal.setName("Indexer");
        principal.setRole(com.springairag.core.entity.ApiKeyRole.NORMAL);
        principal.setAllowedCollectionIds("3,7");
        principal.setExpiresAt(LocalDateTime.now().plusDays(30));
        principal.setRequestsPerMinute(120);
        principal.setCapabilities("RAG_READ,RAG_WRITE");
        return principal;
    }
}
