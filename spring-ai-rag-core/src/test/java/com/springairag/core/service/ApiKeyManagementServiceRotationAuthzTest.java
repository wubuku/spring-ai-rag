package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * rotation 授权矩阵（Batch 394）：authorizeRotation 残余分支——
 * 无策略调用方拒绝、NORMAL 他人凭据/非当前 prepare 凭据拒绝、
 * ADMIN 任意 principal 放行、root 全放行。
 */
class ApiKeyManagementServiceRotationAuthzTest {

    private static final String PRINCIPAL_ID = "principal-1";
    private static final String CURRENT_KEY = "rag_sk_current";
    private static final String SOURCE_KEY = "rag_sk_source";
    private static final String TARGET_KEY = "rag_sk_target";

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyRotationOperationRepository rotationOperationRepository;
    private ApiKeyManagementService service;
    private ApiKeyRotationOperation operation;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);
        rotationOperationRepository =
                mock(ApiKeyRotationOperationRepository.class);

        operation = mock(ApiKeyRotationOperation.class);
        when(operation.getPrincipalId()).thenReturn(PRINCIPAL_ID);
        when(operation.getRotationId()).thenReturn(UUID.randomUUID());
        when(operation.getExpiresAt()).thenReturn(
                LocalDateTime.now().plusMinutes(10));
        when(operation.getStatus()).thenReturn(ApiKeyRotationStatus.PENDING);
        when(operation.getSourceCredentialId()).thenReturn(SOURCE_KEY);
        when(operation.getTargetCredentialId()).thenReturn(TARGET_KEY);
        when(rotationOperationRepository.findById(any(UUID.class)))
                .thenReturn(Optional.of(operation));

        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(activePrincipal()));
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1, true)));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                PRINCIPAL_ID))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1, true)));

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

    private RagApiPrincipal activePrincipal() {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(PRINCIPAL_ID);
        principal.setName("Owner");
        principal.setRole(ApiKeyRole.NORMAL);
        principal.setCapabilities(
                com.springairag.core.security.ApiCapabilitySupport.FULL_SERIALIZED);
        principal.setPolicyVersion(1L);
        principal.setRequestsPerMinute(60);
        principal.setNextCredentialVersion(5);
        return principal;
    }

    private RagApiKey credential(String keyId, int version, boolean enabled) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId(PRINCIPAL_ID);
        key.setCredentialVersion(version);
        key.setEnabled(enabled);
        return key;
    }

    private ApiAccessPolicy caller(String principalId, ApiKeyRole role,
                                   String credentialId) {
        ApiAccessPolicy policy = mock(ApiAccessPolicy.class);
        when(policy.getPrincipalId()).thenReturn(principalId);
        when(policy.getRole()).thenReturn(role);
        when(policy.getCredentialId()).thenReturn(credentialId);
        return policy;
    }

    @Test
    void prepareRejectsCallerWithoutDatabasePolicy() {
        var error = assertThrows(SecurityException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, null, "idem-1", null, false));
        assertEquals("A database-backed ADMIN or owning credential is required",
                error.getMessage());
    }

    @Test
    void prepareRejectsNormalManagingForeignPrincipal() {
        ApiAccessPolicy foreign = caller("other-principal",
                ApiKeyRole.NORMAL, CURRENT_KEY);

        var error = assertThrows(SecurityException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, null, "idem-1", foreign, false));
        assertEquals("NORMAL credentials can only manage their own rotation",
                error.getMessage());
    }

    @Test
    void prepareRejectsNormalPrepareFromNonCurrentCredential() {
        ApiAccessPolicy self = caller(PRINCIPAL_ID, ApiKeyRole.NORMAL,
                "rag_sk_old");

        var error = assertThrows(SecurityException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, null, "idem-1", self, false));
        assertEquals("NORMAL credentials must prepare rotation from their current credential",
                error.getMessage());
    }

    @Test
    void prepareAllowsAdminFromForeignPrincipal() {
        when(apiKeyRepository.findByKeyId(anyString()))
                .thenAnswer(invocation -> {
                    String keyId = invocation.getArgument(0);
                    return Optional.of(credential(keyId,
                            CURRENT_KEY.equals(keyId) ? 1 : 3, true));
                });

        var result = service.prepareRotation(
                CURRENT_KEY, null, "idem-1",
                caller("admin-1", ApiKeyRole.ADMIN, TARGET_KEY), true);

        assertNotNull(result);
        assertEquals("PENDING", result.response().getStatus());
    }

    @Test
    void getRotationRejectsNormalForeignCaller() {
        var error = assertThrows(SecurityException.class,
                () -> service.getRotation(UUID.randomUUID(),
                        caller("other-principal", ApiKeyRole.NORMAL, CURRENT_KEY),
                        false));
        assertEquals("NORMAL credentials can only manage their own rotation",
                error.getMessage());
    }

    @Test
    void getRotationAllowsAdminForeignCaller() {
        // rotationResponse 需要解析 source/target 凭据。
        when(apiKeyRepository.findByKeyId(SOURCE_KEY))
                .thenReturn(Optional.of(credential(SOURCE_KEY, 1, true)));
        when(apiKeyRepository.findByKeyId(TARGET_KEY))
                .thenReturn(Optional.of(credential(TARGET_KEY, 3, true)));

        var response = service.getRotation(UUID.randomUUID(),
                caller("admin-1", ApiKeyRole.ADMIN, TARGET_KEY), true);

        assertEquals("PENDING", response.getStatus());
    }

    @Test
    void getRotationRequiresLedgerRepository() {
        var bare = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                mock(ApiKeyProvisioningOperationRepository.class),
                null,
                new RagProperties(),
                mock(PlatformTransactionManager.class),
                mock(ApiPrincipalLifecycleEventPublisher.class));

        RagException error = assertThrows(RagException.class,
                () -> bare.getRotation(UUID.randomUUID(), null, true));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,
                error.getErrorCodeEnum());
    }
}
