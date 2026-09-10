package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyResponse;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link ApiKeyManagementService} 响应映射与查活主体单测：
 * listKeys→toResponse 字段装配（当前/退役凭证判定、allowed 集合解析、
 * 主体现缺失 fail-closed），findActivePrincipal 的吊销/过期/缺凭证
 * 边界。
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyManagementServiceMappingTest {

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

    private RagApiPrincipal principal() {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId("prn-1");
        principal.setName("svc-key");
        principal.setRole(ApiKeyRole.NORMAL);
        principal.setAllowedCollectionIds("1,2");
        principal.setExpiresAt(LocalDateTime.now().plusDays(30));
        principal.setPolicyVersion(3L);
        principal.setRequestsPerMinute(120);
        principal.setCapabilities(null);
        principal.setCreatedAt(LocalDateTime.now().minusDays(1));
        principal.setUpdatedAt(LocalDateTime.now());
        principal.setLastUsedAt(LocalDateTime.now().minusMinutes(10));
        principal.setNextCredentialVersion(2);
        return principal;
    }

    private RagApiKey credential() {
        RagApiKey credential = new RagApiKey();
        credential.setKeyId("rag_sk_aaa");
        credential.setKeyHash("hash-1");
        credential.setPrincipalId("prn-1");
        credential.setCredentialVersion(1);
        credential.setName("svc-key");
        credential.setEnabled(true);
        credential.setRole(ApiKeyRole.NORMAL);
        credential.setAllowedCollectionIds("1,2");
        credential.setCreatedAt(LocalDateTime.now().minusDays(1));
        return credential;
    }

    @Test
    void listKeysMapsCredentialWithPrincipalDetails() {
        RagApiPrincipal principal = principal();
        RagApiKey credential = credential();
        when(credentialRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(credential));
        when(principalRepository.findByPrincipalId("prn-1"))
                .thenReturn(Optional.of(principal));
        Map<Long, String> keyMap = new java.util.LinkedHashMap<>();
        keyMap.put(1L, "kb-a");
        keyMap.put(2L, "kb-b");
        when(collectionIdentityResolver.mapKeys(List.of(1L, 2L)))
                .thenReturn(keyMap);

        List<ApiKeyResponse> responses = service.listKeys();

        assertEquals(1, responses.size());
        ApiKeyResponse response = responses.get(0);
        assertEquals("rag_sk_aaa", response.getKeyId());
        assertEquals("prn-1", response.getPrincipalId());
        assertEquals("svc-key", response.getName());
        assertEquals(1, response.getCredentialVersion());
        assertEquals(3L, response.getPolicyVersion());
        assertEquals(120, response.getRequestsPerMinute());
        assertEquals("NORMAL", response.getRole());
        // 当前启用且未退役且主体未吊销 → currentCredential。
        assertTrue(response.getCurrentCredential());
        assertFalse(response.getRetiringCredential());
        assertNull(response.getRetireAt());
        assertEquals(List.of(1L, 2L), response.getAllowedCollectionIds());
        assertEquals(List.of("kb-a", "kb-b"), response.getAllowedCollectionKeys());
        // NORMAL + 未持久化能力 → 归一化为全量能力。
        assertEquals(ApiCapabilitySupport.fullCapabilities(),
                response.getCapabilities());
        assertEquals(principal.getLastUsedAt(), response.getLastUsedAt());
    }

    @Test
    void listKeysMarksRetiringCredential() {
        RagApiKey credential = credential();
        credential.setRetireAt(LocalDateTime.now().plusSeconds(3600));
        when(credentialRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(credential));
        when(principalRepository.findByPrincipalId("prn-1"))
                .thenReturn(Optional.of(principal()));

        List<ApiKeyResponse> responses = service.listKeys();

        ApiKeyResponse response = responses.get(0);
        // 已安排退役 → 不再是当前凭证，但仍在退役窗口内。
        assertFalse(response.getCurrentCredential());
        assertTrue(response.getRetiringCredential());
        assertEquals(credential.getRetireAt(), response.getRetireAt());
        assertTrue(response.getEnabled());
    }

    @Test
    void listKeysFailsClosedWhenPrincipalMissing() {
        RagApiKey credential = credential();
        when(credentialRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(credential));
        when(principalRepository.findByPrincipalId("prn-1"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, service::listKeys);
    }

    @Test
    void findActivePrincipalReturnsMappedPrincipal() {
        RagApiPrincipal principal = principal();
        RagApiKey credential = credential();
        when(principalRepository.findByPrincipalId("prn-1"))
                .thenReturn(Optional.of(principal));
        when(credentialRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                "prn-1"))
                .thenReturn(Optional.of(credential));

        AuthenticatedApiPrincipal active = service.findActivePrincipal("prn-1");

        assertEquals("prn-1", active.getPrincipalId());
        assertEquals("rag_sk_aaa", active.getCredentialId());
        assertEquals(1, active.getCredentialVersion());
        assertEquals(ApiKeyRole.NORMAL, active.getRole());
        assertEquals("1,2", active.getAllowedCollectionIds());
        assertEquals(principal.getExpiresAt(), active.getExpiresAt());
        assertEquals(3L, active.getPolicyVersion());
        assertEquals(120, active.getRequestsPerMinute());
        assertEquals(ApiCapabilitySupport.fullCapabilities(),
                active.getCapabilities());
    }

    @Test
    void findActivePrincipalReturnsNullAtBoundaries() {
        // 主体不存在。
        when(principalRepository.findByPrincipalId("missing"))
                .thenReturn(Optional.empty());
        assertNull(service.findActivePrincipal("missing"));

        // 主体已吊销。
        RagApiPrincipal revoked = principal();
        revoked.setRevokedAt(LocalDateTime.now().minusMinutes(1));
        when(principalRepository.findByPrincipalId("revoked"))
                .thenReturn(Optional.of(revoked));
        assertNull(service.findActivePrincipal("revoked"));

        // 主体已过期。
        RagApiPrincipal expired = principal();
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(principalRepository.findByPrincipalId("expired"))
                .thenReturn(Optional.of(expired));
        assertNull(service.findActivePrincipal("expired"));

        // 无当前启用凭证（轮换空窗）。
        RagApiPrincipal principal = principal();
        when(principalRepository.findByPrincipalId("prn-1"))
                .thenReturn(Optional.of(principal));
        when(credentialRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                "prn-1"))
                .thenReturn(Optional.empty());
        assertNull(service.findActivePrincipal("prn-1"));
    }
}
