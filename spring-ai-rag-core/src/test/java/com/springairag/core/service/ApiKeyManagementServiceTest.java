package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiPrincipalPolicyUpdateRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.ApiKeyProvisioningOperation;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.security.ApiCapabilitySupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyManagementServiceTest {

    @Mock RagApiKeyRepository credentialRepository;
    @Mock RagApiPrincipalRepository principalRepository;
    @Mock CollectionIdentityResolver collectionIdentityResolver;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock ApiKeyProvisioningOperationRepository provisioningRepository;
    @Mock ApiPrincipalLifecycleEventPublisher lifecycleEventPublisher;

    private ApiKeyManagementService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyManagementService(
                credentialRepository,
                principalRepository,
                collectionIdentityResolver,
                jdbcTemplate,
                provisioningRepository,
                null,
                new RagProperties(),
                null,
                lifecycleEventPublisher);
    }

    @Test
    void createBuildsStablePrincipalAndVersionOneCredential() {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest(
                "Indexer", LocalDateTime.now().plusDays(30));
        request.setAllowedCollectionIds(List.of(7L, 3L, 7L));
        request.setRequestsPerMinute(120);
        request.setCapabilities(List.of("RAG_READ"));

        ApiKeyCreatedResponse created = service.generateManagedKey(request);

        assertEquals(created.getKeyId(), created.getPrincipalId());
        assertEquals(1, created.getCredentialVersion());
        assertEquals(1L, created.getPolicyVersion());
        assertEquals(120, created.getRequestsPerMinute());
        assertEquals(List.of("RAG_READ"), created.getCapabilities());
        assertTrue(created.getRawKey().startsWith("rag_sk_"));
        assertFalse(created.toString().contains(created.getRawKey()));

        ArgumentCaptor<RagApiPrincipal> principal =
                ArgumentCaptor.forClass(RagApiPrincipal.class);
        verify(principalRepository).save(principal.capture());
        assertEquals("3,7", principal.getValue().getAllowedCollectionIds());
        assertEquals(2, principal.getValue().getNextCredentialVersion());
        assertEquals("RAG_READ", principal.getValue().getCapabilities());

        ArgumentCaptor<RagApiKey> credential =
                ArgumentCaptor.forClass(RagApiKey.class);
        verify(credentialRepository).save(credential.capture());
        assertEquals(principal.getValue().getPrincipalId(),
                credential.getValue().getPrincipalId());
        assertEquals(1, credential.getValue().getCredentialVersion());
        verify(lifecycleEventPublisher).publishAfterCommit(
                principal.getValue().getPrincipalId());
    }

    @Test
    void updateAndRevokePublishLifecycleEvents() {
        RagApiPrincipal updated = principal("rag_k_update");
        when(principalRepository.acquireManagementWrite(
                updated.getPrincipalId())).thenReturn(1);
        when(principalRepository.findByPrincipalId(
                updated.getPrincipalId())).thenReturn(Optional.of(updated));
        ApiPrincipalPolicyUpdateRequest update = policyRequest(updated);
        update.setExpiresAt(LocalDateTime.now().plusDays(20));

        service.updatePolicy(
                updated.getPrincipalId(), update, null, true);

        verify(lifecycleEventPublisher).publishAfterCommit(
                updated.getPrincipalId());

        RagApiPrincipal revoked = principal("rag_k_revoke");
        RagApiKey current = credential(
                "rag_k_revoke_current",
                revoked.getPrincipalId(),
                1,
                true);
        when(credentialRepository.findByKeyId(current.getKeyId()))
                .thenReturn(Optional.of(current));
        when(principalRepository.acquireManagementWrite(
                revoked.getPrincipalId())).thenReturn(1);
        when(principalRepository.findByPrincipalId(
                revoked.getPrincipalId())).thenReturn(Optional.of(revoked));
        when(credentialRepository
                .findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                        revoked.getPrincipalId()))
                .thenReturn(Optional.of(current));
        when(credentialRepository.disableAllActiveByPrincipalId(
                eq(revoked.getPrincipalId()), any(LocalDateTime.class)))
                .thenReturn(1);

        assertTrue(service.revokeManagedKey(current.getKeyId()));
        verify(lifecycleEventPublisher).publishAfterCommit(
                revoked.getPrincipalId());
    }

    @Test
    void authenticateQueriesAuthorityEveryTimeButThrottlesAuditTouch() {
        RagApiKeyRepository.AuthenticationProjection projection =
                mock(RagApiKeyRepository.AuthenticationProjection.class);
        when(projection.getPrincipalId()).thenReturn("rag_k_principal");
        when(projection.getCredentialId()).thenReturn("rag_k_v2");
        when(projection.getCredentialVersion()).thenReturn(2);
        when(projection.getRole()).thenReturn(ApiKeyRole.NORMAL);
        when(projection.getPolicyVersion()).thenReturn(4L);
        when(projection.getRequestsPerMinute()).thenReturn(80);
        when(projection.getCapabilities()).thenReturn("RAG_READ");
        when(credentialRepository.authenticate(anyString(), any(LocalDateTime.class)))
                .thenReturn(Optional.of(projection));

        AuthenticatedApiPrincipal first = service.authenticate("rag_sk_secret");
        AuthenticatedApiPrincipal second = service.authenticate("rag_sk_secret");

        assertEquals("rag_k_principal", first.getPrincipalId());
        assertEquals("rag_k_v2", first.getCredentialId());
        assertEquals(2, first.getCredentialVersion());
        assertEquals(4L, first.getPolicyVersion());
        assertEquals(List.of("RAG_READ"), first.getCapabilities());
        assertEquals(first, second);
        verify(credentialRepository, times(2))
                .authenticate(anyString(), any(LocalDateTime.class));
        verify(principalRepository, times(1)).touchLastUsedIfOlder(
                eq("rag_k_principal"), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void rotatePreservesPrincipalAndPolicyAndAdvancesCredentialVersion() {
        RagApiPrincipal principal = principal("rag_k_principal");
        RagApiKey current = credential("rag_k_v1", principal.getPrincipalId(), 1, true);
        when(credentialRepository.findByKeyId("rag_k_v1"))
                .thenReturn(Optional.of(current));
        when(principalRepository.acquireManagementWrite(principal.getPrincipalId()))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(principal.getPrincipalId()))
                .thenReturn(Optional.of(principal));
        when(credentialRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                principal.getPrincipalId())).thenReturn(Optional.of(current));
        when(credentialRepository.disableByKeyId(eq("rag_k_v1"), any(LocalDateTime.class)))
                .thenReturn(1);

        ApiKeyCreatedResponse rotated = service.rotateManagedKey("rag_k_v1");

        assertEquals(principal.getPrincipalId(), rotated.getPrincipalId());
        assertEquals(2, rotated.getCredentialVersion());
        assertEquals(3L, rotated.getPolicyVersion());
        assertEquals(3, principal.getNextCredentialVersion());
        ArgumentCaptor<RagApiKey> replacement =
                ArgumentCaptor.forClass(RagApiKey.class);
        verify(credentialRepository).save(replacement.capture());
        assertEquals(principal.getPrincipalId(), replacement.getValue().getPrincipalId());
        assertEquals(2, replacement.getValue().getCredentialVersion());
    }

    @Test
    void rotateRejectsStaleCredential() {
        RagApiPrincipal principal = principal("rag_k_principal");
        RagApiKey stale = credential("rag_k_v1", principal.getPrincipalId(), 1, false);
        RagApiKey current = credential("rag_k_v2", principal.getPrincipalId(), 2, true);
        principal.setNextCredentialVersion(3);
        when(credentialRepository.findByKeyId(stale.getKeyId()))
                .thenReturn(Optional.of(stale));
        when(principalRepository.acquireManagementWrite(principal.getPrincipalId()))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(principal.getPrincipalId()))
                .thenReturn(Optional.of(principal));
        when(credentialRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                principal.getPrincipalId())).thenReturn(Optional.of(current));

        RagException error = assertThrows(
                RagException.class,
                () -> service.rotateKey(stale.getKeyId()));
        assertEquals(ErrorCode.CREDENTIAL_NOT_CURRENT, error.getErrorCodeEnum());
    }

    @Test
    void repeatedDeleteOfLatestCredentialIsIdempotent() {
        RagApiPrincipal principal = principal("rag_k_principal");
        principal.setNextCredentialVersion(3);
        principal.setRevokedAt(LocalDateTime.now());
        RagApiKey latest = credential("rag_k_v2", principal.getPrincipalId(), 2, false);
        latest.setRevokedAt(principal.getRevokedAt());
        when(credentialRepository.findByKeyId(latest.getKeyId()))
                .thenReturn(Optional.of(latest));
        when(principalRepository.acquireManagementWrite(principal.getPrincipalId()))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(principal.getPrincipalId()))
                .thenReturn(Optional.of(principal));

        assertTrue(service.revokeManagedKey(latest.getKeyId()));
        verify(credentialRepository, never())
                .disableByKeyId(anyString(), any(LocalDateTime.class));
    }

    @Test
    void policyUpdateRejectsStaleVersion() {
        RagApiPrincipal principal = principal("rag_k_principal");
        when(principalRepository.acquireManagementWrite(principal.getPrincipalId()))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(principal.getPrincipalId()))
                .thenReturn(Optional.of(principal));
        ApiPrincipalPolicyUpdateRequest request = new ApiPrincipalPolicyUpdateRequest();
        request.setExpectedPolicyVersion(2L);
        request.setName("Updated");
        request.setExpiresAt(LocalDateTime.now().plusDays(10));

        RagException error = assertThrows(
                RagException.class,
                () -> service.updatePolicy(
                        principal.getPrincipalId(), request, null, true));
        assertEquals(ErrorCode.POLICY_VERSION_CONFLICT, error.getErrorCodeEnum());
    }

    @Test
    void adminCannotBeDowngradedToReadOnly() {
        RagApiPrincipal principal = principal("rag_admin", ApiKeyRole.ADMIN);
        when(principalRepository.acquireManagementWrite(principal.getPrincipalId()))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(principal.getPrincipalId()))
                .thenReturn(Optional.of(principal));
        ApiPrincipalPolicyUpdateRequest request = policyRequest(principal);
        request.setCapabilities(List.of(ApiCapabilitySupport.RAG_READ));

        RagException error = assertThrows(
                RagException.class,
                () -> service.updatePolicy(
                        principal.getPrincipalId(), request, null, true));
        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCodeEnum());
        assertEquals("RAG_READ,RAG_WRITE", principal.getCapabilities());
    }

    @Test
    void nonManagedCredentialPrefixesNeverReachDatabase() {
        assertNull(service.authenticate("legacy-static"));
        assertNull(service.authenticate(null));
        verifyNoInteractions(credentialRepository);
    }

    @Test
    void generatedIdentifiersHaveExpectedEntropyEncoding() {
        String raw = service.generateRawKey();
        String id = service.generateKeyId();
        assertTrue(raw.matches("rag_sk_[0-9a-f]{64}"));
        assertTrue(id.matches("rag_k_[0-9a-f]{32}"));
    }

    @Test
    void idempotentCreateReplaysWithoutReturningSecret() {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest(
                "Indexer", LocalDateTime.now().plusDays(30));
        request.setAllowedCollectionIds(List.of(7L, 3L));
        when(provisioningRepository.findByOwnerIdAndIdempotencyKeyHash(
                "root:environment-root", "hash"))
                .thenReturn(Optional.empty());

        ApiKeyManagementService.ProvisioningResult first =
                service.generateIdempotentKey(
                        request, ApiKeyRole.NORMAL,
                        "root:environment-root", "hash", true);
        ArgumentCaptor<ApiKeyProvisioningOperation> operationCaptor =
                ArgumentCaptor.forClass(ApiKeyProvisioningOperation.class);
        verify(provisioningRepository).saveAndFlush(operationCaptor.capture());
        ApiKeyProvisioningOperation operation = operationCaptor.getValue();
        RagApiPrincipal principal = principal(first.response().getPrincipalId());
        principal.setName(first.response().getName());
        principal.setExpiresAt(first.response().getExpiresAt());
        principal.setAllowedCollectionIds("3,7");
        RagApiKey current = credential(
                first.response().getKeyId(),
                principal.getPrincipalId(), 1, true);
        when(principalRepository.findByPrincipalId(principal.getPrincipalId()))
                .thenReturn(Optional.of(principal));
        when(credentialRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                principal.getPrincipalId())).thenReturn(Optional.of(current));
        when(provisioningRepository.findByOwnerIdAndIdempotencyKeyHash(
                "root:environment-root", "hash"))
                .thenReturn(Optional.of(operation));

        ApiKeyManagementService.ProvisioningResult replay =
                service.generateIdempotentKey(
                        reordered(request), ApiKeyRole.NORMAL,
                        "root:environment-root", "hash", true);

        assertFalse(first.replay());
        assertTrue(replay.replay());
        assertEquals(first.response().getPrincipalId(), replay.response().getPrincipalId());
        assertNull(replay.response().getRawKey());
        assertFalse(replay.response().getSecretAvailable());
        assertTrue(replay.response().getIdempotentReplay());
    }

    @Test
    void idempotentCreateRejectsSameOwnerAndKeyForDifferentRequest() {
        ApiKeyProvisioningOperation operation = new ApiKeyProvisioningOperation();
        operation.setOwnerId("root:environment-root");
        operation.setIdempotencyKeyHash("hash");
        operation.setRequestFingerprintSha256("different");
        operation.setPrincipalId("rag_k_existing");
        when(provisioningRepository.findByOwnerIdAndIdempotencyKeyHash(
                "root:environment-root", "hash"))
                .thenReturn(Optional.of(operation));

        RagException error = assertThrows(
                RagException.class,
                () -> service.generateIdempotentKey(
                        new ApiKeyCreateRequest(
                                "Indexer", LocalDateTime.now().plusDays(30)),
                        ApiKeyRole.NORMAL,
                        "root:environment-root", "hash", true));

        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, error.getErrorCodeEnum());
        verifyNoInteractions(credentialRepository);
    }

    private ApiKeyCreateRequest reordered(ApiKeyCreateRequest original) {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest(
                original.getName(), original.getExpiresAt());
        request.setAllowedCollectionIds(List.of(3L, 7L));
        return request;
    }

    private RagApiPrincipal principal(String id) {
        return principal(id, ApiKeyRole.NORMAL);
    }

    private RagApiPrincipal principal(String id, ApiKeyRole role) {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(id);
        principal.setName("Indexer");
        principal.setRole(role);
        principal.setAllowedCollectionIds("3,7");
        principal.setExpiresAt(LocalDateTime.now().plusDays(30));
        principal.setRequestsPerMinute(120);
        principal.setPolicyVersion(3L);
        principal.setNextCredentialVersion(2);
        principal.setCapabilities("RAG_READ,RAG_WRITE");
        principal.setCreatedAt(LocalDateTime.now().minusDays(1));
        principal.setUpdatedAt(LocalDateTime.now().minusDays(1));
        return principal;
    }

    private ApiPrincipalPolicyUpdateRequest policyRequest(RagApiPrincipal principal) {
        ApiPrincipalPolicyUpdateRequest request = new ApiPrincipalPolicyUpdateRequest();
        request.setExpectedPolicyVersion(principal.getPolicyVersion());
        request.setName(principal.getName());
        request.setExpiresAt(principal.getExpiresAt());
        return request;
    }

    private RagApiKey credential(
            String id, String principalId, int version, boolean enabled) {
        RagApiKey credential = new RagApiKey();
        credential.setKeyId(id);
        credential.setPrincipalId(principalId);
        credential.setCredentialVersion(version);
        credential.setEnabled(enabled);
        credential.setRole(ApiKeyRole.NORMAL);
        credential.setName("Indexer");
        return credential;
    }
}
