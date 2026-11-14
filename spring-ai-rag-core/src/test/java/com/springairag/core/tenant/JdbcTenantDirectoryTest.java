package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** WP7：ACTIVE 租户目录 fail-closed 合同。 */
@DisplayName("JdbcTenantDirectory (ACTIVE 租户枚举)")
class JdbcTenantDirectoryTest {

    @Test
    @DisplayName("枚举到 ACTIVE 租户 → 按稳定顺序返回")
    void returnsActiveTenants() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(any(String.class), eq(String.class)))
                .thenReturn(List.of("default", "tenant-a"));
        assertEquals(List.of("default", "tenant-a"),
                new JdbcTenantDirectory(jdbc).activeTenantIds());
    }

    @Test
    @DisplayName("枚举为空 → fail-closed 拒绝（不静默降级）")
    void emptyEnumerationRejected() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(any(String.class), eq(String.class)))
                .thenReturn(List.of());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JdbcTenantDirectory(jdbc).activeTenantIds());
        assertTrue(ex.getMessage().contains("枚举为空"));
    }

    @Test
    @DisplayName("SQL 失败 → fail-closed 拒绝（不吞异常）")
    void queryFailureRejected() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(any(String.class), eq(String.class)))
                .thenThrow(new IllegalStateException("db down"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JdbcTenantDirectory(jdbc).activeTenantIds());
        assertTrue(ex.getMessage().contains("枚举失败"));
    }
}
