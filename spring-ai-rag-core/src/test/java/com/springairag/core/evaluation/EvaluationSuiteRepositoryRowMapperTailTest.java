package com.springairag.core.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EvaluationSuiteRepository 行映射长尾（Batch 708，JaCoCo 驱
 * 动）：mapSuite / mapVersion / mapRun 在真实 ResultSet 装配下执
 * 行、按显式版本号查询走参数化分支、countActiveRuns 对 null 计数
 * 归零。
 */
class EvaluationSuiteRepositoryRowMapperTailTest {

    private JdbcTemplate jdbcTemplate;
    private EvaluationSuiteRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new EvaluationSuiteRepository(
                jdbcTemplate, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private ResultSet suiteRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getString("suite_key")).thenReturn("smoke-suite");
        when(rs.getString("name")).thenReturn("Smoke");
        when(rs.getString("owner_principal_id")).thenReturn("owner-1");
        when(rs.getObject("created_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-09-29T00:00:00Z"));
        return rs;
    }

    private ResultSet versionRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getObject("suite_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getInt("version")).thenReturn(3);
        when(rs.getString("definition")).thenReturn("{\"cases\":[]}");
        when(rs.getString("definition_sha256")).thenReturn("abc");
        when(rs.getObject("created_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-09-29T00:00:00Z"));
        return rs;
    }

    private Answer<List<Object>> invokeSuiteMapper() {
        return invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(suiteRow(), 0));
        };
    }

    @SuppressWarnings("unchecked")
    @Test
    void insertSuiteInvokesSuiteRowMapper() {
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return mapper.mapRow(suiteRow(), 0);
                });

        var row = repository.insertSuite(
                "smoke-suite", "Smoke", "owner-1");

        assertEquals("smoke-suite", row.suiteKey());
        assertEquals("Smoke", row.name());
    }

    @SuppressWarnings("unchecked")
    @Test
    void findSuiteInvokesSuiteRowMapper() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object[].class)))
                .thenAnswer((Answer<List<Object>>) invokeSuiteMapper());

        Optional<EvaluationSuiteRepository.SuiteRow> row =
                repository.findSuite("owner-1", "smoke-suite");

        assertTrue(row.isPresent());
        assertEquals("owner-1", row.get().ownerPrincipalId());
    }

    @SuppressWarnings("unchecked")
    @Test
    void findVersionByExplicitNumberInvokesVersionRowMapper() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(versionRow(), 0));
                });

        Optional<EvaluationSuiteRepository.VersionRow> row =
                repository.findVersion(UUID.randomUUID(), 3);

        assertTrue(row.isPresent());
        assertEquals(3, row.get().version());
        assertInstanceOf(com.fasterxml.jackson.databind.JsonNode.class,
                row.get().definition());
    }

    @SuppressWarnings("unchecked")
    @Test
    void findVersionByIdInvokesVersionRowMapper() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(versionRow(), 0));
                });

        assertTrue(repository.findVersionById(UUID.randomUUID()).isPresent());
    }

    @Test
    void countActiveRunsTreatsNullCountAsZero() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                any(Object[].class))).thenReturn(null);

        assertEquals(0, repository.countActiveRuns("owner-1"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void insertRunInvokesRunRowMapper() throws Exception {
        ResultSet runRow = mock(ResultSet.class);
        when(runRow.getObject(eq("id"), eq(UUID.class)))
                .thenReturn(UUID.randomUUID());
        when(runRow.getObject(eq("suite_version_id"), eq(UUID.class)))
                .thenReturn(UUID.randomUUID());
        when(runRow.getString("owner_principal_id")).thenReturn("owner-1");
        when(runRow.getString("status")).thenReturn("RUNNING");
        when(runRow.getString("configuration_snapshot")).thenReturn("{}");
        when(runRow.getString("code_revision")).thenReturn("rev-1");
        when(runRow.getString("embedding_profile_key")).thenReturn("bge-m3");
        when(runRow.getString("aggregate_metrics")).thenReturn("{}");
        when(runRow.getString("error")).thenReturn(null);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return mapper.mapRow(runRow, 0);
                });

        var row = repository.insertRun(
                UUID.randomUUID(), "owner-1", "RUNNING", "{}", "rev-1", "bge-m3");

        assertEquals("RUNNING", row.status());
        assertEquals("rev-1", row.codeRevision());
    }
}
