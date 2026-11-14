package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WP3：SINGLE 模式守卫（凭据全部 default，否则拒绝启动）的纯 JVM 测试。
 *
 * <p>不需要真实 PostgreSQL：mock {@link JdbcTemplate} 返回「是否存在非 default 凭据」。
 * 「存在非 default 凭据 → 拒绝启动」是规划 §6 WP3 的硬退出条件之一。</p>
 */
@DisplayName("TenantModeGuard (SINGLE 凭据守卫)")
class TenantModeGuardTest {

    private static JdbcTemplate jdbcReturning(long keyRows, long principalRows) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("rag_api_principal")) {
                        return principalRows;
                    }
                    if (sql.contains("rag_api_key")) {
                        return keyRows;
                    }
                    return 0L;
                });
        return jdbc;
    }

    @Test
    @DisplayName("SINGLE + 存在非 default 凭据 → 拒绝启动")
    void singleModeRefusesNonDefaultCredentials() {
        TenantModeGuard guard = new TenantModeGuard(jdbcReturning(1, 0));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.verify(TenantMode.SINGLE));
        assertTrue(ex.getMessage().contains("rag_api_key"));
        assertTrue(ex.getMessage().contains("default"));
    }

    @Test
    @DisplayName("SINGLE + principal 存在非 default 凭据 → 拒绝启动")
    void singleModeRefusesNonDefaultPrincipal() {
        TenantModeGuard guard = new TenantModeGuard(jdbcReturning(0, 3));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.verify(TenantMode.SINGLE));
        assertTrue(ex.getMessage().contains("rag_api_principal"));
    }

    @Test
    @DisplayName("SINGLE + 凭据全部 default → 放行（不要求三角色/RLS）")
    void singleModeAllowsAllDefaultCredentials() {
        TenantModeGuard guard = new TenantModeGuard(jdbcReturning(0, 0));
        assertDoesNotThrow(() -> guard.verify(TenantMode.SINGLE));
    }

    @Test
    @DisplayName("STRICT 模式委托给 TenantRlsVerifier")
    void strictModeDelegatesToRlsVerifier() {
        StubRlsVerifier verifier = new StubRlsVerifier();
        TenantModeGuard guard = new TenantModeGuard(jdbcReturning(0, 0), verifier);
        guard.verify(TenantMode.STRICT);
        assertTrue(verifier.called, "STRICT 必须执行 RLS verifier 全项校验");
    }

    /** 记录是否被调用的 verifier 替身。 */
    static final class StubRlsVerifier implements TenantRlsVerifier {
        boolean called;

        @Override
        public void verify() {
            called = true;
        }
    }
}
