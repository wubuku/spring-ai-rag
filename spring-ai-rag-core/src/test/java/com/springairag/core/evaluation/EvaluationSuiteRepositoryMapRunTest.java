package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * mapRun 运行行映射（Batch 319）：全字段映射断言 + 空白/畸形
 * JSON 列回退 nullNode 的容错。
 */
class EvaluationSuiteRepositoryMapRunTest {

    private JdbcTemplate jdbcTemplate;
    private EvaluationSuiteRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new EvaluationSuiteRepository(
                jdbcTemplate, new ObjectMapper());
    }

    private void stubRunRow(
            String configurationSnapshot,
            String aggregateMetrics) throws Exception {
        UUID runId = UUID.fromString(
                "00000000-0000-0000-0000-000000000001");
        UUID versionId = UUID.fromString(
                "00000000-0000-0000-0000-000000000002");
        when(jdbcTemplate.query(
                contains("FROM rag_evaluation_runs"),
                any(RowMapper.class), eq(runId), eq("principal-1")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(runId);
                    when(rs.getObject("suite_version_id", UUID.class))
                            .thenReturn(versionId);
                    when(rs.getString("owner_principal_id"))
                            .thenReturn("principal-1");
                    when(rs.getString("status")).thenReturn("SUCCEEDED");
                    when(rs.getString("configuration_snapshot"))
                            .thenReturn(configurationSnapshot);
                    when(rs.getString("code_revision")).thenReturn("rev-9");
                    when(rs.getString("embedding_profile_key"))
                            .thenReturn("bge-m3");
                    when(rs.getString("aggregate_metrics"))
                            .thenReturn(aggregateMetrics);
                    when(rs.getString("error")).thenReturn(null);
                    when(rs.getObject("started_at", OffsetDateTime.class))
                            .thenReturn(OffsetDateTime.parse("2026-09-13T01:00:00Z"));
                    when(rs.getObject("finished_at", OffsetDateTime.class))
                            .thenReturn(OffsetDateTime.parse("2026-09-13T01:05:00Z"));
                    when(rs.getObject("created_at", OffsetDateTime.class))
                            .thenReturn(OffsetDateTime.parse("2026-09-13T00:59:00Z"));
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @Test
    void findRunMapsEveryColumn() throws Exception {
        UUID runId = UUID.fromString(
                "00000000-0000-0000-0000-000000000001");
        UUID versionId = UUID.fromString(
                "00000000-0000-0000-0000-000000000002");
        stubRunRow("{\"topK\":5}", "{\"hitRate\":0.9}");

        Optional<EvaluationSuiteRepository.RunRow> run =
                repository.findRun(runId, "principal-1");

        assertTrue(run.isPresent());
        EvaluationSuiteRepository.RunRow row = run.get();
        assertEquals(runId, row.id());
        assertEquals(versionId, row.suiteVersionId());
        assertEquals("principal-1", row.ownerPrincipalId());
        assertEquals("SUCCEEDED", row.status());
        JsonNode snapshot = row.configurationSnapshot();
        assertEquals(5, snapshot.get("topK").asInt());
        assertEquals("rev-9", row.codeRevision());
        assertEquals("bge-m3", row.embeddingProfileKey());
        assertEquals(0.9, row.aggregateMetrics().get("hitRate").asDouble());
        assertNull(row.error());
        assertEquals("2026-09-13T01:00:00Z",
                row.startedAt().toInstant().toString());
        assertEquals("2026-09-13T01:05:00Z",
                row.finishedAt().toInstant().toString());
        assertEquals("2026-09-13T00:59:00Z",
                row.createdAt().toInstant().toString());
    }

    @Test
    void blankOrMalformedJsonColumnsFallBackToNullNode() throws Exception {
        UUID runId = UUID.fromString(
                "00000000-0000-0000-0000-000000000001");
        stubRunRow("  ", "{not-valid-json");

        Optional<EvaluationSuiteRepository.RunRow> run =
                repository.findRun(runId, "principal-1");

        assertTrue(run.isPresent());
        // 空白与畸形 JSON 均回退 nullNode 而非抛错。
        assertTrue(run.get().configurationSnapshot().isNull());
        assertTrue(run.get().aggregateMetrics().isNull());
    }
}
