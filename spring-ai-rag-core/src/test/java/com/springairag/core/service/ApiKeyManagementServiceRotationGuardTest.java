package com.springairag.core.service;

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
import com.springairag.core.security.ApiAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code prepareRotation} 的两个安全守卫（Batch 781）。
 *
 * <p>这两个守卫此前**只由 {@code ManagedApiPrincipalPostgresIntegrationTest} 覆盖**，
 * 而那个类在 Batch 780 之前一直处于"隐形"状态——既不执行、也不计入跳过。
 * 也就是说，整个默认测试流程里，"只能用当前持有的密钥发起轮换"这条安全规则
 * 从来没有被验证过。
 *
 * <p><strong>不可达分支如实登记，不编造测试</strong>：
 * {@code if (!deadline.isAfter(now))} 抛 {@code PRINCIPAL_NOT_ACTIVE} 看似可达，
 * 实则不可达——{@code ensureActive(principal)} 在它之前执行，而后者对
 * {@code expiresAt <= now} 已经先抛了同一个错误码。要让该分支触发，只能让主体
 * 在两次 {@code LocalDateTime.now()} 之间恰好过期，即亚毫秒级竞态。
 * 它是一道冗余的纵深防御，不是一条可以被测试固定的规则。
 * 本批真正要钉住的是它<strong>前面</strong>那条：重叠窗口必须被主体到期时间钳制。
 */
class ApiKeyManagementServiceRotationGuardTest {

    private static final String PRINCIPAL_ID = "principal-1";
    private static final String CURRENT_KEY = "rag_sk_current";
    private static final String IDEMPOTENCY = "idem-hash-1";

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

        // generateRawKey/generateKeyId 是包级可见，固定下来让断言可复现。
        doReturn("rag_sk_generated_raw").when(service).generateRawKey();
        doReturn("rag_k_generated").when(service).generateKeyId();

        // 通用前置：凭据存在、写锁拿到、无幂等重放、无待处理轮换、无待清理退役凭据。
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1)));
        // 轮换成功后返回响应时会把源/目标凭据重新解析一遍；目标凭据是刚生成的，
        // 它的版本必须严格大于源凭据，否则 requiredRotationCredentials 会拒绝。
        when(apiKeyRepository.findByKeyId("rag_k_generated"))
                .thenReturn(Optional.of(credential("rag_k_generated", 2)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID)).thenReturn(1);
        when(rotationOperationRepository.findByPrincipalIdAndIdempotencyKeyHash(
                anyString(), anyString())).thenReturn(Optional.empty());
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                anyString(), any(ApiKeyRotationStatus.class))).thenReturn(Optional.empty());
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNotNull(
                anyString())).thenReturn(Optional.empty());
    }

    private ApiAccessPolicy adminCaller() {
        ApiAccessPolicy policy = mock(ApiAccessPolicy.class);
        when(policy.getPrincipalId()).thenReturn("admin-1");
        when(policy.getRole()).thenReturn(ApiKeyRole.ADMIN);
        when(policy.getCredentialId()).thenReturn("admin-key");
        return policy;
    }

    private RagApiPrincipal principal(LocalDateTime expiresAt) {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(PRINCIPAL_ID);
        principal.setName("Owner");
        principal.setRole(ApiKeyRole.NORMAL);
        principal.setCapabilities(
                com.springairag.core.security.ApiCapabilitySupport.FULL_SERIALIZED);
        principal.setPolicyVersion(1L);
        principal.setRequestsPerMinute(60);
        principal.setNextCredentialVersion(2);
        principal.setExpiresAt(expiresAt);
        return principal;
    }

    private RagApiKey credential(String keyId, int version) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId(PRINCIPAL_ID);
        key.setCredentialVersion(version);
        key.setEnabled(true);
        return key;
    }

    // ── 守卫一：只能用当前持有的密钥发起轮换 ────────────────────────────

    @Test
    @DisplayName("出示一个不是当前凭据的 keyId 时拒绝轮换")
    void staleCredentialIsRejected() {
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal(LocalDateTime.now().plusDays(30))));
        // 当前凭据是另一把密钥：调用方拿着一个陈旧 keyId。
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(credential("rag_sk_newer", 2)));

        RagException error = assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 60, IDEMPOTENCY, adminCaller(), false));

        assertEquals(ErrorCode.CREDENTIAL_NOT_CURRENT, error.getErrorCodeEnum(),
                "持有陈旧密钥的调用方不得发起轮换——否则轮换窗口会绑在错误的凭据上");
    }

    @Test
    @DisplayName("主体当前没有任何启用凭据时拒绝轮换")
    void missingCurrentCredentialIsRejected() {
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal(LocalDateTime.now().plusDays(30))));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 60, IDEMPOTENCY, adminCaller(), false));

        assertEquals(ErrorCode.CREDENTIAL_NOT_CURRENT, error.getErrorCodeEnum());
    }

    // ── 守卫二：轮换重叠窗口不得比主体活得更久 ──────────────────────────

    @Test
    @DisplayName("主体先于重叠窗口到期时，轮换截止时间被钳制到主体到期时间")
    void overlapDeadlineIsClampedToPrincipalExpiry() {
        LocalDateTime principalExpiry = LocalDateTime.now().plusSeconds(20);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal(principalExpiry)));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1)));

        ApiKeyManagementService.RotationResult result = service.prepareRotation(
                CURRENT_KEY, 3600, IDEMPOTENCY, adminCaller(), false);

        assertNotNull(result);
        LocalDateTime expiry = result.response().getRotationExpiresAt();
        assertTrue(expiry.isBefore(LocalDateTime.now().plusSeconds(3600)),
                "重叠窗口不得比主体活得更久，实际到期时间: " + expiry);
        // 钳制是精确的：截止时间就是主体的到期时间，而不是一个近似值。
        assertTrue(Math.abs(java.time.Duration.between(
                        principalExpiry, expiry).toSeconds()) <= 1,
                "截止时间应等于主体到期时间 " + principalExpiry + "，实际 " + expiry);
    }

    @Test
    @DisplayName("主体到期时间远晚于重叠窗口时，按请求的重叠秒数计算")
    void normalOverlapUsesRequestedSeconds() {
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal(LocalDateTime.now().plusDays(30))));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1)));

        LocalDateTime before = LocalDateTime.now();
        ApiKeyManagementService.RotationResult result = service.prepareRotation(
                CURRENT_KEY, 120, IDEMPOTENCY, adminCaller(), false);

        LocalDateTime expiry = result.response().getRotationExpiresAt();
        long actualSeconds = java.time.Duration.between(before, expiry).toSeconds();
        assertTrue(actualSeconds > 110 && actualSeconds <= 120,
                "应按请求的 120 秒重叠窗口计算，实际: " + actualSeconds + " 秒");
    }

    @Test
    @DisplayName("重叠秒数越界时直接拒绝，而不是悄悄改用默认值")
    void outOfRangeOverlapIsRejected() {
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal(LocalDateTime.now().plusDays(30))));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1)));

        assertThrows(IllegalArgumentException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 0, IDEMPOTENCY, adminCaller(), false));
    }

    @Test
    @DisplayName("已过期主体在更早的 ensureActive 处被拒绝")
    void expiredPrincipalIsRejectedBeforeDeadlineMath() {
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal(LocalDateTime.now().minusMinutes(1))));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 1)));

        RagException error = assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 60, IDEMPOTENCY, adminCaller(), false));

        assertEquals(ErrorCode.PRINCIPAL_NOT_ACTIVE, error.getErrorCodeEnum());
        // 注意：正是这条路径让后面那道 "!deadline.isAfter(now)" 守卫变得不可达。
        assertEquals("API principal is revoked or expired", error.getMessage(),
                "过期主体应止于 ensureActive，而非后面那道冗余的截止时间守卫");
    }

    @Test
    @DisplayName("Idempotency-Key 为空白时直接拒绝")
    void blankIdempotencyKeyIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 60, "  ", adminCaller(), false));

        assertEquals("Idempotency-Key is required", error.getMessage());
    }
}
