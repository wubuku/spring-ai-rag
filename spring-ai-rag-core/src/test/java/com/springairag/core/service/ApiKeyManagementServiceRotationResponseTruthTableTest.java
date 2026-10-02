package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyRotationResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.security.ApiAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 轮换响应里两个 3 项真值表的完整枚举（Batch 779，JaCoCo 行级驱动）。
 *
 * <p>{@code rotationResponse} 里的
 * {@code currentCredentialActive = current != null && revokedAt == null && !isExpired}
 * 与 {@code rotationPending = PENDING && expiresAt > now && retiring != null}
 * 各自有 8 种输入组合。客户端靠这两个布尔量决定"旧密钥还能不能用"、
 * "轮换窗口是否还开着"，漏掉任何一个组合都会让前端给出与后端相反的指引。
 *
 * <p>此前这两个真值表**一条组合都没有断言过**——既有的
 * {@code ApiKeyManagementServiceRotationResponseTest} 覆盖的是<strong>列表</strong>层的
 * {@code setCurrentCredentialActive(active)}，与轮换响应里的同名字段是两处不同的代码。
 */
class ApiKeyManagementServiceRotationResponseTruthTableTest {

    private static final String PRINCIPAL_ID = "principal-1";
    private static final String CURRENT_KEY = "rag_sk_current";
    private static final String RETIRING_KEY = "rag_sk_retiring";

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyManagementService service;
    private Method rotationResponse;
    private ApiKeyRotationOperation operation;

    @BeforeEach
    void setUp() throws Exception {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);

        operation = mock(ApiKeyRotationOperation.class);
        when(operation.getPrincipalId()).thenReturn(PRINCIPAL_ID);
        when(operation.getRotationId()).thenReturn(UUID.randomUUID());
        when(operation.getStatus()).thenReturn(ApiKeyRotationStatus.PENDING);
        when(operation.getExpiresAt())
                .thenReturn(LocalDateTime.now().plusMinutes(10));
        when(operation.getSourceCredentialId()).thenReturn("rag_sk_source");
        when(operation.getTargetCredentialId()).thenReturn("rag_sk_target");

        // requiredRotationCredentials 会先解析源/目标凭据，并要求目标版本严格大于源版本。
        when(apiKeyRepository.findByKeyId("rag_sk_source"))
                .thenReturn(Optional.of(key("rag_sk_source", 2)));
        when(apiKeyRepository.findByKeyId("rag_sk_target"))
                .thenReturn(Optional.of(key("rag_sk_target", 3)));

        service = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                mock(ApiKeyProvisioningOperationRepository.class),
                mock(ApiKeyRotationOperationRepository.class),
                new RagProperties(),
                mock(PlatformTransactionManager.class),
                mock(ApiPrincipalLifecycleEventPublisher.class));

        rotationResponse = ApiKeyManagementService.class.getDeclaredMethod(
                "rotationResponse", ApiKeyRotationOperation.class,
                String.class, boolean.class);
        rotationResponse.setAccessible(true);
    }

    private ApiKeyRotationResponse project(RagApiPrincipal principal,
                                           RagApiKey current,
                                           RagApiKey retiring,
                                           String rawKey,
                                           boolean replay) {
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.ofNullable(current));
        when(apiKeyRepository.findLiveRetiring(any(String.class), any(LocalDateTime.class)))
                .thenReturn(Optional.ofNullable(retiring));
        return invoke(rawKey, replay);
    }

    /**
     * 只做反射调用，不再打桩。
     *
     * <p>与 {@link #project} 分开，是因为后者会重新设置
     * {@code findByPrincipalId} 的桩，从而覆盖用例自己预设的返回值——
     * "主体不存在"这条用例正是靠预设 {@code Optional.empty()} 才能成立。
     */
    private ApiKeyRotationResponse invoke(String rawKey, boolean replay) {
        try {
            return (ApiKeyRotationResponse) rotationResponse.invoke(
                    service, operation, rawKey, replay);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private RagApiPrincipal principal(LocalDateTime expiresAt,
                                       LocalDateTime revokedAt) {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(PRINCIPAL_ID);
        principal.setName("Owner");
        principal.setRole(ApiKeyRole.NORMAL);
        principal.setCapabilities(
                com.springairag.core.security.ApiCapabilitySupport.FULL_SERIALIZED);
        principal.setPolicyVersion(1L);
        principal.setRequestsPerMinute(60);
        principal.setNextCredentialVersion(5);
        principal.setExpiresAt(expiresAt);
        principal.setRevokedAt(revokedAt);
        return principal;
    }

    private RagApiKey key(String keyId, int version) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId(PRINCIPAL_ID);
        key.setCredentialVersion(version);
        key.setEnabled(true);
        return key;
    }

    // ── currentCredentialActive 真值表 ──────────────────────────────────

    @Nested
    @DisplayName("currentCredentialActive：3 项条件的全部组合")
    class CurrentCredentialActive {

        @Test
        @DisplayName("有当前凭据 + 未吊销 + 未过期 → true")
        void allHealthyIsActive() {
            ApiKeyRotationResponse response = project(
                    principal(LocalDateTime.now().plusDays(30), null),
                    key(CURRENT_KEY, 3), null, "raw-1", false);

            assertEquals(Boolean.TRUE, response.getCurrentCredentialActive());
            assertEquals(CURRENT_KEY, response.getKeyId());
            assertEquals(3, response.getCredentialVersion());
        }

        @Test
        @DisplayName("没有当前凭据 → false（且不写入 keyId）")
        void noCurrentCredentialIsInactive() {
            ApiKeyRotationResponse response = project(
                    principal(LocalDateTime.now().plusDays(30), null),
                    null, null, "raw-1", false);

            assertEquals(Boolean.FALSE, response.getCurrentCredentialActive());
            assertNull(response.getKeyId(),
                    "没有当前凭据时不得凭空写出一个 keyId");
        }

        @Test
        @DisplayName("主体已吊销 → false")
        void revokedPrincipalIsInactive() {
            ApiKeyRotationResponse response = project(
                    principal(LocalDateTime.now().plusDays(30),
                            LocalDateTime.now().minusMinutes(1)),
                    key(CURRENT_KEY, 3), null, "raw-1", false);

            assertEquals(Boolean.FALSE, response.getCurrentCredentialActive(),
                    "已吊销主体的凭据不得被报告为可用");
        }

        @Test
        @DisplayName("主体已过期 → false")
        void expiredPrincipalIsInactive() {
            ApiKeyRotationResponse response = project(
                    principal(LocalDateTime.now().minusMinutes(1), null),
                    key(CURRENT_KEY, 3), null, "raw-1", false);

            assertEquals(Boolean.FALSE, response.getCurrentCredentialActive(),
                    "已过期主体的凭据不得被报告为可用");
        }

        @Test
        @DisplayName("同时吊销且过期 → false（短路顺序不影响结论）")
        void revokedAndExpiredIsInactive() {
            ApiKeyRotationResponse response = project(
                    principal(LocalDateTime.now().minusDays(1),
                            LocalDateTime.now().minusDays(2)),
                    key(CURRENT_KEY, 3), null, "raw-1", false);

            assertEquals(Boolean.FALSE, response.getCurrentCredentialActive());
        }
    }

    // ── rotationPending 真值表 ──────────────────────────────────────────

    @Nested
    @DisplayName("rotationPending：3 项条件的全部组合")
    class RotationPending {

        private final RagApiPrincipal healthy =
                principal(LocalDateTime.now().plusDays(30), null);

        @Test
        @DisplayName("PENDING + 未过期 + 有退役凭据 → true")
        void allTrueIsPending() {
            ApiKeyRotationResponse response = project(
                    healthy, key(CURRENT_KEY, 3),
                    key(RETIRING_KEY, 2), null, false);

            assertEquals(Boolean.TRUE, response.getRotationPending());
            assertEquals(RETIRING_KEY, response.getRetiringCredentialId());
            assertEquals(2, response.getRetiringCredentialVersion());
        }

        @Test
        @DisplayName("没有退役凭据 → false（窗口开着却没有可退役的旧密钥）")
        void noRetiringCredentialIsNotPending() {
            ApiKeyRotationResponse response = project(
                    healthy, key(CURRENT_KEY, 3), null, null, false);

            assertEquals(Boolean.FALSE, response.getRotationPending());
            assertNull(response.getRetiringCredentialId());
        }

        @Test
        @DisplayName("轮换窗口已过期 → false")
        void expiredOperationIsNotPending() {
            when(operation.getExpiresAt())
                    .thenReturn(LocalDateTime.now().minusMinutes(1));

            ApiKeyRotationResponse response = project(
                    healthy, key(CURRENT_KEY, 3),
                    key(RETIRING_KEY, 2), null, false);

            assertEquals(Boolean.FALSE, response.getRotationPending(),
                    "过期的轮换操作不得继续报告为“轮换进行中”");
        }

        @Test
        @DisplayName("操作状态不是 PENDING → false")
        void nonPendingStatusIsNotPending() {
            when(operation.getStatus()).thenReturn(ApiKeyRotationStatus.COMPLETED);

            ApiKeyRotationResponse response = project(
                    healthy, key(CURRENT_KEY, 3),
                    key(RETIRING_KEY, 2), null, false);

            assertEquals(Boolean.FALSE, response.getRotationPending());
            assertEquals("COMPLETED", response.getStatus());
        }
    }

    // ── rawKey / replay 投影 ───────────────────────────────────────────

    @Nested
    @DisplayName("rawKey 与 replay 投影")
    class SecretProjection {

        @Test
        @DisplayName("带 rawKey 时 secretAvailable 为 true 并原样带出")
        void rawKeyIsProjected() {
            ApiKeyRotationResponse response = project(
                    principal(LocalDateTime.now().plusDays(30), null),
                    key(CURRENT_KEY, 3), null, "sk_live_secret", false);

            assertEquals(Boolean.TRUE, response.getSecretAvailable());
            assertEquals("sk_live_secret", response.getRawKey());
            assertEquals(Boolean.FALSE, response.getIdempotentReplay());
        }

        @Test
        @DisplayName("不带 rawKey 时 secretAvailable 为 false 且 rawKey 为 null")
        void missingRawKeyIsNotAvailable() {
            ApiKeyRotationResponse response = project(
                    principal(LocalDateTime.now().plusDays(30), null),
                    key(CURRENT_KEY, 3), null, null, true);

            assertEquals(Boolean.FALSE, response.getSecretAvailable());
            assertNull(response.getRawKey());
            assertEquals(Boolean.TRUE, response.getIdempotentReplay(),
                    "重放响应必须标明自己是重放，否则客户端会以为拿到了新密钥");
        }

        @Test
        @DisplayName("轮换引用的主体不存在时快速失败")
        void missingPrincipalFailsFast() {
            when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                    .thenReturn(Optional.empty());

            when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                    .thenReturn(Optional.of(key(CURRENT_KEY, 3)));
            when(apiKeyRepository.findLiveRetiring(any(String.class), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            RagException error = assertThrows(RagException.class,
                    () -> invoke(null, false));

            assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
        }
    }
}
