package com.springairag.core.service;

import com.springairag.core.repository.RagApiKeyRepository.AuthenticationProjection;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiKeyManagementService key 校验残余（Batch 385）：裸 key 校验
 * 返回 principalId 或 null、last-used 触碰的 DataAccessException
 * 容错与 5 分钟节流缓存。
 */
class ApiKeyManagementServiceKeyValidationTest {

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyManagementService service;
    private AuthenticationProjection authenticated;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);
        authenticated = mock(AuthenticationProjection.class);
        when(authenticated.getPrincipalId()).thenReturn("principal-1");
        when(authenticated.getCredentialId()).thenReturn("cred-1");
        when(authenticated.getCredentialVersion()).thenReturn(1);
        when(authenticated.getRole()).thenReturn(
                com.springairag.core.entity.ApiKeyRole.NORMAL);
        when(authenticated.getAllowedCollectionIds()).thenReturn("");
        when(authenticated.getPolicyVersion()).thenReturn(1L);
        when(authenticated.getRequestsPerMinute()).thenReturn(60);
        when(authenticated.getCapabilities()).thenReturn(
                com.springairag.core.security.ApiCapabilitySupport.FULL_SERIALIZED);
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
    }

    @Test
    void validateKeyReturnsNullForBlankMalformedOrUnknownRawKeys() {
        assertNull(service.validateKey(null));
        assertNull(service.validateKey("   "));
        assertNull(service.validateKey("no-prefix-key"));
        assertNull(service.validateKey("rag_sk_unknown"));
    }

    @Test
    void validateKeyReturnsPrincipalIdForKnownRawKey() {
        when(authenticated.getPrincipalId()).thenReturn("principal-1");
        when(apiKeyRepository.authenticate(anyString(),
                any(LocalDateTime.class)))
                .thenReturn(java.util.Optional.of(authenticated));

        assertEquals("principal-1", service.validateKey(
                "rag_sk_0123456789abcdef0123456789abcdef"));
    }

    @Test
    void touchLastUsedSwallowsDataAccessFailures() {
        when(apiKeyRepository.authenticate(anyString(),
                any(LocalDateTime.class)))
                .thenReturn(java.util.Optional.of(authenticated));
        when(principalRepository.touchLastUsedIfOlder(
                eq("principal-1"), any(LocalDateTime.class),
                any(LocalDateTime.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertEquals("principal-1", service.validateKey(
                "rag_sk_0123456789abcdef0123456789abcdef"));
    }

    @Test
    void touchLastUsesThrottledByCacheWithinInterval() {
        when(apiKeyRepository.authenticate(anyString(),
                any(LocalDateTime.class)))
                .thenReturn(java.util.Optional.of(authenticated));

        service.validateKey("rag_sk_0123456789abcdef0123456789abcdef");
        service.validateKey("rag_sk_0123456789abcdef0123456789abcdef");

        // 5 分钟节流缓存：同 principal 只触碰一次。
        verify(principalRepository, times(1)).touchLastUsedIfOlder(
                eq("principal-1"), any(LocalDateTime.class),
                any(LocalDateTime.class));
    }
}
