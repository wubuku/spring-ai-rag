package com.springairag.core.repository;

import com.springairag.core.entity.RagAuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RagAuditLogRepository Unit Tests (using Mock).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagAuditLogRepository Tests")
class RagAuditLogRepositoryTest {

    @Mock
    private RagAuditLogRepository repository;

    private RagAuditLog createLog(Long id, String entityType, String entityId,
                                  String operation, String sessionId) {
        RagAuditLog log = new RagAuditLog();
        log.setId(id);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setOperation(operation);
        log.setSessionId(sessionId);
        log.setOperator("admin");
        log.setDescription("Test operation");
        log.setDetails("{}");
        log.setClientIp("127.0.0.1");
        log.setTraceId("trace-123");
        log.setCreatedAt(ZonedDateTime.now());
        return log;
    }

    // findByEntityTypeAndEntityIdOrderByCreatedAtDesc

    @Nested
    @DisplayName("findByEntityTypeAndEntityIdOrderByCreatedAtDesc")
    class FindByEntityAndId {

        @Test
        @DisplayName("returns paginated audit logs for entity")
        void returnsPaginatedAuditLogsForEntity() {
            Pageable pageable = PageRequest.of(0, 10);
            RagAuditLog l1 = createLog(1L, "COLLECTION", "col-1", "CREATE", "sess-1");
            RagAuditLog l2 = createLog(2L, "COLLECTION", "col-1", "UPDATE", "sess-2");
            Page<RagAuditLog> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(l1, l2), pageable, 2);
            when(repository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                    "COLLECTION", "col-1", pageable)).thenReturn(page);

            Page<RagAuditLog> result =
                    repository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                            "COLLECTION", "col-1", pageable);

            assertEquals(2, result.getTotalElements());
            assertEquals("CREATE", result.getContent().get(0).getOperation());
        }

        @Test
        @DisplayName("returns empty page for unknown entity")
        void returnsEmptyPageForUnknownEntity() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<RagAuditLog> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(), pageable, 0);
            when(repository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                    "UNKNOWN", "999", pageable)).thenReturn(page);

            Page<RagAuditLog> result =
                    repository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                            "UNKNOWN", "999", pageable);

            assertEquals(0, result.getTotalElements());
        }
    }

    // findBySessionIdOrderByCreatedAtDesc

    @Nested
    @DisplayName("findBySessionIdOrderByCreatedAtDesc")
    class FindBySessionIdDesc {

        @Test
        @DisplayName("returns paginated logs for session")
        void returnsPaginatedLogsForSession() {
            Pageable pageable = PageRequest.of(0, 20);
            RagAuditLog l1 = createLog(1L, "DOCUMENT", "doc-1", "CREATE", "sess-abc");
            Page<RagAuditLog> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(l1), pageable, 1);
            when(repository.findBySessionIdOrderByCreatedAtDesc("sess-abc", pageable))
                    .thenReturn(page);

            Page<RagAuditLog> result =
                    repository.findBySessionIdOrderByCreatedAtDesc("sess-abc", pageable);

            assertEquals(1, result.getTotalElements());
            assertEquals("sess-abc", result.getContent().get(0).getSessionId());
        }

        @Test
        @DisplayName("returns empty page for unknown session")
        void returnsEmptyPageForUnknownSession() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<RagAuditLog> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(), pageable, 0);
            when(repository.findBySessionIdOrderByCreatedAtDesc("unknown-sess", pageable))
                    .thenReturn(page);

            Page<RagAuditLog> result =
                    repository.findBySessionIdOrderByCreatedAtDesc("unknown-sess", pageable);

            assertEquals(0, result.getTotalElements());
        }
    }

    // CRUD inherited methods

    @Nested
    @DisplayName("CRUD inherited methods")
    class CrudMethods {

        @Test
        @DisplayName("save stores log and returns it")
        void saveStoresLogAndReturnsIt() {
            RagAuditLog log = createLog(null, "DOCUMENT", "doc-1", "CREATE", "sess-1");
            RagAuditLog saved = createLog(1L, "DOCUMENT", "doc-1", "CREATE", "sess-1");
            when(repository.save(log)).thenReturn(saved);

            RagAuditLog result = repository.save(log);

            assertNotNull(result.getId());
            assertEquals("DOCUMENT", result.getEntityType());
        }

        @Test
        @DisplayName("findById returns log when present")
        void findByIdReturnsLogWhenPresent() {
            RagAuditLog log = createLog(1L, "DOCUMENT", "doc-1", "CREATE", "sess-1");
            when(repository.findById(1L)).thenReturn(Optional.of(log));

            Optional<RagAuditLog> result = repository.findById(1L);

            assertTrue(result.isPresent());
            assertEquals("CREATE", result.get().getOperation());
        }

        @Test
        @DisplayName("findById returns empty when not present")
        void findByIdReturnsEmptyWhenNotPresent() {
            when(repository.findById(999L)).thenReturn(Optional.empty());

            Optional<RagAuditLog> result = repository.findById(999L);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("findAll returns all logs")
        void findAllReturnsAllLogs() {
            RagAuditLog l1 = createLog(1L, "DOCUMENT", "doc-1", "CREATE", "sess-1");
            RagAuditLog l2 = createLog(2L, "COLLECTION", "col-1", "DELETE", "sess-2");
            when(repository.findAll()).thenReturn(List.of(l1, l2));

            List<RagAuditLog> logs = repository.findAll();

            assertEquals(2, logs.size());
        }

        @Test
        @DisplayName("deleteById removes log")
        void deleteByIdRemovesLog() {
            doNothing().when(repository).deleteById(1L);

            repository.deleteById(1L);

            verify(repository).deleteById(1L);
        }

        @Test
        @DisplayName("count returns total log count")
        void countReturnsTotalLogCount() {
            when(repository.count()).thenReturn(100L);

            long count = repository.count();

            assertEquals(100L, count);
        }
    }
}
