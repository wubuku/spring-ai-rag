package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖评测套件仓储：套件/版本/运行 CRUD、版本分配 CAS、worker 认领
 * 与心跳租约、用例结果写入与读取。
 */
class EvaluationSuiteRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private EvaluationSuiteRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new EvaluationSuiteRepository(
                jdbcTemplate, new ObjectMapper());
    }

    private void stubSuiteRow() {
        when(jdbcTemplate.queryForObject(anyString(),
                any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id", UUID.class))
                            .thenReturn(UUID.randomUUID());
                    when(rs.getString("suite_key")).thenReturn("gold-en");
                    when(rs.getString("name")).thenReturn("Gold EN");
                    when(rs.getString("owner_principal_id"))
                            .thenReturn("principal-1");
                    when(rs.getObject("created_at", OffsetDateTime.class))
                            .thenReturn(OffsetDateTime.parse("2026-09-07T10:00:00Z"));
                    return mapper.mapRow(rs, 0);
                });
    }

    @Test
    void insertSuiteReturnsMappedRow() {
        stubSuiteRow();

        var row = repository.insertSuite("gold-en", "Gold EN", "principal-1");

        assertEquals("gold-en", row.suiteKey());
        assertEquals("Gold EN", row.name());
        assertEquals("principal-1", row.ownerPrincipalId());
    }

    @Test
    void findSuiteReturnsEmptyWhenNoRows() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        assertTrue(repository.findSuite("principal-1", "ghost").isEmpty());
    }

    @Test
    void insertVersionThrowsWhenSuiteMissing() {
        // RETURNING 空列表表示 suite 不存在。
        when(jdbcTemplate.query(contains("RETURNING next_version - 1"),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> repository.insertVersion(UUID.randomUUID(), "{}", "sha"));
    }

    @Test
    void insertVersionAllocatesSequentialVersion() {
        // 版本号从 RETURNING 分配（next_version 递增后回读旧值）。
        when(jdbcTemplate.query(contains("RETURNING next_version - 1"),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of(2));
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class),
                any(Object[].class))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    UUID id = UUID.randomUUID();
                    when(rs.getObject("id", UUID.class)).thenReturn(id);
                    when(rs.getObject("suite_id", UUID.class))
                            .thenReturn(UUID.randomUUID());
                    when(rs.getInt("version")).thenReturn(2);
                    when(rs.getString("definition")).thenReturn("{\"cases\":[]}");
                    when(rs.getString("definition_sha256")).thenReturn("sha");
                    when(rs.getObject("created_at", OffsetDateTime.class))
                            .thenReturn(OffsetDateTime.parse("2026-09-07T10:00:00Z"));
                    return mapper.mapRow(rs, 0);
                });

        var version = repository.insertVersion(UUID.randomUUID(), "{\"cases\":[]}", "sha");

        assertEquals(2, version.version());
        assertEquals("sha", version.definitionSha256());
    }

    @Test
    void findVersionWithoutVersionReturnsLatest() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        assertTrue(repository.findVersion(UUID.randomUUID(), null).isEmpty());
    }

    @Test
    void countActiveRunsTreatsNullAsZero() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                any(Object[].class))).thenReturn(null);

        assertEquals(0, repository.countActiveRuns("principal-1"));
    }

    @Test
    void tryInsertRunReturnsEmptyOnSlotConflict() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        assertTrue(repository.tryInsertRun(UUID.randomUUID(), "principal-1",
                "PENDING", "{}", "rev-1", "bge-m3", 0).isEmpty());
    }

    @Test
    void claimFloorsLimitAndLeaseSeconds() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                args.capture())).thenReturn(List.of());

        repository.claim("worker-1", 0, 5);

        // 参数顺序：LIMIT → workerId → 租约秒（下限 30）。
        assertEquals(1, args.getValue()[0]);
        assertEquals("worker-1", args.getValue()[1]);
        assertEquals(30, args.getValue()[2]);
    }

    @Test
    void heartbeatAndMarkInterruptedReportCasOutcome() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        assertEquals(1, repository.heartbeat(UUID.randomUUID(), "w-1", 30));
        assertEquals(1, repository.markInterrupted(UUID.randomUUID(), "w-1"));
    }

    @Test
    void finishRunReportsCasOutcome() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        assertEquals(0, repository.finishRun(UUID.randomUUID(), "w-1",
                "SUCCEEDED", "{}", null));
    }

    @Test
    void insertCaseResultBindsRunAndWorkerGuard() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        UUID runId = UUID.randomUUID();

        assertEquals(1, repository.insertCaseResult(
                runId, "w-1", "baseline", "case-1", "PASSED",
                "[]", "{}", 120, UUID.randomUUID(), null));

        verify(jdbcTemplate).update(contains("rag_evaluation_case_results"),
                eq("baseline"), eq("case-1"), eq("PASSED"), eq("[]"), eq("{}"),
                eq(120), any(), eq((String) null), eq(runId), eq("w-1"));
    }

    @Test
    void listCaseResultsMapsJsonColumns() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object[].class))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    UUID runId = UUID.randomUUID();
                    UUID traceId = UUID.randomUUID();
                    when(rs.getObject("run_id", UUID.class)).thenReturn(runId);
                    when(rs.getString("variant_key")).thenReturn("baseline");
                    when(rs.getString("case_id")).thenReturn("case-1");
                    when(rs.getString("status")).thenReturn("PASSED");
                    when(rs.getString("retrieved_identities")).thenReturn("[\"d1\"]");
                    when(rs.getString("metrics")).thenReturn("{\"mrr\":1}");
                    when(rs.getObject("latency_ms")).thenReturn(120);
                    when(rs.getObject("trace_id", UUID.class)).thenReturn(traceId);
                    when(rs.getString("error_code")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<EvaluationSuiteRepository.CaseRow> rows =
                repository.listCaseResults(UUID.randomUUID());

        assertEquals(1, rows.size());
        assertEquals("baseline", rows.getFirst().variantKey());
        assertEquals("PASSED", rows.getFirst().status());
        assertEquals(120, rows.getFirst().latencyMs());
    }
}
