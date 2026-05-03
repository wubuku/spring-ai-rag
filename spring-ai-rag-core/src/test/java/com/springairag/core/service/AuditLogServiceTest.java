package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.entity.RagAuditLog;
import com.springairag.core.repository.RagAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogService Tests")
class AuditLogServiceTest {

    @Mock
    private RagAuditLogRepository repository;

    @Mock
    private ObjectMapper objectMapper;

    @Captor
    private ArgumentCaptor<RagAuditLog> auditLogCaptor;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(repository, objectMapper);
        MDC.clear();
    }

    // --- Constants verification ---

    @Test
    @DisplayName("Operation constants have expected values")
    void operationConstants_haveExpectedValues() {
        assertEquals("CREATE", AuditLogService.OP_CREATE);
        assertEquals("UPDATE", AuditLogService.OP_UPDATE);
        assertEquals("DELETE", AuditLogService.OP_DELETE);
    }

    @Test
    @DisplayName("Entity type constants have expected values")
    void entityTypeConstants_haveExpectedValues() {
        assertEquals("Collection", AuditLogService.ENTITY_COLLECTION);
        assertEquals("Document", AuditLogService.ENTITY_DOCUMENT);
        assertEquals("ChatHistory", AuditLogService.ENTITY_CHAT_HISTORY);
        assertEquals("AbTest", AuditLogService.ENTITY_AB_TEST);
        assertEquals("Alert", AuditLogService.ENTITY_ALERT);
    }

    // --- logCreate ---

    @Test
    @DisplayName("logCreate saves audit entry with correct operation and entity fields")
    void logCreate_savesAuditEntry() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditLogService.logCreate("Collection", "col-123", "Created collection test");

        verify(repository).save(auditLogCaptor.capture());
        RagAuditLog captured = auditLogCaptor.getValue();
        assertEquals("CREATE", captured.getOperation());
        assertEquals("Collection", captured.getEntityType());
        assertEquals("col-123", captured.getEntityId());
        assertEquals("Created collection test", captured.getDescription());
        assertNull(captured.getDetails());
        assertNull(captured.getSessionId());
    }

    @Test
    @DisplayName("logCreate with details serializes details to JSON")
    void logCreate_withDetails_serializesToJson() throws Exception {
        Map<String, Object> details = Map.of("size", 1024, "type", "pdf");
        when(objectMapper.writeValueAsString(details)).thenReturn("{\"size\":1024,\"type\":\"pdf\"}");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditLogService.logCreate("Document", "doc-456", "Uploaded document", details);

        verify(repository).save(auditLogCaptor.capture());
        RagAuditLog captured = auditLogCaptor.getValue();
        assertEquals("{\"size\":1024,\"type\":\"pdf\"}", captured.getDetails());
    }

    // --- logUpdate ---

    @Test
    @DisplayName("logUpdate saves audit entry with UPDATE operation")
    void logUpdate_savesAuditEntry() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditLogService.logUpdate("Document", "doc-789", "Updated document title");

        verify(repository).save(auditLogCaptor.capture());
        RagAuditLog captured = auditLogCaptor.getValue();
        assertEquals("UPDATE", captured.getOperation());
        assertEquals("Document", captured.getEntityType());
        assertEquals("doc-789", captured.getEntityId());
    }

    @Test
    @DisplayName("logUpdate with details saves details JSON")
    void logUpdate_withDetails_savesDetailsJson() throws Exception {
        Map<String, Object> details = Map.of("oldName", "old", "newName", "new");
        when(objectMapper.writeValueAsString(details)).thenReturn("{\"oldName\":\"old\",\"newName\":\"new\"}");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditLogService.logUpdate("Collection", "col-111", "Renamed collection", details);

        verify(repository).save(auditLogCaptor.capture());
        assertEquals("{\"oldName\":\"old\",\"newName\":\"new\"}", auditLogCaptor.getValue().getDetails());
    }

    // --- logDelete ---

    @Test
    @DisplayName("logDelete saves audit entry with DELETE operation")
    void logDelete_savesAuditEntry() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditLogService.logDelete("Alert", "alert-999", "Deleted alert rule");

        verify(repository).save(auditLogCaptor.capture());
        RagAuditLog captured = auditLogCaptor.getValue();
        assertEquals("DELETE", captured.getOperation());
        assertEquals("Alert", captured.getEntityType());
        assertEquals("alert-999", captured.getEntityId());
    }

    @Test
    @DisplayName("logDelete with details saves details JSON")
    void logDelete_withDetails_savesDetailsJson() throws Exception {
        Map<String, Object> details = Map.of("reason", "user requested");
        when(objectMapper.writeValueAsString(details)).thenReturn("{\"reason\":\"user requested\"}");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditLogService.logDelete("Document", "doc-555", "Deleted document", details);

        verify(repository).save(auditLogCaptor.capture());
        assertEquals("{\"reason\":\"user requested\"}", auditLogCaptor.getValue().getDetails());
    }

    // --- MDC traceId ---

    @Test
    @DisplayName("logAudit reads traceId from MDC")
    void logAudit_readsTraceIdFromMDC() {
        MDC.put("traceId", "trace-abc-123");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditLogService.logCreate("Collection", "col-1", "test");

        verify(repository).save(auditLogCaptor.capture());
        assertEquals("trace-abc-123", auditLogCaptor.getValue().getTraceId());
    }

    @Test
    @DisplayName("logAudit handles missing MDC traceId gracefully")
    void logAudit_handlesMissingMdcTraceId() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditLogService.logCreate("Collection", "col-1", "test");

        verify(repository).save(auditLogCaptor.capture());
        assertNull(auditLogCaptor.getValue().getTraceId());
    }

    // --- Details JSON serialization ---

    @Test
    @DisplayName("toJson returns null for null details map")
    void toJson_nullMap_returnsNull() {
        // Calling via logCreate with null details - toJson is private but
        // we verify details is null in the captured entry
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditLogService.logCreate("Document", "doc-1", "no details");

        verify(repository).save(auditLogCaptor.capture());
        assertNull(auditLogCaptor.getValue().getDetails());
    }

    @Test
    @DisplayName("toJson returns null for empty details map")
    void toJson_emptyMap_returnsNull() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditLogService.logCreate("Document", "doc-1", "empty details", Collections.emptyMap());

        verify(repository).save(auditLogCaptor.capture());
        assertNull(auditLogCaptor.getValue().getDetails());
    }

    @Test
    @DisplayName("toJson returns null when ObjectMapper throws JsonProcessingException")
    void toJson_jsonException_returnsNull() throws Exception {
        Map<String, Object> details = Map.of("key", "value");
        when(objectMapper.writeValueAsString(details))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("test") {});
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditLogService.logCreate("Document", "doc-1", "test", details);

        verify(repository).save(auditLogCaptor.capture());
        assertNull(auditLogCaptor.getValue().getDetails());
    }

    // --- Resilience ---

    @Test
    @DisplayName("logCreate does not throw when repository throws exception")
    void logCreate_repositoryThrows_doesNotThrow() {
        when(repository.save(any())).thenThrow(new RuntimeException("DB error"));

        assertDoesNotThrow(() ->
                auditLogService.logCreate("Collection", "col-1", "test"));
    }

    @Test
    @DisplayName("logUpdate does not throw when repository throws exception")
    void logUpdate_repositoryThrows_doesNotThrow() {
        when(repository.save(any())).thenThrow(new RuntimeException("DB error"));

        assertDoesNotThrow(() ->
                auditLogService.logUpdate("Document", "doc-1", "test"));
    }

    @Test
    @DisplayName("logDelete does not throw when repository throws exception")
    void logDelete_repositoryThrows_doesNotThrow() {
        when(repository.save(any())).thenThrow(new RuntimeException("DB error"));

        assertDoesNotThrow(() ->
                auditLogService.logDelete("Alert", "alert-1", "test"));
    }

    // --- Query methods ---

    @Test
    @DisplayName("getEntityHistory delegates to repository")
    void getEntityHistory_delegatesToRepository() {
        auditLogService.getEntityHistory("Collection", "col-123", 0, 20);

        verify(repository).findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                eq("Collection"), eq("col-123"),
                argThat(pageable -> pageable.getPageNumber() == 0 && pageable.getPageSize() == 20));
    }

    @Test
    @DisplayName("getSessionHistory delegates to repository")
    void getSessionHistory_delegatesToRepository() {
        auditLogService.getSessionHistory("session-abc", 1, 10);

        verify(repository).findBySessionIdOrderByCreatedAtDesc(
                eq("session-abc"),
                argThat(pageable -> pageable.getPageNumber() == 1 && pageable.getPageSize() == 10));
    }

    @Test
    @DisplayName("getSessionHistory rejects null sessionId")
    void getSessionHistory_nullSessionId_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                auditLogService.getSessionHistory(null, 0, 10));
    }

    @Test
    @DisplayName("getSessionHistory rejects blank sessionId")
    void getSessionHistory_blankSessionId_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                auditLogService.getSessionHistory("   ", 0, 10));
    }

    @Test
    @DisplayName("getSessionHistory rejects empty sessionId")
    void getSessionHistory_emptySessionId_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                auditLogService.getSessionHistory("", 0, 10));
    }

    @Test
    @DisplayName("getRecentAuditLogs delegates to repository")
    void getRecentAuditLogs_delegatesToRepository() {
        auditLogService.getRecentAuditLogs(5);

        verify(repository).findTop20ByOrderByCreatedAtDesc();
    }

    @Test
    @DisplayName("getRecentAuditLogs returns list from repository")
    void getRecentAuditLogs_returnsList() {
        RagAuditLog log = new RagAuditLog();
        log.setId(1L);
        when(repository.findTop20ByOrderByCreatedAtDesc()).thenReturn(List.of(log));

        List<RagAuditLog> result = auditLogService.getRecentAuditLogs(5);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    // --- Cleanup ---

    @Test
    @DisplayName("cleanup delegates to repository deleteByCreatedAtBefore")
    void cleanup_delegatesToRepository() {
        ZonedDateTime cutoff = ZonedDateTime.now().minusDays(30);
        when(repository.deleteByCreatedAtBefore(cutoff)).thenReturn(42L);

        long deleted = auditLogService.cleanup(cutoff);

        assertEquals(42L, deleted);
        verify(repository).deleteByCreatedAtBefore(cutoff);
    }

    @Test
    @DisplayName("cleanup returns zero when nothing to delete")
    void cleanup_nothingToDelete_returnsZero() {
        ZonedDateTime cutoff = ZonedDateTime.now().minusDays(30);
        when(repository.deleteByCreatedAtBefore(cutoff)).thenReturn(0L);

        long deleted = auditLogService.cleanup(cutoff);

        assertEquals(0L, deleted);
    }
}
