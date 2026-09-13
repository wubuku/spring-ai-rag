package com.springairag.core.service;

import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.security.ApiAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * authorizeRotation 授权矩阵（Batch 352，经 prepareRotation 入口
 * 驱动）：environmentRoot 豁免、无主体调用方拒绝、ADMIN 越权豁
 * 免、NORMAL 仅可管理自身轮换、prepare 必须来自当前凭证。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiKeyRotationAuthorizeMatrixTest {

    @Mock RagApiKeyRepository apiKeyRepository;
    @Mock RagApiPrincipalRepository principalRepository;
    @Mock ApiKeyRotationOperationRepository rotationOperationRepository;

    private ApiKeyManagementService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                mock(ApiKeyProvisioningOperationRepository.class),
                rotationOperationRepository,
                new RagProperties(),
                null,
                null);
        when(principalRepository.acquireManagementWrite(anyString()))
                .thenReturn(0);
    }

    private RagApiKey credential(String keyId, String principalId) {
        RagApiKey credential = new RagApiKey();
        credential.setKeyId(keyId);
        credential.setPrincipalId(principalId);
        credential.setCredentialVersion(1);
        credential.setEnabled(true);
        return credential;
    }

    private ApiAccessPolicy caller(
            String principalId, String credentialId, ApiKeyRole role) {
        return new ApiAccessPolicy() {
            @Override public String getPrincipalId() { return principalId; }
            @Override public String getCredentialId() { return credentialId; }
            @Override public ApiKeyRole getRole() { return role; }
            @Override public String getAllowedCollectionIds() { return null; }
            @Override public java.time.LocalDateTime getExpiresAt() { return null; }
        };
    }

    private Object prepare(String currentKeyId, ApiAccessPolicy caller,
                           boolean environmentRoot) {
        when(apiKeyRepository.findByKeyId(currentKeyId))
                .thenReturn(java.util.Optional.of(
                        credential(currentKeyId, "p-1")));

        return service.prepareRotation(
                currentKeyId, null, "hash-1", caller, environmentRoot);
    }

    @Test
    void environmentRootSkipsAuthorization() {
        // environmentRoot：即使无调用方也放行（后续管理写为 0 → null）。
        assertNull(prepare("rag_k_cur", null, true));
    }

    @Test
    void callerWithoutPrincipalIdRejected() {
        SecurityException error = assertThrows(SecurityException.class,
                () -> prepare("rag_k_cur", caller(null, "kid", ApiKeyRole.ADMIN), false));
        assertEquals("A database-backed ADMIN or owning credential is required",
                error.getMessage());
    }

    @Test
    void missingCallerRejected() {
        SecurityException error = assertThrows(SecurityException.class,
                () -> prepare("rag_k_cur", null, false));
        assertEquals("A database-backed ADMIN or owning credential is required",
                error.getMessage());
    }

    @Test
    void adminRoleBypassesOwnershipCheck() {
        // ADMIN：即使主体不同也放行授权（管理写 0 → null 早退）。
        assertNull(prepare("rag_k_cur",
                caller("db:someone-else", "rag_k_cur", ApiKeyRole.ADMIN), false));
    }

    @Test
    void normalCredentialCannotRotateForeignPrincipal() {
        SecurityException error = assertThrows(SecurityException.class,
                () -> prepare("rag_k_cur",
                        caller("db:me", "rag_k_cur", ApiKeyRole.NORMAL), false));
        assertEquals("NORMAL credentials can only manage their own rotation",
                error.getMessage());
    }

    @Test
    void normalPrepareMustUseCurrentCredential() {
        // NORMAL 主体匹配但 prepare 凭证与当前凭证不一致。
        SecurityException error = assertThrows(SecurityException.class,
                () -> prepare("rag_k_cur",
                        caller("p-1", "rag_k_other", ApiKeyRole.NORMAL), false));
        assertEquals("NORMAL credentials must prepare rotation from their current credential",
                error.getMessage());
    }
}
