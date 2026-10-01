package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.security.ApiCapabilitySupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 轮换授权的<b>放行侧</b>与凭据版本一致性守卫（Batch 772）。
 *
 * <p>勘察发现的两个空白，都是"只测了拒绝、没测放行"这一类：
 * <ol>
 *   <li>{@code authorizeRotation} 末尾的
 *       {@code prepare && !Objects.equals(prepareCredentialId, caller.getCredentialId())}
 *       守卫，JaCoCo 显示四个分支只覆盖两个：<b>拒绝侧有测试，放行侧没有</b>。
 *       也就是说 NORMAL 凭据用<b>自己的</b>密钥发起轮换——最日常的那条路——
 *       完全没有回归保护。把它改成永远拒绝，现有测试仍然全绿。</li>
 *   <li>另一侧 {@code prepare == false} 也没被走到：三个调用方
 *       （getRotation / completeRotation / cancelRotation）都传 false，
 *       而现有用例一律用 {@code caller = null, environmentRoot = true}
 *       走豁免分支，数据库 NORMAL 调用方一次都没进过。</li>
 *   <li>{@code requiredRotationCredentials} 的版本一致性守卫
 *       （"The rotation credential versions are inconsistent"）
 *       在<b>整个测试目录里 0 处引用</b>——包括 target 版本不比 source 新的
 *       这条最基本的数据完整性检查。</li>
 * </ol>
 */
class ApiKeyRotationAuthorizationAllowSideTest {

    private static final String PRINCIPAL_ID = "principal-1";
    private static final String CURRENT_KEY = "rag_sk_current";
    private static final String OTHER_KEY = "rag_sk_other";
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
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));

        operation = mock(ApiKeyRotationOperation.class);
        when(operation.getPrincipalId()).thenReturn(PRINCIPAL_ID);
        when(operation.getRotationId()).thenReturn(UUID.randomUUID());
        when(operation.getExpiresAt())
                .thenReturn(LocalDateTime.now().plusMinutes(10));
        AtomicReference<ApiKeyRotationStatus> status =
                new AtomicReference<>(ApiKeyRotationStatus.PENDING);
        when(operation.getStatus()).thenAnswer(invocation -> status.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            status.set(invocation.getArgument(0));
            return null;
        }).when(operation).setStatus(any());
        when(operation.getSourceCredentialId()).thenReturn(SOURCE_KEY);
        when(operation.getTargetCredentialId()).thenReturn(TARGET_KEY);
        when(operation.getRequestFingerprintSha256()).thenReturn("fp-1");
        when(rotationOperationRepository.findById(any(UUID.class)))
                .thenReturn(Optional.of(operation));
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                eq(PRINCIPAL_ID), eq(ApiKeyRotationStatus.PENDING)))
                .thenReturn(Optional.empty());
        when(rotationOperationRepository
                .findByPrincipalIdAndIdempotencyKeyHash(
                        eq(PRINCIPAL_ID), anyString()))
                .thenReturn(Optional.empty());

        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(activePrincipal()));
        when(apiKeyRepository.findByKeyId(SOURCE_KEY))
                .thenReturn(Optional.of(credential(SOURCE_KEY, 1, true)));
        when(apiKeyRepository.findByKeyId(TARGET_KEY))
                .thenReturn(Optional.of(credential(TARGET_KEY, 2, false)));
        lenient().when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                PRINCIPAL_ID))
                .thenReturn(Optional.of(credential(CURRENT_KEY, 2, true)));
        lenient().when(apiKeyRepository.findLiveRetiring(
                eq(PRINCIPAL_ID), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        lenient().when(apiKeyRepository.disableByKeyId(
                anyString(), any(LocalDateTime.class))).thenReturn(1);
        lenient().when(apiKeyRepository.saveAndFlush(any(RagApiKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(principalRepository.saveAndFlush(
                any(RagApiPrincipal.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(rotationOperationRepository.saveAndFlush(
                any(ApiKeyRotationOperation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                mock(ApiKeyProvisioningOperationRepository.class),
                rotationOperationRepository,
                new RagProperties(),
                transactionManager,
                mock(ApiPrincipalLifecycleEventPublisher.class));
    }

    private RagApiPrincipal activePrincipal() {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(PRINCIPAL_ID);
        principal.setName("Owner");
        principal.setRole(ApiKeyRole.NORMAL);
        principal.setCapabilities(ApiCapabilitySupport.FULL_SERIALIZED);
        principal.setPolicyVersion(1L);
        principal.setRequestsPerMinute(60);
        principal.setNextCredentialVersion(5);
        return principal;
    }

    private RagApiKey credential(String keyId, Integer version, boolean enabled) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId(PRINCIPAL_ID);
        key.setCredentialVersion(version);
        key.setEnabled(enabled);
        return key;
    }

    /** 数据库托管的 NORMAL 调用方。 */
    private ApiAccessPolicy normalCaller(String credentialId) {
        return new ApiAccessPolicy() {
            @Override public String getPrincipalId() { return PRINCIPAL_ID; }
            @Override public String getCredentialId() { return credentialId; }
            @Override public ApiKeyRole getRole() { return ApiKeyRole.NORMAL; }
            @Override public String getAllowedCollectionIds() { return null; }
            @Override public LocalDateTime getExpiresAt() { return null; }
        };
    }

    // ── 放行侧：NORMAL 用自己的密钥发起轮换 ──────────────────────────

    @Test
    void normalOwnerMayPrepareRotationFromItsOwnCredential() {
        // 这是最日常的一条路：NORMAL 凭据轮换自己的密钥。
        // 此前只有"prepare 必须来自当前凭证"的拒绝用例，放行侧一个都没有——
        // 把守卫改成永远拒绝，测试仍然全绿。
        //
        // prepare 会在内部新建 target 凭据并立刻组装响应，所以这里用
        // "按调用顺序递增版本"的通用打桩：requiredRotationCredentials 先取
        // source 再取 target，递增即可保证 target 版本严格更新。
        java.util.concurrent.atomic.AtomicInteger versions =
                new java.util.concurrent.atomic.AtomicInteger();
        when(apiKeyRepository.findByKeyId(anyString())).thenAnswer(invocation -> {
            String keyId = invocation.getArgument(0);
            return Optional.of(credential(keyId, versions.incrementAndGet(), true));
        });

        assertNotNull(
                service.prepareRotation(
                        CURRENT_KEY, null, "hash-allow", normalCaller(CURRENT_KEY), false),
                "NORMAL 主体用自己的凭证发起轮换必须被放行");
    }

    // ── prepare == false 侧：get / cancel 用的是另一个凭证 id ─────────

    @Test
    void normalOwnerMayReadRotationWithADifferentCredentialId() {
        // getRotation 传 prepare=false 且 prepareCredentialId=null，
        // 调用方凭证 id 与当前密钥不同也不该被 prepare 守卫误伤。
        var response = service.getRotation(
                UUID.randomUUID(), normalCaller(OTHER_KEY), false);

        assertEquals("PENDING", response.getStatus());
    }

    @Test
    void normalOwnerMayCancelRotationWithADifferentCredentialId() {
        // cancel 同样传 prepare=false；target 仍是启用态时会被禁用。
        RagApiKey target = credential(TARGET_KEY, 2, true);
        when(apiKeyRepository.findByKeyId(TARGET_KEY))
                .thenReturn(Optional.of(target));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(
                PRINCIPAL_ID)).thenReturn(Optional.of(target));

        var response = service.cancelRotation(
                UUID.randomUUID(), normalCaller(OTHER_KEY), false);

        assertEquals("CANCELED", response.getStatus());
    }

    // ── 凭据版本一致性守卫（此前全目录 0 处引用） ────────────────────

    @Test
    void rotationIsRejectedWhenSourceCredentialVersionIsMissing() {
        when(apiKeyRepository.findByKeyId(SOURCE_KEY))
                .thenReturn(Optional.of(credential(SOURCE_KEY, null, true)));

        RagException error = assertThrows(RagException.class,
                () -> service.completeRotation(
                        UUID.randomUUID(), null, true));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
        assertTrue(error.getMessage()
                .contains("credential versions are inconsistent"),
                () -> "unexpected message: " + error.getMessage());
    }

    @Test
    void rotationIsRejectedWhenTargetCredentialVersionIsMissing() {
        when(apiKeyRepository.findByKeyId(TARGET_KEY))
                .thenReturn(Optional.of(credential(TARGET_KEY, null, false)));

        RagException error = assertThrows(RagException.class,
                () -> service.completeRotation(
                        UUID.randomUUID(), null, true));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
        assertTrue(error.getMessage()
                .contains("credential versions are inconsistent"),
                () -> "unexpected message: " + error.getMessage());
    }

    @Test
    void rotationIsRejectedWhenTargetVersionEqualsSourceVersion() {
        // 版本相等意味着轮转没有推进：完成它会让两个凭据版本撞车。
        when(apiKeyRepository.findByKeyId(SOURCE_KEY))
                .thenReturn(Optional.of(credential(SOURCE_KEY, 3, true)));
        when(apiKeyRepository.findByKeyId(TARGET_KEY))
                .thenReturn(Optional.of(credential(TARGET_KEY, 3, false)));

        RagException error = assertThrows(RagException.class,
                () -> service.completeRotation(
                        UUID.randomUUID(), null, true));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
    }

    @Test
    void rotationIsRejectedWhenTargetVersionIsOlderThanSourceVersion() {
        when(apiKeyRepository.findByKeyId(SOURCE_KEY))
                .thenReturn(Optional.of(credential(SOURCE_KEY, 5, true)));
        when(apiKeyRepository.findByKeyId(TARGET_KEY))
                .thenReturn(Optional.of(credential(TARGET_KEY, 4, false)));

        RagException error = assertThrows(RagException.class,
                () -> service.completeRotation(
                        UUID.randomUUID(), null, true));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
    }

    // ── 对照组：版本确实推进时必须放行，否则上面几条就成了空转 ──────

    @Test
    void rotationCompletesWhenTargetVersionIsStrictlyNewer() {
        var response = service.completeRotation(UUID.randomUUID(), null, true);

        assertEquals("COMPLETED", response.getStatus());
    }
}
