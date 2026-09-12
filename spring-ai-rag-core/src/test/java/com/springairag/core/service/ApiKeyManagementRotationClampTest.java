package com.springairag.core.service;

import com.springairag.api.dto.ApiPrincipalPolicyUpdateRequest;
import com.springairag.api.dto.ApiPrincipalResponse;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.config.RagProperties;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * clampPendingRotationDeadline（经 updatePolicy 入口）的截断分支
 * （Batch 309）：主体过期时间为空、无待定轮换、操作截止更晚、
 * 未来截止收敛、已过期截止直接判过期并禁用源凭证。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiKeyManagementRotationClampTest {

    private static final String PRINCIPAL_ID = "rag_k_clamp";

    @Mock RagApiKeyRepository credentialRepository;
    @Mock RagApiPrincipalRepository principalRepository;
    @Mock ApiKeyRotationOperationRepository rotationOperationRepository;
    @Mock ApiPrincipalLifecycleEventPublisher lifecycleEventPublisher;

    private ApiKeyManagementService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyManagementService(
                credentialRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                mock(ApiKeyProvisioningOperationRepository.class),
                rotationOperationRepository,
                new RagProperties(),
                null,
                lifecycleEventPublisher);
    }

    @Test
    void clampSkipsWhenPrincipalExpiryRemoved() {
        RagApiPrincipal principal = principal();
        stubUpdateFlow(principal, null);

        ApiPrincipalResponse response =
                service.updatePolicy(PRINCIPAL_ID, policyRequest(null), null, false);

        assertNotNull(response);
        // clamp 在主体过期时间为空时短路；响应装配仍会查询轮换仓储。
        verify(rotationOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    void clampSkipsWhenNoPendingOperation() {
        RagApiPrincipal principal = principal();
        LocalDateTime newExpiry = LocalDateTime.now().plusDays(5);
        stubUpdateFlow(principal, newExpiry);
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                PRINCIPAL_ID, ApiKeyRotationStatus.PENDING))
                .thenReturn(Optional.empty());

        assertNotNull(service.updatePolicy(
                PRINCIPAL_ID, policyRequest(newExpiry), null, false));

        verify(rotationOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    void clampSkipsWhenOperationDeadlineAlreadyEarlier() {
        RagApiPrincipal principal = principal();
        LocalDateTime newExpiry = LocalDateTime.now().plusDays(45);
        stubUpdateFlow(principal, newExpiry);
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                PRINCIPAL_ID, ApiKeyRotationStatus.PENDING))
                .thenReturn(Optional.of(operation(LocalDateTime.now().plusDays(40))));
        RagApiKey source = sourceCredential(1);
        RagApiKey target = sourceCredential(2);
        when(credentialRepository.findByKeyId("rag_k_src"))
                .thenReturn(Optional.of(source));
        when(credentialRepository.findByKeyId("rag_k_tgt"))
                .thenReturn(Optional.of(target));

        assertNotNull(service.updatePolicy(
                PRINCIPAL_ID, policyRequest(newExpiry), null, false));

        // 主体截止晚于操作截止：不收敛、不落库。
        verify(rotationOperationRepository, never()).saveAndFlush(any());
        verify(credentialRepository, never()).saveAndFlush(any());
    }

    @Test
    void clampTrimsOperationAndSourceRetireAtWhenFuture() {
        RagApiPrincipal principal = principal();
        LocalDateTime newExpiry = LocalDateTime.now().plusDays(5);
        stubUpdateFlow(principal, newExpiry);
        ApiKeyRotationOperation operation =
                operation(LocalDateTime.now().plusDays(30));
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                PRINCIPAL_ID, ApiKeyRotationStatus.PENDING))
                .thenReturn(Optional.of(operation));
        RagApiKey source = sourceCredential(1);
        RagApiKey target = sourceCredential(2);
        when(credentialRepository.findByKeyId("rag_k_src"))
                .thenReturn(Optional.of(source));
        when(credentialRepository.findByKeyId("rag_k_tgt"))
                .thenReturn(Optional.of(target));

        assertNotNull(service.updatePolicy(
                PRINCIPAL_ID, policyRequest(newExpiry), null, false));

        assertEquals(newExpiry, operation.getExpiresAt());
        assertEquals(newExpiry, source.getRetireAt());
        verify(credentialRepository).saveAndFlush(source);
        verify(rotationOperationRepository).saveAndFlush(operation);
        assertEquals(ApiKeyRotationStatus.PENDING, operation.getStatus());
    }

    @Test
    void clampExpiresOperationAndDisablesSourceWhenDeadlinePassed() {
        RagApiPrincipal principal = principal();
        LocalDateTime pastExpiry = LocalDateTime.now().minusMinutes(5);
        stubUpdateFlow(principal, pastExpiry);
        ApiKeyRotationOperation operation =
                operation(LocalDateTime.now().plusDays(30));
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                PRINCIPAL_ID, ApiKeyRotationStatus.PENDING))
                .thenReturn(Optional.of(operation));
        RagApiKey source = sourceCredential(1);
        RagApiKey target = sourceCredential(2);
        when(credentialRepository.findByKeyId("rag_k_src"))
                .thenReturn(Optional.of(source));
        when(credentialRepository.findByKeyId("rag_k_tgt"))
                .thenReturn(Optional.of(target));

        assertNotNull(service.updatePolicy(
                PRINCIPAL_ID, policyRequest(pastExpiry), null, false));

        // 截止时间已过：源凭证被禁用，操作进入 EXPIRED 终态。
        verify(credentialRepository).disableByKeyId(eq("rag_k_src"), any());
        ArgumentCaptor<ApiKeyRotationOperation> saved =
                ArgumentCaptor.forClass(ApiKeyRotationOperation.class);
        verify(rotationOperationRepository).saveAndFlush(saved.capture());
        assertEquals(ApiKeyRotationStatus.EXPIRED,
                saved.getValue().getStatus());
        assertNotNull(saved.getValue().getTerminalAt());
    }

    // ── fixture ─────────────────────────────────────────────────────

    private void stubUpdateFlow(RagApiPrincipal principal, LocalDateTime expiry) {
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal));
    }

    private RagApiPrincipal principal() {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(PRINCIPAL_ID);
        principal.setName("Indexer");
        principal.setRole(com.springairag.core.entity.ApiKeyRole.NORMAL);
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

    private ApiPrincipalPolicyUpdateRequest policyRequest(LocalDateTime expiry) {
        ApiPrincipalPolicyUpdateRequest request =
                new ApiPrincipalPolicyUpdateRequest();
        request.setExpectedPolicyVersion(3L);
        request.setName("Indexer");
        request.setExpiresAt(expiry);
        return request;
    }

    private ApiKeyRotationOperation operation(LocalDateTime expiresAt) {
        ApiKeyRotationOperation operation = new ApiKeyRotationOperation();
        operation.setPrincipalId(PRINCIPAL_ID);
        operation.setStatus(ApiKeyRotationStatus.PENDING);
        operation.setExpiresAt(expiresAt);
        operation.setSourceCredentialId("rag_k_src");
        operation.setTargetCredentialId("rag_k_tgt");
        return operation;
    }

    private RagApiKey sourceCredential(int version) {
        RagApiKey credential = new RagApiKey();
        credential.setKeyId(version == 2 ? "rag_k_tgt" : "rag_k_src");
        credential.setPrincipalId(PRINCIPAL_ID);
        credential.setCredentialVersion(version);
        credential.setEnabled(version == 1);
        return credential;
    }
}
