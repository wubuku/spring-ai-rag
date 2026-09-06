package com.springairag.core.service;

import com.springairag.api.dto.DocumentSyncRunItemCurrentSummary;
import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.DocumentSyncItemStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * 覆盖 Sync Run item receipt 只读查询：计数摘要、四种分页谓词组合
 * 与多类型时间戳转换。
 */
class DocumentSyncRunItemReceiptRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private DocumentSyncRunItemReceiptRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new DocumentSyncRunItemReceiptRepository(jdbcTemplate);
    }

    private DocumentSyncRunItemCursorCodec.CursorPosition cursor() {
        return new DocumentSyncRunItemCursorCodec.CursorPosition(
                OffsetDateTime.parse("2026-09-01T10:15:30Z"), "ext-9");
    }

    @Test
    void currentSummaryMapsAllStatusCounters() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("total")).thenReturn(20L);
        when(rs.getLong("applied")).thenReturn(12L);
        when(rs.getLong("unchanged")).thenReturn(5L);
        when(rs.getLong("skipped_newer_mutation")).thenReturn(2L);
        when(rs.getLong("failed")).thenReturn(1L);
        ArgumentCaptor<RowMapper<DocumentSyncRunItemCurrentSummary>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        UUID runId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(contains("WHERE run_id = ?"),
                mapper.capture(), eq(runId))).thenAnswer(invocation -> null);

        repository.currentSummary(runId);

        DocumentSyncRunItemCurrentSummary summary =
                mapper.getValue().mapRow(rs, 0);
        assertEquals(20L, summary.total());
        assertEquals(12L, summary.applied());
        assertEquals(5L, summary.unchanged());
        assertEquals(2L, summary.skippedNewerMutation());
        assertEquals(1L, summary.failed());
    }

    @Test
    void pageWithoutFiltersUsesBaseQueryOnly() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.query(sql.capture(), any(RowMapper.class),
                args.capture())).thenReturn(List.of());
        UUID runId = UUID.randomUUID();

        repository.page(runId, null, null, 51);

        assertTrue(sql.getValue().contains("WHERE run_id = ?"));
        assertTrue(!sql.getValue().contains("status = ?"));
        assertTrue(!sql.getValue().contains("(seen_at, external_id) >"));
        assertEquals(2, args.getValue().length);
    }

    @Test
    void pageWithCursorOnlyAddsKeysetPredicate() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.query(sql.capture(), any(RowMapper.class),
                args.capture())).thenReturn(List.of());

        repository.page(UUID.randomUUID(), null, cursor(), 51);

        assertTrue(sql.getValue().contains("(seen_at, external_id) > (?, ?)"));
        assertTrue(!sql.getValue().contains("status = ?"));
        assertEquals(4, args.getValue().length);
        assertEquals("ext-9", args.getValue()[2]);
    }

    @Test
    void pageWithStatusOnlyAddsStatusPredicate() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(sql.capture(), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        repository.page(UUID.randomUUID(), DocumentSyncItemStatus.FAILED,
                null, 51);

        assertTrue(sql.getValue().contains("status = ?"));
        assertTrue(!sql.getValue().contains("(seen_at, external_id) >"));
    }

    @Test
    void pageWithStatusAndCursorCombinesBothPredicates() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.query(sql.capture(), any(RowMapper.class),
                args.capture())).thenReturn(List.of());

        repository.page(UUID.randomUUID(), DocumentSyncItemStatus.APPLIED,
                cursor(), 11);

        assertTrue(sql.getValue().contains("status = ?"));
        assertTrue(sql.getValue().contains("(seen_at, external_id) > (?, ?)"));
        assertEquals(5, args.getValue().length);
        assertEquals("APPLIED", args.getValue()[1]);
        assertEquals(11, args.getValue()[4]);
    }

    @Test
    void mapRowReadsEnumsLongAndTimestamp() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("external_id")).thenReturn("ext-1");
        when(rs.getString("document_kind")).thenReturn("TEXT");
        when(rs.getString("source_revision")).thenReturn("etag:2");
        when(rs.getObject("document_id")).thenReturn(77L);
        when(rs.getString("status")).thenReturn("APPLIED");
        when(rs.getString("error_code")).thenReturn(null);
        when(rs.getString("error_message")).thenReturn(null);
        OffsetDateTime seenAt = OffsetDateTime.parse("2026-09-01T10:00:00Z");
        when(rs.getObject("seen_at")).thenReturn(seenAt);
        ArgumentCaptor<RowMapper<DocumentSyncRunItemReceiptRepository.ReceiptRow>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(anyString(), mapper.capture(),
                any(Object[].class))).thenReturn(List.of());

        repository.page(UUID.randomUUID(), null, null, 10);

        var row = mapper.getValue().mapRow(rs, 0);
        assertEquals("ext-1", row.externalId());
        assertEquals(DocumentSyncDocumentKind.TEXT, row.documentKind());
        assertEquals("etag:2", row.sourceRevision());
        assertEquals(77L, row.documentId());
        assertEquals(DocumentSyncItemStatus.APPLIED, row.status());
        assertNull(row.errorCode());
        assertEquals(seenAt, row.seenAt());
    }

    @Test
    void readOffsetDateTimeConvertsTimestampInstantAndDate() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("external_id")).thenReturn("ext-1");
        when(rs.getString("document_kind")).thenReturn("TEXT");
        when(rs.getString("status")).thenReturn("APPLIED");
        ArgumentCaptor<RowMapper<DocumentSyncRunItemReceiptRepository.ReceiptRow>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(anyString(), mapper.capture(),
                any(Object[].class))).thenReturn(List.of());
        repository.page(UUID.randomUUID(), null, null, 10);

        Instant instant = Instant.parse("2026-09-01T10:00:00Z");
        when(rs.getObject("seen_at")).thenReturn(Timestamp.from(instant));
        assertEquals(instant.atOffset(ZoneOffset.UTC),
                mapper.getValue().mapRow(rs, 0).seenAt());

        when(rs.getObject("seen_at")).thenReturn(instant);
        assertEquals(instant.atOffset(ZoneOffset.UTC),
                mapper.getValue().mapRow(rs, 0).seenAt());

        java.sql.Date date = java.sql.Date.valueOf("2026-09-01");
        when(rs.getObject("seen_at")).thenReturn(date);
        OffsetDateTime fromDirect = mapper.getValue().mapRow(rs, 0).seenAt();
        assertEquals(date.toLocalDate(), fromDirect.toLocalDate());

        when(rs.getObject("seen_at")).thenReturn("not-a-timestamp");
        assertThrows(IllegalStateException.class,
                () -> mapper.getValue().mapRow(rs, 0));

        when(rs.getObject("seen_at")).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> mapper.getValue().mapRow(rs, 0));
    }
}
