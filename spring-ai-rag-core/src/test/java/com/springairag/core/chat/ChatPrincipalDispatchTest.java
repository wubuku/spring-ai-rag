package com.springairag.core.chat;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 866。{@link ChatPrincipal#from(HttpServletRequest)} 按 principal 类型的四条分派分支。
 *
 * <p>为什么值得单独钉这一层：变异实验把 environment-root 分支的 {@code admin}
 * 从 true 改成 false（M-D），定向跑 4 个类时**完全漏网**；放到全量 7622 个用例上
 * 才被 {@code RagChatControllerTest#productionHistory_allowsRootToReadVisibleLegacyRows}
 * 抓到。但那两道机制都不是有意断言——该测试手工
 * {@code new ChatPrincipal("root:environment-root", ENVIRONMENT_ROOT, true)}
 * 塞进 Mockito 的 when 与 verify，靠 record 的 {@code equals}（含 admin 分量）匹配上。
 * 把那两处换成同一文件相邻测试**正在使用**的 {@code any(ChatPrincipal.class)} 惯用法后，
 * M-D 就完全漏网（32 个用例 0 红）。
 *
 * <p>结论：这条分支的管理员权限在整条主线上没有任何一条断言声明过它。
 * {@code admin()} 有 5 个生产读者（{@code LlmUsageQueryService} 4 处、
 * {@code RagChatToolRegistry} 1 处），而全仓唯一的 {@code admin()} 断言是
 * {@link ChatPrincipalNullRequestTest} 里对 local() 回落那条的 {@code assertFalse}——
 * 只覆盖了 false 一侧。
 *
 * <p>本类把"哪种 principal 类型带不带管理员"变成显式声明，
 * 而不是 Mockito 匹配器的副产品。{@link ChatPrincipalNullRequestTest} 继续只管
 * null 回落，两边职责不重叠。
 */
@DisplayName("ChatPrincipal 按 principal 类型的分派")
class ChatPrincipalDispatchTest {

    @Test
    @DisplayName("ENVIRONMENT_ROOT 恒为管理员，身份固定为 root:environment-root")
    void environmentRootIsAlwaysAdmin() {
        ChatPrincipal principal =
                ChatPrincipal.from(requestWith(ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT));

        assertEquals("root:environment-root", principal.id());
        assertEquals(ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT, principal.type());
        assertTrue(principal.admin(),
                "环境根是部署级身份，撤掉管理员会让它读不到别人的数据——"
                        + "LlmUsageQueryService 正是按 admin 放行跨 principal 的用量查询");
    }

    @Test
    @DisplayName("ENVIRONMENT_ROOT 只认类型：即使挂了 NORMAL 角色的 key 也仍是管理员")
    void environmentRootIgnoresKeyAndPolicyAttributes() {
        MockHttpServletRequest request =
                requestWith(ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT);
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "key-a");
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                policy(ApiKeyRole.NORMAL));

        ChatPrincipal principal = ChatPrincipal.from(request);

        assertEquals("root:environment-root", principal.id());
        assertTrue(principal.admin(),
                "环境根的管理员身份由 principal 类型决定，不看挂上来的 key 角色——"
                        + "否则这个判定会被一个可能被误配成 NORMAL 的 key 悄悄降级");
    }

    @Test
    @DisplayName("DATABASE_API_KEY 依据 key 角色带出管理员")
    void databaseKeyCarriesAdminRole() {
        MockHttpServletRequest request =
                requestWith(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "key-a");
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                policy(ApiKeyRole.ADMIN));

        ChatPrincipal principal = ChatPrincipal.from(request);

        assertEquals("db:key-a", principal.id());
        assertTrue(principal.admin(),
                "数据库 API key 的管理员身份来自策略里的角色，"
                        + "这正是 RagChatToolRegistry 执行命令时读的那个分量");
    }

    @Test
    @DisplayName("NORMAL 角色的 DATABASE_API_KEY 不是管理员")
    void normalDatabaseKeyIsNotAdmin() {
        MockHttpServletRequest request =
                requestWith(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "key-a");
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                policy(ApiKeyRole.NORMAL));

        ChatPrincipal principal = ChatPrincipal.from(request);

        assertEquals("db:key-a", principal.id());
        assertFalse(principal.admin(),
                "NORMAL 角色必须拿不到管理员；这条与上一条成对，防止把 admin 恒写成 true");
    }

    @Test
    @DisplayName("DATABASE_API_KEY 缺 key id 时落到 db:unknown")
    void databaseKeyWithoutKeyIdFallsBackToUnknown() {
        ChatPrincipal principal =
                ChatPrincipal.from(requestWith(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY));

        assertEquals("db:unknown", principal.id(),
                "缺 key id 时要落成可读的占位身份，而不是 null 前缀或直接崩掉");
    }

    @Test
    @DisplayName("LEGACY_STATIC 不是管理员")
    void legacyStaticKeyIsNeverAdmin() {
        ChatPrincipal principal =
                ChatPrincipal.from(requestWith(ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC));

        assertEquals("legacy:static", principal.id());
        assertFalse(principal.admin(),
                "静态 key 没有角色信息，任何情况下都不应被当成管理员");
    }

    @Test
    @DisplayName("未知 principal 类型回落到 local()，不继承任何权限")
    void unknownPrincipalTypeFallsBackToLocal() {
        ChatPrincipal principal = ChatPrincipal.from(requestWith("SOMETHING_NEW"));

        assertEquals(ChatPrincipal.local(), principal,
                "认不出的类型必须整体回落到 local()（三个分量都比），"
                        + "而不是只回落一部分、留下看起来有权限的残缺身份");
        assertFalse(principal.admin());
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
