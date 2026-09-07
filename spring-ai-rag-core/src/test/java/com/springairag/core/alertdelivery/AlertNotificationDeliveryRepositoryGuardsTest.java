package com.springairag.core.alertdelivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖 durable 投递仓储的轻量守卫：幂等插入、版本收敛、
 * 过期租约恢复与清理计数。
 */
class AlertNotificationDeliveryRepositoryGuardsTest {

    private JdbcTemplate jdbcTemplate;
    private AlertNotificationDeliveryRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new AlertNotificationDeliveryRepository(
                jdbcTemplate, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void insertReportsConflictWhenRowAlreadyExists() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1)
                .thenReturn(0);

        assertTrue(repository.insert(
                id, 42L, 1, false, "webhook", "{}", 8));
        // 同 (alert, version, provider) 再次插入 → ON CONFLICT DO NOTHING。
        assertFalse(repository.insert(
                id, 42L, 1, false, "webhook", "{}", 8));
    }

    @Test
    void supersedeManagedReturnsAffectedRowCount() {
        // 按 SQL 分发：旧版本收敛 3 行，全量收敛 1 行。
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql != null && sql.contains("notification_version < ?")) {
                return 3;
            }
            return 1;
        });

        assertEquals(3, repository.supersedeOlderManaged(42L, 5));
        assertEquals(1, repository.supersedeManaged(42L));
    }

    @Test
    void recoverExhaustedLeasesReturnsRecoveredCount() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(2);

        assertEquals(2, repository.recoverExhaustedLeases(100));
    }

    @Test
    void findCandidateIdsReturnsDueJobIds() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.query(contains("WHERE status IN ('PENDING', 'RETRY_WAIT')"),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of(id));

        assertEquals(List.of(id), repository.findCandidateIds(10));
    }
}
