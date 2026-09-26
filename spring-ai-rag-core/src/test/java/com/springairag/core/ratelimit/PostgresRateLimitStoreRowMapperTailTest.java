package com.springairag.core.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PostgresRateLimitStore 行映射长尾（Batch 672，JaCoCo 驱动）：
 * consume/拒绝路径的 RowMapper 真实执行（ResultSet 列装配）。
 */
class PostgresRateLimitStoreRowMapperTailTest {

    private JdbcTemplate jdbcTemplate;
    private PostgresRateLimitStore store;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        store = new PostgresRateLimitStore(jdbcTemplate);
    }

    private ResultSet stubResultSet() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("request_count")).thenReturn(42);
        when(rs.getObject("window_start", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.now());
        when(rs.getInt("retry_after")).thenReturn(30);
        return rs;
    }

    @SuppressWarnings("unchecked")
    private void runConsumeWithMapperInvocation() throws Exception {
        ResultSet rs = stubResultSet();
        when(jdbcTemplate.query(
                anyString(), any(RowMapper.class), eq("p1"), eq(60)))
                .thenAnswer(invocation -> {
                    RowMapper<PostgresRateLimitStore.Decision> mapper =
                            invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @SuppressWarnings("unchecked")
    private void runCurrentWithMapperInvocation() throws Exception {
        ResultSet rs = stubResultSet();
        when(jdbcTemplate.query(
                anyString(), any(RowMapper.class), eq("p1")))
                .thenAnswer(invocation -> {
                    RowMapper<PostgresRateLimitStore.Decision> mapper =
                            invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @Test
    void consumeRowMapperAssemblesAcceptedDecision() throws Exception {
        runConsumeWithMapperInvocation();

        var decision = store.consume("p1", 60);

        assertTrue(decision.allowed());
        assertEquals(42, decision.requestCount());
        assertEquals(30, decision.retryAfterSeconds());
        assertNotNull(decision.windowStart());
    }

    @Test
    void rejectedConsumeRowMapperAssemblesDeniedDecision() throws Exception {
        // 消耗查询返回空（拒绝）→ 当前桶查询经 RowMapper 装配拒绝决策。
        ResultSet rs = stubResultSet();
        when(jdbcTemplate.query(
                anyString(), any(RowMapper.class), eq("p1"), eq(60)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(
                anyString(), any(RowMapper.class), eq("p1")))
                .thenAnswer(invocation -> {
                    RowMapper<PostgresRateLimitStore.Decision> mapper =
                            invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        var decision = store.consume("p1", 60);

        assertFalse(decision.allowed());
        assertEquals(42, decision.requestCount());
        assertEquals(30, decision.retryAfterSeconds());
    }


    private static void assertNotNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNotNull(value);
    }
}
