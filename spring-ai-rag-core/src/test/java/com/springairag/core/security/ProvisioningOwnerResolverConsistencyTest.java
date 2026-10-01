package com.springairag.core.security;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 认证状态一致性的<strong>矛盾组合</strong>矩阵（Batch 765）。
 *
 * <p>{@code ProvisioningOwnerResolver} 决定一次 provisioning 操作归属于哪个 owner。
 * 它的正确性直接等于越权防线：任何"部分存在"或"互相矛盾"的认证状态都<strong>必须</strong>
 * 落到 {@code inconsistent()}，而不是猜测一个 owner——猜错就是把操作记到别人头上，
 * 或者让本该受限的调用方拿到 owner 身份。
 *
 * <p>既有 4 个测试覆盖了 3 条正常路径和 2 条明显的矛盾（id 不匹配、id 单独存在）。
 * 缺的是三类最容易在重构中被改坏的组合：
 * <ol>
 *   <li>不该出现的 snapshot：root / legacy 类型上挂了一个 principal snapshot。</li>
 *   <li>database 路径上每一项单独缺失或类型不对：id 非 String、空白、snapshot 类型错、
 *       principalType 对不上。</li>
 *   <li>auth-disabled 路径上多出来的一个属性——这正是 {@code currentPolicy(request) == null}
 *       那一支要管的事：属性为空不等于认证被关闭。</li>
 * </ol>
 */
class ProvisioningOwnerResolverConsistencyTest {

    private final ProvisioningOwnerResolver resolver =
            new ProvisioningOwnerResolver();

    private static AuthenticatedApiPrincipal principal(
            String principalId, String principalType) {
        return new AuthenticatedApiPrincipal(
                principalId, "credential-v1", 1, principalType,
                ApiKeyRole.NORMAL, null, null, 1, 120,
                List.of(ApiCapabilitySupport.RAG_READ));
    }

    private static MockHttpServletRequest requestWith(
            Object type, Object id, Object snapshot) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE, type);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, id);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE, snapshot);
        return request;
    }

    private void assertRejected(MockHttpServletRequest request, String description) {
        RagException error = assertThrows(RagException.class,
                () -> resolver.resolve(request),
                () -> description + " 时必须拒绝，不能猜一个 owner");
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum(),
                description);
    }

    // ==================== root / legacy 上不该出现 snapshot ====================

    @Test
    @DisplayName("root 类型上挂着 principal snapshot 时拒绝")
    void environmentRootWithSnapshotIsRejected() {
        assertRejected(
                requestWith(ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT, null,
                        principal("p-1", ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY)),
                "root 类型不应携带 database principal 快照");
    }

    @Test
    @DisplayName("legacy 类型上挂着 principal snapshot 时拒绝")
    void legacyStaticWithSnapshotIsRejected() {
        assertRejected(
                requestWith(ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC, null,
                        principal("p-1", ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY)),
                "legacy 类型不应携带 database principal 快照");
    }

    @Test
    @DisplayName("root 类型不携带 snapshot 时正常解析")
    void environmentRootWithoutSnapshotIsAccepted() {
        assertEquals(ProvisioningOwnerResolver.ENVIRONMENT_ROOT_OWNER,
                resolver.resolve(requestWith(
                        ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT, null, null)));
    }

    @Test
    @DisplayName("legacy 类型不携带 snapshot 时正常解析")
    void legacyStaticWithoutSnapshotIsAccepted() {
        assertEquals(ProvisioningOwnerResolver.LEGACY_STATIC_OWNER,
                resolver.resolve(requestWith(
                        ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC, null, null)));
    }

    // ==================== database 路径上每一项的缺失与类型错误 ====================

    @Test
    @DisplayName("database 类型缺少 principal id 时拒绝")
    void databaseWithoutPrincipalIdIsRejected() {
        assertRejected(
                requestWith(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, null,
                        principal("p-1", ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY)),
                "database 类型没有稳定 principal id 就无法归属");
    }

    @Test
    @DisplayName("database 类型的 principal id 为空白时拒绝")
    void databaseWithBlankPrincipalIdIsRejected() {
        assertRejected(
                requestWith(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, "   ",
                        principal("p-1", ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY)),
                "空白 principal id 会被拼进 owner 字符串，污染归属");
    }

    @Test
    @DisplayName("database 类型的 principal id 不是 String 时拒绝")
    void databaseWithNonStringPrincipalIdIsRejected() {
        assertRejected(
                requestWith(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, 42,
                        principal("p-1", ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY)),
                "principal id 必须是 String");
    }

    @Test
    @DisplayName("database 类型缺少 principal 快照时拒绝")
    void databaseWithoutSnapshotIsRejected() {
        // 类型说自己是 database，却没有快照——不得退化成 legacy 或 auth-disabled。
        assertRejected(
                requestWith(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, "p-1", null),
                "database 类型缺少快照");
    }

    @Test
    @DisplayName("database 快照类型不对时拒绝")
    void databaseWithWrongSnapshotTypeIsRejected() {
        assertRejected(
                requestWith(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, "p-1",
                        "not-a-principal"),
                "快照必须是 AuthenticatedApiPrincipal");
    }

    @Test
    @DisplayName("快照内的 principalType 与请求声明不一致时拒绝")
    void databaseWithMismatchedPrincipalTypeIsRejected() {
        // 攻击面：把一个 legacy/root 身份塞进 database 类型的请求里。
        assertRejected(
                requestWith(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, "p-1",
                        principal("p-1", ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT)),
                "快照 principalType 必须与请求声明一致");
    }

    @Test
    @DisplayName("id 与快照的 principalId 不一致时拒绝")
    void databaseWithMismatchedIdsIsRejected() {
        assertRejected(
                requestWith(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, "p-1",
                        principal("p-2", ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY)),
                "id 与快照必须指向同一个 principal");
    }

    @Test
    @DisplayName("一致的 database 身份正常解析出稳定 owner")
    void consistentDatabaseIdentityIsAccepted() {
        assertEquals("db:p-1",
                resolver.resolve(requestWith(
                        ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY, "p-1",
                        principal("p-1", ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY))));
    }

    // ==================== auth-disabled 路径：属性为空 ≠ 认证关闭 ====================

    @Test
    @DisplayName("三个属性都为空时才是 auth-disabled")
    void allAttributesAbsentIsAuthDisabled() {
        assertEquals(ProvisioningOwnerResolver.AUTH_DISABLED_OWNER,
                resolver.resolve(new MockHttpServletRequest()));
    }

    @Test
    @DisplayName("request 为 null 时按 auth-disabled 处理")
    void nullRequestIsAuthDisabled() {
        assertEquals(ProvisioningOwnerResolver.AUTH_DISABLED_OWNER,
                resolver.resolve(null));
    }

    @Test
    @DisplayName("只有快照存在而 type/id 为空时拒绝，不当作 auth-disabled")
    void snapshotOnlyIsRejected() {
        // 这是最危险的一种组合：看起来"没有任何认证信息"，若被当成
        // auth-disabled，一次本应被拒绝的 provisioning 就会获得本地 owner 身份。
        assertRejected(
                requestWith(null, null,
                        principal("p-1", ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY)),
                "仅有快照时不能当作未认证");
    }

    @Test
    @DisplayName("只有 id 存在而 type 为空时拒绝")
    void idOnlyIsRejected() {
        assertRejected(requestWith(null, "p-1", null), "仅有 id 时不能当作未认证");
    }

    @Test
    @DisplayName("只有 type 存在且是未知取值时拒绝")
    void unknownPrincipalTypeIsRejected() {
        assertRejected(requestWith("some-future-type", null, null),
                "未知的 principal 类型不得回退到任何既有 owner");
    }

    @Test
    @DisplayName("auth-disabled owner 与 environment root owner 不得相同")
    void disabledOwnerDiffersFromRootOwner() {
        // 两条路径的归属主体必须可区分，否则审计台账无法分辨"本地无认证"
        // 与"环境根身份"。
        assertNotEquals(ProvisioningOwnerResolver.AUTH_DISABLED_OWNER,
                ProvisioningOwnerResolver.ENVIRONMENT_ROOT_OWNER);
        assertNotEquals(ProvisioningOwnerResolver.AUTH_DISABLED_OWNER,
                ProvisioningOwnerResolver.LEGACY_STATIC_OWNER);
    }
}
