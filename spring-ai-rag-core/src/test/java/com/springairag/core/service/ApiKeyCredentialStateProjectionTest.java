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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 凭据状态投影真值表（Batch 773）。
 *
 * <p>{@code toResponse} 用两组三/四条件布尔，向客户端声明
 * "这是当前凭证"与"这是退役中的凭证"：
 * <pre>
 * current  = enabled &amp;&amp; retireAt == null   &amp;&amp; revokedAt == null
 * retiring = enabled &amp;&amp; retireAt != null   &amp;&amp; retireAt.isAfter(now)
 *                        &amp;&amp; revokedAt == null
 * </pre>
 *
 * <p>JaCoCo 显示这两行共 5 个分支从未被走过：禁用凭证、已吊销主体、
 * 退役时间已过——也就是**客户端据此判断"我的密钥还有效吗"的三种否定答案**
 * 全都没被验证过。此前只测了肯定答案。
 *
 * <p>本类用 {@code listKeys()} 驱动（它内部 {@code .map(this::toResponse)}），
 * 逐格覆盖真值表，并保证每条否定路径都有一个专门用例。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiKeyCredentialStateProjectionTest {

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
        when(collectionIdentityResolver.mapKeys(anyList()))
                .thenReturn(Map.of());
    }

    private RagApiPrincipal principal(boolean revoked) {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId("prn-1");
        principal.setName("svc-key");
        principal.setRole(ApiKeyRole.NORMAL);
        principal.setPolicyVersion(3L);
        principal.setRequestsPerMinute(120);
        principal.setCreatedAt(LocalDateTime.now().minusDays(1));
        principal.setUpdatedAt(LocalDateTime.now());
        if (revoked) {
            principal.setRevokedAt(LocalDateTime.now().minusHours(1));
        }
        return principal;
    }

    /** retireAt 传 null 表示"未安排退役"。 */
    private RagApiKey credential(boolean enabled, LocalDateTime retireAt) {
        RagApiKey credential = new RagApiKey();
        credential.setKeyId("rag_sk_aaa");
        credential.setPrincipalId("prn-1");
        credential.setCredentialVersion(1);
        credential.setEnabled(enabled);
        credential.setRetireAt(retireAt);
        credential.setCreatedAt(LocalDateTime.now().minusDays(1));
        return credential;
    }

    private ApiKeyResponse project(boolean enabled,
                                   LocalDateTime retireAt,
                                   boolean revoked) {
        when(credentialRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(credential(enabled, retireAt)));
        when(principalRepository.findByPrincipalId("prn-1"))
                .thenReturn(Optional.of(principal(revoked)));
        return service.listKeys().get(0);
    }

    // ── currentCredential ────────────────────────────────────────────

    @Test
    void enabledCredentialWithoutRetireAtIsCurrent() {
        ApiKeyResponse response = project(true, null, false);

        assertTrue(response.getCurrentCredential());
        assertFalse(response.getRetiringCredential());
    }

    @Test
    void disabledCredentialIsNotCurrent() {
        // 否定答案之一：密钥已被禁用。此前无任何用例。
        ApiKeyResponse response = project(false, null, false);

        assertFalse(response.getCurrentCredential(),
                "禁用的凭据绝不能被声明为当前凭证");
    }

    @Test
    void revokedPrincipalHidesCurrentCredential() {
        // 否定答案之二：主体已吊销，即便密钥本身启用也不算当前凭证。
        ApiKeyResponse response = project(true, null, true);

        assertFalse(response.getCurrentCredential(),
                "主体已吊销时不得声明当前凭证");
    }

    @Test
    void credentialWithRetireAtIsNotCurrent() {
        // 退役中的凭据已经让位给新密钥，不该同时被标成"当前"。
        ApiKeyResponse response =
                project(true, LocalDateTime.now().plusMinutes(5), false);

        assertFalse(response.getCurrentCredential());
        assertTrue(response.getRetiringCredential());
    }

    // ── retiringCredential ───────────────────────────────────────────

    @Test
    void enabledCredentialWithFutureRetireAtIsRetiring() {
        ApiKeyResponse response =
                project(true, LocalDateTime.now().plusMinutes(5), false);

        assertTrue(response.getRetiringCredential());
    }

    @Test
    void disabledCredentialIsNotRetiring() {
        // 否定答案之三：禁用且已安排退役。两个标记都必须是 false。
        ApiKeyResponse response =
                project(false, LocalDateTime.now().plusMinutes(5), false);

        assertFalse(response.getRetiringCredential(),
                "禁用的凭据不应被声明为退役中");
    }

    @Test
    void elapsedRetireAtIsNotRetiring() {
        // 否定答案之四：重叠窗口已经结束，退役实际已完成。
        // 用 isAfter 判定"仍处于退役中"，过期的 retireAt 必须判 false。
        ApiKeyResponse response =
                project(true, LocalDateTime.now().minusMinutes(1), false);

        assertFalse(response.getRetiringCredential(),
                "退役时间已过的凭据不应仍被标记为退役中");
    }

    @Test
    void revokedPrincipalHidesRetiringCredential() {
        ApiKeyResponse response =
                project(true, LocalDateTime.now().plusMinutes(5), true);

        assertFalse(response.getRetiringCredential(),
                "主体已吊销时不得声明退役中的凭据");
    }

    @Test
    void credentialWithoutRetireAtIsNotRetiring() {
        ApiKeyResponse response = project(true, null, false);

        assertFalse(response.getRetiringCredential());
    }
}
