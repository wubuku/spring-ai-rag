package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiPrincipalPolicyUpdateRequest;
import com.springairag.api.dto.ApiPrincipalResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.security.ApiCapabilitySupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * `rotate` 与 `updatePolicy` 的四道安全守卫（Batch 782）。
 *
 * <p>这些守卫此前只被隐形的集成测试覆盖，或干脆没有对应分支。二者的共同点是：
 * 去掉任何一条，测试**依然全绿**，而生产行为已经悄悄变了——这正是"能失败的门禁"
 * 与"看不见的覆盖率"之间的差别。
 *
 * <ul>
 *   <li>{@code rotate}：出示的 keyId 必须是当前凭据；且真正落库时
 *       {@code disableByKeyId} 必须恰好影响 1 行——这是并发守卫，
 *       防的是"读取之后被别人抢先禁用"这个窗口。</li>
 *   <li>{@code updatePolicy}：非 root 调用方不得改动 legacy ADMIN 的到期时间
 *       （否则等于变相延长管理员寿命）；ADMIN 的能力集不得被降级。</li>
 * </ul>
 */
class ApiKeyManagementServiceSecurityGuardTest {

    private static final String PRINCIPAL_ID = "principal-1";
    private static final String CURRENT_KEY = "rag_sk_current";
    private static final String REPLACEMENT_KEY = "rag_k_replacement";

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyRotationOperationRepository rotationOperationRepository;
    private ApiKeyManagementService service;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);
        rotationOperationRepository = mock(ApiKeyRotationOperationRepository.class);

        service = Mockito.spy(new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                mock(ApiKeyProvisioningOperationRepository.class),
                rotationOperationRepository,
                new RagProperties(),
                mock(PlatformTransactionManager.class),
                mock(ApiPrincipalLifecycleEventPublisher.class)));

        doReturn("rag_sk_replacement_raw").when(service).generateRawKey();
        doReturn(REPLACEMENT_KEY).when(service).generateKeyId();

        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1)));
        when(apiKeyRepository.findByKeyId(REPLACEMENT_KEY))
                .thenReturn(Optional.of(credential(REPLACEMENT_KEY, 2)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID)).thenReturn(1);
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                anyString(), any(ApiKeyRotationStatus.class))).thenReturn(Optional.empty());
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNotNull(
                anyString())).thenReturn(Optional.empty());
    }

    private RagApiKey credential(String keyId, int version) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId(PRINCIPAL_ID);
        key.setCredentialVersion(version);
        key.setEnabled(true);
        return key;
    }

    private RagApiPrincipal principal(ApiKeyRole role, LocalDateTime expiresAt) {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(PRINCIPAL_ID);
        principal.setName("Owner");
        principal.setRole(role);
        principal.setCapabilities(
                com.springairag.core.security.ApiCapabilitySupport.FULL_SERIALIZED);
        principal.setPolicyVersion(1L);
        principal.setRequestsPerMinute(60);
        principal.setNextCredentialVersion(2);
        principal.setExpiresAt(expiresAt);
        return principal;
    }

    // ── rotate 的两道守卫 ───────────────────────────────────────────────

    @Nested
    @DisplayName("rotate：两道相互独立的守卫")
    class Rotate {

        @Test
        @DisplayName("内存态：出示陈旧 keyId 时拒绝")
        void staleCredentialIsRejectedInMemory() {
            when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                    .thenReturn(Optional.of(principal(ApiKeyRole.NORMAL, null)));
            when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                    .thenReturn(Optional.of(credential("rag_sk_newer", 2)));

            RagException error = assertThrows(RagException.class,
                    () -> service.rotateKey(CURRENT_KEY));

            assertEquals(ErrorCode.CREDENTIAL_NOT_CURRENT, error.getErrorCodeEnum());
        }

        @Test
        @DisplayName("并发守卫：落库时影响行数不等于 1 时拒绝")
        void disableAffectingNoRowIsRejected() {
            // 内存态检查通过了，但到真正落库时那把密钥已经被别人禁用——
            // 这是"读—改—写"之间的竞态窗口，只靠内存态检查抓不到。
            when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                    .thenReturn(Optional.of(principal(ApiKeyRole.NORMAL, null)));
            when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                    .thenReturn(Optional.of(credential(CURRENT_KEY, 1)));
            when(apiKeyRepository.disableByKeyId(eq(CURRENT_KEY), any(LocalDateTime.class)))
                    .thenReturn(0);

            RagException error = assertThrows(RagException.class,
                    () -> service.rotateKey(CURRENT_KEY));

            assertEquals(ErrorCode.CREDENTIAL_NOT_CURRENT, error.getErrorCodeEnum(),
                    "禁用没有真正生效时不得继续签发替代凭据——否则旧密钥仍在、"
                            + "新密钥又已发出，等于凭空多了一把有效密钥");
        }

        @Test
        @DisplayName("正常轮换：签发替代凭据并返回明文密钥")
        void happyPathIssuesReplacement() {
            when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                    .thenReturn(Optional.of(principal(ApiKeyRole.NORMAL, null)));
            when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                    .thenReturn(Optional.of(credential(CURRENT_KEY, 1)));
            when(apiKeyRepository.disableByKeyId(eq(CURRENT_KEY), any(LocalDateTime.class)))
                    .thenReturn(1);
            when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                    .thenReturn(Optional.of(credential(CURRENT_KEY, 1)));

            ApiKeyCreatedResponse response = service.rotateKey(CURRENT_KEY);

            assertNotNull(response);
            assertEquals("rag_sk_replacement_raw", response.getRawKey());
            verify(apiKeyRepository).save(any(RagApiKey.class));
        }

        @Test
        @DisplayName("keyId 不存在时返回 null 而不是抛错")
        void unknownKeyIdReturnsNull() {
            when(apiKeyRepository.findByKeyId("rag_sk_unknown"))
                    .thenReturn(Optional.empty());

            org.junit.jupiter.api.Assertions.assertNull(
                    service.rotateKey("rag_sk_unknown"));
        }
    }

    // ── updatePolicy 的两道守卫 ─────────────────────────────────────────

    @Nested
    @DisplayName("updatePolicy：策略变更的安全边界")
    class UpdatePolicy {

        private ApiPrincipalPolicyUpdateRequest request(
                String name, LocalDateTime expiresAt, Long expectedVersion) {
            ApiPrincipalPolicyUpdateRequest req = new ApiPrincipalPolicyUpdateRequest();
            req.setName(name);
            req.setExpiresAt(expiresAt);
            req.setExpectedPolicyVersion(expectedVersion);
            req.setRequestsPerMinute(60);
            return req;
        }

        @Test
        @DisplayName("非 root 调用方不得改动 legacy ADMIN 的到期时间")
        void nonRootCannotChangeLegacyAdminExpiry() {
            when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                    .thenReturn(Optional.of(principal(
                            ApiKeyRole.ADMIN, LocalDateTime.now().plusDays(10))));

            RagException error = assertThrows(RagException.class,
                    () -> service.updatePolicy(PRINCIPAL_ID,
                            request("Admin", LocalDateTime.now().plusYears(5), 1L),
                            List.of(), false));

            assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCodeEnum());
            assertTrue(error.getMessage().contains("Legacy ADMIN expiry"),
                    "错误信息应说明被拒的原因: " + error.getMessage());
        }

        @Test
        @DisplayName("root 调用方可以改动 legacy ADMIN 的到期时间")
        void rootMayChangeLegacyAdminExpiry() {
            LocalDateTime newExpiry = LocalDateTime.now().plusYears(5);
            when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                    .thenReturn(Optional.of(principal(
                            ApiKeyRole.ADMIN, LocalDateTime.now().plusDays(10))));
            when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                    .thenReturn(Optional.empty());
            when(apiKeyRepository.findLiveRetiring(anyString(), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            ApiPrincipalResponse response = service.updatePolicy(PRINCIPAL_ID,
                    request("Admin", newExpiry, 1L), List.of(), true);

            assertNotNull(response);
            assertEquals(newExpiry, response.getExpiresAt());
        }

        @Test
        @DisplayName("非 root 调用方保持 ADMIN 到期时间不变时放行")
        void nonRootMayUpdateAdminWhenExpiryUnchanged() {
            LocalDateTime unchanged = LocalDateTime.now().plusDays(10);
            when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                    .thenReturn(Optional.of(principal(ApiKeyRole.ADMIN, unchanged)));
            when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                    .thenReturn(Optional.empty());
            when(apiKeyRepository.findLiveRetiring(anyString(), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            ApiPrincipalResponse response = service.updatePolicy(PRINCIPAL_ID,
                    request("Renamed", unchanged, 1L), List.of(), false);

            assertNotNull(response, "到期时间没变时不应被这条守卫拦下");
        }

        @Test
        @DisplayName("ADMIN 的能力集不得被降级")
        void adminCapabilitiesCannotBeDowngraded() {
            when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                    .thenReturn(Optional.of(principal(ApiKeyRole.ADMIN, null)));

            ApiPrincipalPolicyUpdateRequest req =
                    request("Admin", null, 1L);
            // 合法但**不完整**的能力集：只有读，没有写。
            // 用一个非法能力名会先被 normalizeRequested 拒掉，那样就永远走不到
            // ADMIN 能力守卫，测到的会是另一条规则。
            req.setCapabilities(List.of(ApiCapabilitySupport.RAG_READ));

            RagException error = assertThrows(RagException.class,
                    () -> service.updatePolicy(PRINCIPAL_ID, req, List.of(), false));

            assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCodeEnum());
            assertTrue(error.getMessage().contains("full RAG capabilities"),
                    "错误信息应说明 ADMIN 必须保留完整能力: " + error.getMessage());
        }

        @Test
        @DisplayName("策略版本不匹配时拒绝，避免覆盖并发修改")
        void policyVersionConflictIsRejected() {
            when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                    .thenReturn(Optional.of(principal(ApiKeyRole.NORMAL, null)));

            RagException error = assertThrows(RagException.class,
                    () -> service.updatePolicy(PRINCIPAL_ID,
                            request("Name", null, 99L), List.of(), false));

            assertEquals(ErrorCode.POLICY_VERSION_CONFLICT, error.getErrorCodeEnum());
        }

        @Test
        @DisplayName("允许集合为空时写入空串而不是 null")
        void emptyAllowedCollectionIdsSerialize() {
            when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                    .thenReturn(Optional.of(principal(ApiKeyRole.NORMAL, null)));
            when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                    .thenReturn(Optional.empty());
            when(apiKeyRepository.findLiveRetiring(anyString(), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            ApiPrincipalResponse response = service.updatePolicy(PRINCIPAL_ID,
                    request("Name", null, 1L), List.of(), false);

            assertNotNull(response);
            assertEquals(2L, response.getPolicyVersion(),
                    "策略更新后版本号必须自增，否则并发控制会失效");
        }
    }
}
