package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyRotationResponse;
import com.springairag.api.dto.ApiPrincipalPolicyUpdateRequest;
import com.springairag.api.dto.ApiPrincipalResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRotationStatus;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.service.ApiKeyManagementService.ProvisioningResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApiKeyManagementService} 剩余的未覆盖分支（Batch 787）。
 *
 * <p>这些分支此前只被 {@code ManagedApiPrincipalPostgresIntegrationTest} 覆盖，
 * 而那个类需要 Docker 且默认被跳过（Batch 780 才发现它一直隐形）。
 * 也就是说，<strong>默认测试流程里这些安全边界从来没有被执行过</strong>。
 *
 * <p>本类只覆盖**真正可达**的分支。三处如实记下，不为它们编造覆盖，
 * 也不动生产代码来凑数字：
 * <ul>
 *   <li>{@code prepareRotation} 里 {@code !deadline.isAfter(now)}：其上一步
 *       {@code ensureActive(principal)} 已用同一个错误码先抛（Batch 781 已确认）。</li>
 *   <li>{@code sha256} 里的 {@code NoSuchAlgorithmException}：JDK 必然提供 SHA-256。</li>
 *   <li>{@code createdResponse} 里的 {@code rawKey != null}：两个调用点
 *       （{@code createPrincipal} 与 {@code rotate}）传的都是刚生成的明文密钥，
 *       且它在校验之前已被 {@code sha256} 消费过，{@code null} 会先在
 *       {@code sha256} 里炸掉，根本到不了这里。</li>
 *   <li>{@code cleanupExpiredRotationForPrincipal} 里的
 *       {@code if (retiring != null)}：{@code retiring} 是 Spring Data 返回的
 *       {@link java.util.Optional}，生产上**永不为 null**，所以这个判断恒为真——
 *       它不是 null 保护，却长得像 null 保护，会误导读者。
 *       唯一的"覆盖"方式是让 mock 返回 {@code null}，那测的是 Mockito 的行为，
 *       不是生产行为，属于本仓库明令禁止的凑数测试。</li>
 * </ul>
 */
class ApiKeyManagementServiceRemainingBranchTest {

    private static final String PRINCIPAL_ID = "principal-1";
    private static final String CURRENT_KEY = "rag_sk_current";
    private static final String OWNER_ID = "owner-1";

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyRotationOperationRepository rotationOperationRepository;
    private ApiKeyProvisioningOperationRepository provisioningOperationRepository;
    private ApiKeyManagementService service;

    /**
     * @param identityResolver 可为 {@code null}，用于钉住"未装配该协作者"的行为
     * @param lifecyclePublisher 可为 {@code null}，同上
     */
    private ApiKeyManagementService newService(
            CollectionIdentityResolver identityResolver,
            ApiPrincipalLifecycleEventPublisher lifecyclePublisher) {
        return Mockito.spy(new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                identityResolver,
                mock(JdbcTemplate.class),
                provisioningOperationRepository,
                rotationOperationRepository,
                new RagProperties(),
                mock(PlatformTransactionManager.class),
                lifecyclePublisher));
    }

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);
        rotationOperationRepository = mock(ApiKeyRotationOperationRepository.class);
        provisioningOperationRepository = mock(ApiKeyProvisioningOperationRepository.class);
        service = newService(mock(CollectionIdentityResolver.class),
                mock(ApiPrincipalLifecycleEventPublisher.class));
        when(rotationOperationRepository.findByPrincipalIdAndStatus(
                anyString(), any(ApiKeyRotationStatus.class))).thenReturn(Optional.empty());
    }

    private ApiAccessPolicy rootPolicy() {
        return new ApiAccessPolicy() {
            @Override
            public String getPrincipalId() {
                return PRINCIPAL_ID;
            }

            @Override
            public String getCredentialId() {
                return CURRENT_KEY;
            }

            @Override
            public ApiKeyRole getRole() {
                return ApiKeyRole.ADMIN;
            }

            @Override
            public String getAllowedCollectionIds() {
                return "";
            }

            @Override
            public LocalDateTime getExpiresAt() {
                return null;
            }
        };
    }

    private RagApiKey credential(String keyId, boolean enabled) {
        RagApiKey key = new RagApiKey();
        key.setKeyId(keyId);
        key.setPrincipalId(PRINCIPAL_ID);
        key.setCredentialVersion(1);
        key.setEnabled(enabled);
        return key;
    }

    private RagApiPrincipal principal(String allowedCollectionIds) {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId(PRINCIPAL_ID);
        principal.setName("Owner");
        principal.setRole(ApiKeyRole.NORMAL);
        principal.setCapabilities(ApiCapabilitySupport.FULL_SERIALIZED);
        principal.setPolicyVersion(1L);
        principal.setRequestsPerMinute(60);
        principal.setNextCredentialVersion(2);
        principal.setExpiresAt(LocalDateTime.now().plusDays(30));
        principal.setAllowedCollectionIds(allowedCollectionIds);
        return principal;
    }

    // ── 协作者未装配时不得 NPE ─────────────────────────────────────────

    @Test
    @DisplayName("未装配 CollectionIdentityResolver 时，allowedCollectionKeys 为 null 而不是 NPE")
    void collectionKeysAreNullWhenIdentityResolverIsAbsent() {
        // collectionIdentityResolver 是 @Autowired(required = false)，生产上确实可能为 null。
        // 此时若直接 mapKeys(...)，整个轮换接口会抛裸 NPE。
        service = newService(null, mock(ApiPrincipalLifecycleEventPublisher.class));
        doReturn("rag_sk_replacement_raw").when(service).generateRawKey();
        doReturn("rag_sk_replacement").when(service).generateKeyId();

        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, true)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID)).thenReturn(1);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal("11,22")));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(credential(CURRENT_KEY, true)));
        when(apiKeyRepository.disableByKeyId(anyString(), any(LocalDateTime.class)))
                .thenReturn(1);

        ApiKeyCreatedResponse response = service.rotateKey(CURRENT_KEY);

        assertNotNull(response);
        assertEquals(List.of(11L, 22L), response.getAllowedCollectionIds(),
                "数字 ID 仍应返回，只是 key 投影不可得");
        assertNull(response.getAllowedCollectionKeys(),
                "没有 identity resolver 时不得凭空编造 key");
    }

    @Test
    @DisplayName("未装配生命周期发布器时，策略变更仍应完成而不是 NPE")
    void policyUpdateSucceedsWithoutLifecyclePublisher() {
        // lifecycleEventPublisher 同样是 @Autowired(required = false)。
        service = newService(mock(CollectionIdentityResolver.class), null);
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID)).thenReturn(1);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal("")));

        ApiPrincipalPolicyUpdateRequest request = new ApiPrincipalPolicyUpdateRequest();
        request.setExpectedPolicyVersion(1L);
        request.setCapabilities(List.of(ApiCapabilitySupport.RAG_READ, ApiCapabilitySupport.RAG_WRITE));
        request.setExpiresAt(LocalDateTime.now().plusDays(30));

        ApiPrincipalResponse response = service.updatePolicy(
                PRINCIPAL_ID, request, List.of(), true);

        assertNotNull(response, "发布器缺席不应让整个策略变更失败");
        assertEquals(PRINCIPAL_ID, response.getPrincipalId());
    }

    // ── 凭据状态：响应投影必须诚实 ─────────────────────────────────────

    // ── 清理：并发删除容错 ───────────────────────────────────────────

    @Test
    @DisplayName("轮换记录在两次读取之间被删除时，清理不得 NPE")
    void rotationDeletedBetweenReadsIsTolerated() {
        // expireRotationById 连续读两次 ledger：先确认待过期，再取操作收敛。
        // 两次之间那一行完全可能被别人删掉（保留期清理、并发取消）。
        // 第二次读到空时必须安静返回，而不是对着 null 展开流程。
        UUID rotationId = UUID.randomUUID();
        when(rotationOperationRepository.findExpiredRotationIds(
                any(ApiKeyRotationStatus.class), any(LocalDateTime.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(rotationId));
        when(rotationOperationRepository.findById(rotationId))
                .thenReturn(Optional.of(pendingExpiredOperation(rotationId)))
                .thenReturn(Optional.empty());
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID)).thenReturn(1);

        service.cleanupCredentialRotations();

        verify(rotationOperationRepository, Mockito.never()).saveAndFlush(any());
    }

    // ── 轮换：凭据与主体边界 ─────────────────────────────────────────

    @Test
    @DisplayName("主体名下的当前凭据不存在时，轮换必须拒绝")
    void rotationWithoutCurrentCredentialIsRejected() {
        // `current == null` 这一侧：主体还在，但当前凭据已被禁用/退役。
        // 放行就等于凭空再发一把有效密钥。
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, true)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID)).thenReturn(1);
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.of(principal("")));
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.rotateKey(CURRENT_KEY));

        assertEquals(ErrorCode.CREDENTIAL_NOT_CURRENT, error.getErrorCodeEnum());
        // 只断言错误码是不够的：并发守卫（disableByKeyId 影响行数不为 1）
        // 会用**同一个**错误码把"内存态守卫被删掉"这件事盖过去。
        // 内存态守卫的价值恰恰在于它更早——任何状态都还没被改动。
        // 因此额外钉住：拒绝发生时不得已经写过库。
        verify(principalRepository, Mockito.never()).saveAndFlush(any());
        verify(apiKeyRepository, Mockito.never())
                .disableByKeyId(anyString(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("prepareRotation：空白 Idempotency-Key 被拒绝")
    void blankIdempotencyKeyIsRejected() {
        // null 一侧与其他测试共享；此处钉住"非 null 但全空白"这一侧，
        // 它会绕过朴素的 null 检查进入后续流程。
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 300, "   ", rootPolicy(), true));

        assertEquals("Idempotency-Key is required", error.getMessage());
    }

    @Test
    @DisplayName("prepareRotation：主体不存在时返回 NOT_FOUND")
    void prepareRotationOnUnknownPrincipalIsNotFound() {
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(credential(CURRENT_KEY, true)));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID)).thenReturn(1);
        when(rotationOperationRepository.findByPrincipalIdAndIdempotencyKeyHash(
                anyString(), anyString())).thenReturn(Optional.empty());
        when(principalRepository.findByPrincipalId(PRINCIPAL_ID))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.prepareRotation(
                        CURRENT_KEY, 300, "hash-1", rootPolicy(), true));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    // ── 轮换状态机 ───────────────────────────────────────────────────

    @Test
    @DisplayName("已过期的轮换不得被完成")
    void expiredRotationCannotBeCompleted() {
        // EXPIRED 分支此前完全没有覆盖：一条已过期的轮换若被当成可完成，
        // 就会给一把本该作废的凭据盖上"完成"的章。
        UUID rotationId = UUID.randomUUID();
        ApiKeyRotationOperation operation = new ApiKeyRotationOperation();
        operation.setRotationId(rotationId);
        operation.setPrincipalId(PRINCIPAL_ID);
        operation.setSourceCredentialId(CURRENT_KEY);
        operation.setTargetCredentialId("rag_sk_next");
        operation.setStatus(ApiKeyRotationStatus.EXPIRED);
        operation.setExpiresAt(LocalDateTime.now().minusMinutes(5));

        when(rotationOperationRepository.findById(rotationId))
                .thenReturn(Optional.of(operation));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID)).thenReturn(1);
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNotNull(
                PRINCIPAL_ID)).thenReturn(Optional.empty());
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNull(PRINCIPAL_ID))
                .thenReturn(Optional.of(credential(CURRENT_KEY, true)));
        when(apiKeyRepository.disableByKeyId(anyString(), any(LocalDateTime.class)))
                .thenReturn(1);

        RagException error = assertThrows(RagException.class,
                () -> service.completeRotation(rotationId, rootPolicy(), true));

        assertNotNull(error.getErrorCodeEnum());
        assertEquals(ApiKeyRotationStatus.EXPIRED, operation.getStatus(),
                "过期轮换必须落到 EXPIRED 终态");
    }

    @Test
    @DisplayName("源凭据已被禁用时不得重复禁用，但轮换仍须落到终态")
    void alreadyDisabledSourceIsNotDisabledTwice() {
        // `source.isEnabled()` 的 false 一侧。重复下发禁用是幂等的，
        // 但它会掩盖"到底谁先禁用的"这条排障线索。
        UUID rotationId = UUID.randomUUID();
        ApiKeyRotationOperation operation = pendingExpiredOperation(rotationId);

        RagApiKey disabledSource = credential(CURRENT_KEY, false);
        when(rotationOperationRepository.findById(rotationId))
                .thenReturn(Optional.of(operation));
        when(rotationOperationRepository.findExpiredRotationIds(
                any(ApiKeyRotationStatus.class), any(LocalDateTime.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(rotationId));
        when(principalRepository.acquireManagementWrite(PRINCIPAL_ID)).thenReturn(1);
        when(apiKeyRepository.findByPrincipalIdAndEnabledTrueAndRetireAtIsNotNull(
                PRINCIPAL_ID)).thenReturn(Optional.empty());
        when(apiKeyRepository.findByKeyId(CURRENT_KEY))
                .thenReturn(Optional.of(disabledSource));
        RagApiKey target = credential("rag_sk_next", true);
        target.setCredentialVersion(2);
        when(apiKeyRepository.findByKeyId("rag_sk_next"))
                .thenReturn(Optional.of(target));

        service.cleanupCredentialRotations();

        assertEquals(ApiKeyRotationStatus.EXPIRED, operation.getStatus(),
                "过期的 PENDING 轮换必须被收敛到 EXPIRED");
        verify(apiKeyRepository, Mockito.never())
                .disableByKeyId(anyString(), any(LocalDateTime.class));
    }

    private ApiKeyRotationOperation pendingExpiredOperation(UUID rotationId) {
        ApiKeyRotationOperation operation = new ApiKeyRotationOperation();
        operation.setRotationId(rotationId);
        operation.setPrincipalId(PRINCIPAL_ID);
        operation.setSourceCredentialId(CURRENT_KEY);
        operation.setTargetCredentialId("rag_sk_next");
        operation.setStatus(ApiKeyRotationStatus.PENDING);
        operation.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        return operation;
    }

    // ── 供应重试 ────────────────────────────────────────────────────

    @Test
    @DisplayName("并发供应冲突重试耗尽时返回 SERVICE_UNAVAILABLE")
    void provisioningRetryExhaustionSurfacesServiceUnavailable() {
        // 重试循环此前从未被执行：仓库每抛一次唯一键冲突，代码就退避重试，
        // 耗尽后必须给出可诊断的错误，而不是把最后一个异常吞掉。
        RagProperties properties = new RagProperties();
        properties.getApiKeyProvisioning().setEnabled(true);
        properties.getApiKeyProvisioning().setConcurrentRetryAttempts(2);
        service = Mockito.spy(new ApiKeyManagementService(
                apiKeyRepository, principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(JdbcTemplate.class),
                provisioningOperationRepository,
                rotationOperationRepository,
                properties,
                null, // 无事务管理器 → 直接在当前事务里走，冲突异常直接冒泡
                mock(ApiPrincipalLifecycleEventPublisher.class)));

        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setName("owner");
        when(provisioningOperationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER_ID, "hash-1"))
                .thenThrow(new DataIntegrityViolationException("unique owner+hash"));

        RagException error = assertThrows(RagException.class,
                () -> service.generateIdempotentKey(
                        request, ApiKeyRole.NORMAL, OWNER_ID, "hash-1", false));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("concurrent provisioning request"),
                "错误信息应指明是并发冲突耗尽: " + error.getMessage());
    }
}
