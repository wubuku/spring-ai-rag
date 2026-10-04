package com.springairag.core.chat;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.security.ProvisioningOwnerResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 867。「持久化的 owner id」与「运行时身份」之间的那张映射表。
 *
 * <p>这张表在本批之前被写成了三份互不知情的实现：
 * <ol>
 *   <li>{@code ChatPrincipal.from} —— 请求 -&gt; 身份（正典）</li>
 *   <li>{@code ChatTurnOperationService.principalFor} —— 落库 id -&gt; 身份（重建）</li>
 *   <li>{@code RagChatHistoryRepository.canReadLegacy} 与
 *       {@code ChatExportService.canReadLegacy} —— 两份<b>逐字节相同</b>的私有方法</li>
 * </ol>
 *
 * <p>三个变异实验（全量 7629 个用例）证明其中两处漂移完全无感：
 * <ul>
 *   <li>N1：只把重建出的环境根降级成非管理员 -> <b>0 红</b></li>
 *   <li>N2：只从仓库那一份 canReadLegacy 删掉环境根 -> <b>0 红</b>（导出那一侧有测试，
 *       仓库那一侧一个都没有）</li>
 * </ul>
 *
 * <p>本类把往返不变量写下来：身份经 {@link ChatPrincipal#id()} 落库、再由
 * {@link ChatPrincipal#fromOwnerId} 重建之后必须<b>完全相等</b>，包括 admin 分量。
 * 同时钉住三处 id 字面量与 {@link ProvisioningOwnerResolver} 的三个 OWNER 常量逐字相等
 * ——两边各走各的代码路径算出同一个身份，漂移了不会有人发现。
 */
@DisplayName("ChatPrincipal 的 owner id 往返与 legacy 行可见性")
class ChatPrincipalOwnerIdTest {

    @Test
    @DisplayName("环境根经 owner id 往返后仍然是管理员")
    void environmentRootSurvivesOwnerIdRoundTrip() {
        ChatPrincipal original =
                ChatPrincipal.from(requestWith(ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT));

        ChatPrincipal rebuilt = ChatPrincipal.fromOwnerId(original.id());

        assertEquals(original, rebuilt,
                "重建必须逐分量等于原身份；丢了 admin 就等于把环境根降级");
        assertTrue(rebuilt.admin());
    }

    @Test
    @DisplayName("静态 key 经 owner id 往返后仍然不是管理员")
    void legacyStaticSurvivesOwnerIdRoundTrip() {
        ChatPrincipal original =
                ChatPrincipal.from(requestWith(ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC));

        ChatPrincipal rebuilt = ChatPrincipal.fromOwnerId(original.id());

        assertEquals(original, rebuilt);
        assertFalse(rebuilt.admin());
    }

    @Test
    @DisplayName("auth 关闭的本地身份经 owner id 往返后不变")
    void authDisabledSurvivesOwnerIdRoundTrip() {
        ChatPrincipal original = ChatPrincipal.from(null);

        assertEquals(original, ChatPrincipal.fromOwnerId(original.id()));
    }

    @Test
    @DisplayName("数据库 key 的重建刻意丢掉角色：id 保留，admin 一律 false")
    void databaseKeyRoundTripDeliberatelyLosesRole() {
        MockHttpServletRequest request =
                requestWith(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "key-a");
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                policy(ApiKeyRole.ADMIN));

        ChatPrincipal original = ChatPrincipal.from(request);
        assertTrue(original.admin(), "前置条件：原身份确实带管理员");

        ChatPrincipal rebuilt = ChatPrincipal.fromOwnerId(original.id());

        assertEquals(original.id(), rebuilt.id(), "id 必须原样保留");
        assertFalse(rebuilt.admin(),
                "落库的 id 不带角色，所以重建一律按最低权限解释。"
                        + "这是刻意的 fail-closed——不要把它'修'成从别处猜回角色，"
                        + "那会把一条重建路径变成提权路径");
    }

    @Test
    @DisplayName("三个非数据库身份都能读 legacy 行")
    void canReadLegacyRowsAcceptsTheThreeNonDatabaseIdentities() {
        assertTrue(ChatPrincipal.local().canReadLegacyRows());
        assertTrue(ChatPrincipal.fromOwnerId(ChatPrincipal.ENVIRONMENT_ROOT_ID)
                .canReadLegacyRows());
        assertTrue(ChatPrincipal.fromOwnerId(ChatPrincipal.LEGACY_STATIC_ID)
                .canReadLegacyRows());
    }

    @Test
    @DisplayName("任何数据库 key 都读不到 legacy 行，哪怕它是管理员")
    void canReadLegacyRowsRejectsEveryDatabaseKey() {
        for (String keyId : new String[] {"key-a", "unknown", "42"}) {
            assertFalse(ChatPrincipal.fromOwnerId(
                            ChatPrincipal.DATABASE_KEY_ID_PREFIX + keyId)
                    .canReadLegacyRows(),
                    "数据库 key（" + keyId + "）必须读不到 legacy 行："
                            + "这些行在归属字段上线前写入，不属于任何 key");
        }
    }

    @Test
    @DisplayName("ChatPrincipal 的三个 id 与 ProvisioningOwnerResolver 的常量逐字相等")
    void ownerIdsMatchProvisioningOwnerConstants() {
        assertEquals(ProvisioningOwnerResolver.ENVIRONMENT_ROOT_OWNER,
                ChatPrincipal.ENVIRONMENT_ROOT_ID);
        assertEquals(ProvisioningOwnerResolver.LEGACY_STATIC_OWNER,
                ChatPrincipal.LEGACY_STATIC_ID);
        assertEquals(ProvisioningOwnerResolver.AUTH_DISABLED_OWNER,
                ChatPrincipal.AUTH_DISABLED_ID);
    }

    @Test
    @DisplayName("ChatPrincipal 产出的 id 就是 ProvisioningOwnerResolver 认的那三个")
    void producedOwnerIdsAreTheOnesProvisioningAccepts() {
        MockHttpServletRequest root = requestWith(
                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT);
        MockHttpServletRequest legacy = requestWith(
                ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC);
        MockHttpServletRequest local = new MockHttpServletRequest();

        assertEquals(ProvisioningOwnerResolver.ENVIRONMENT_ROOT_OWNER,
                ChatPrincipal.from(root).id());
        assertEquals(ProvisioningOwnerResolver.LEGACY_STATIC_OWNER,
                ChatPrincipal.from(legacy).id());
        assertEquals(ProvisioningOwnerResolver.AUTH_DISABLED_OWNER,
                ChatPrincipal.from(local).id());
    }

    private MockHttpServletRequest requestWith(String principalType) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE, principalType);
        return request;
    }

    private AuthenticatedApiPrincipal policy(ApiKeyRole role) {
        return new AuthenticatedApiPrincipal(
                "principal-1", "credential-1", 1,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, role,
                "1,2", LocalDateTime.now().plusYears(1), 1L, 60);
    }
}
