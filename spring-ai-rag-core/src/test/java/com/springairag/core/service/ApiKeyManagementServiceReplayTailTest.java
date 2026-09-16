package com.springairag.core.service;

import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiKeyManagementService.replayResponse 长尾（Batch 457）：缺失
 * principal 的 fail-closed、活跃凭证重放（keyId + 版本）、凭证缺
 * 失或主体已撤销/过期时的降级重放。
 */
class ApiKeyManagementServiceReplayTailTest {

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyManagementService service;
    private RagApiPrincipal principal;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);
        service = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                mock(ApiKeyProvisioningOperationRepository.class),
                mock(ApiKeyRotationOperationRepository.class),
                new com.springairag.core.config.RagProperties(),
                mock(PlatformTransactionManager.class),
                mock(ApiPrincipalLifecycleEventPublisher.class));

        principal = new RagApiPrincipal();
        principal.setPrincipalId("principal-1");
        principal.setName("Prod Key");
        principal.setAllowedCollectionIds("7,8");
        principal.setExpiresAt(null);
        principal.setRevokedAt(null);
    }

    private com.springairag.api.dto.ApiKeyCreatedResponse replayResponse(
            RagApiKey currentKey) throws Exception {
        when(principalRepository.findByPrincipalId("principal-1"))
                .thenReturn(Optional.of(principal));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                "principal-1"))
                .thenReturn(Optional.ofNullable(currentKey));
        Method method = ApiKeyManagementService.class.getDeclaredMethod(
                "replayResponse",
                com.springairag.core.entity.ApiKeyProvisioningOperation.class);
        method.setAccessible(true);
        var operation = mock(
                com.springairag.core.entity.ApiKeyProvisioningOperation.class);
        when(operation.getPrincipalId()).thenReturn("principal-1");
        return (com.springairag.api.dto.ApiKeyCreatedResponse)
                method.invoke(service, operation);
    }

    @Test
    void missingPrincipalFailsClosed() throws Exception {
        // helper 会先 stub principal 存在，这里直接置空再调用。
        when(principalRepository.findByPrincipalId("principal-1"))
                .thenReturn(Optional.empty());
        Method method = ApiKeyManagementService.class.getDeclaredMethod(
                "replayResponse",
                com.springairag.core.entity.ApiKeyProvisioningOperation.class);
        method.setAccessible(true);
        var operation = mock(
                com.springairag.core.entity.ApiKeyProvisioningOperation.class);
        when(operation.getPrincipalId()).thenReturn("principal-1");

        try {
            method.invoke(service, operation);
            org.junit.jupiter.api.Assertions.fail(
                    "expected RagException for missing principal");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertEquals(RagException.class, e.getCause().getClass());
        }
    }

    @Test
    void activePrincipalReplaysWithKeyIdAndVersion() throws Exception {
        RagApiKey currentKey = new RagApiKey();
        currentKey.setKeyId("key-123");
        currentKey.setCredentialVersion(2);

        var response = replayResponse(currentKey);

        assertEquals("key-123", response.getKeyId());
        assertEquals(2, response.getCredentialVersion());
        assertEquals("Prod Key", response.getName());
    }

    @Test
    void missingCurrentKeyDegradesReplayWithoutKeyId() throws Exception {
        var response = replayResponse(null);

        assertNull(response.getKeyId());
        assertNull(response.getCredentialVersion());
        assertEquals("Prod Key", response.getName());
    }

    @Test
    void revokedOrExpiredPrincipalDegradesReplay() throws Exception {
        principal.setRevokedAt(LocalDateTime.now().minusDays(1));
        RagApiKey currentKey = new RagApiKey();
        currentKey.setKeyId("key-123");
        currentKey.setCredentialVersion(2);

        var revoked = replayResponse(currentKey);
        assertNull(revoked.getKeyId());

        principal.setRevokedAt(null);
        principal.setExpiresAt(LocalDateTime.now().minusHours(1));
        var expired = replayResponse(currentKey);
        assertNull(expired.getKeyId());
    }
}
