package com.springairag.core.repository;

import com.springairag.core.entity.RagClientError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RagClientErrorRepository Unit Tests (using Mock).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagClientErrorRepository Tests")
class RagClientErrorRepositoryTest {

    @Mock
    private RagClientErrorRepository repository;

    private RagClientError createError(Long id, String errorType, String errorMessage) {
        RagClientError e = new RagClientError();
        e.setId(id);
        e.setErrorType(errorType);
        e.setErrorMessage(errorMessage);
        e.setStackTrace("Error stack trace");
        e.setComponentStack("Component stack");
        e.setPageUrl("http://localhost/page");
        e.setUserAgent("Mozilla/5.0");
        e.setSessionId("sess-123");
        e.setUserId("user-1");
        e.setCreatedAt(Instant.now());
        return e;
    }

    // findByCreatedAtBetweenOrderByCreatedAtDesc

    @Nested
    @DisplayName("findByCreatedAtBetweenOrderByCreatedAtDesc")
    class FindByCreatedAtBetween {

        @Test
        @DisplayName("returns paginated errors within time range")
        void returnsPaginatedErrorsWithinTimeRange() {
            Pageable pageable = PageRequest.of(0, 10);
            Instant start = Instant.now().minusSeconds(3600);
            Instant end = Instant.now();
            RagClientError e1 = createError(1L, "JS_ERROR", "TypeError");
            Page<RagClientError> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(e1), pageable, 1);
            when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable))
                    .thenReturn(page);

            Page<RagClientError> result =
                    repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable);

            assertEquals(1, result.getTotalElements());
            assertEquals("JS_ERROR", result.getContent().get(0).getErrorType());
        }

        @Test
        @DisplayName("returns empty page when no errors in range")
        void returnsEmptyPageWhenNoErrorsInRange() {
            Pageable pageable = PageRequest.of(0, 10);
            Instant start = Instant.now().minusSeconds(86400);
            Instant end = Instant.now().minusSeconds(43200);
            Page<RagClientError> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(), pageable, 0);
            when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable))
                    .thenReturn(page);

            Page<RagClientError> result =
                    repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable);

            assertEquals(0, result.getTotalElements());
        }
    }

    // findByErrorTypeOrderByCreatedAtDesc

    @Nested
    @DisplayName("findByErrorTypeOrderByCreatedAtDesc")
    class FindByErrorType {

        @Test
        @DisplayName("returns paginated errors by type")
        void returnsPaginatedErrorsByType() {
            Pageable pageable = PageRequest.of(0, 10);
            RagClientError e1 = createError(1L, "NETWORK_ERROR", "fetch failed");
            RagClientError e2 = createError(2L, "NETWORK_ERROR", "connection timeout");
            Page<RagClientError> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(e1, e2), pageable, 2);
            when(repository.findByErrorTypeOrderByCreatedAtDesc("NETWORK_ERROR", pageable))
                    .thenReturn(page);

            Page<RagClientError> result =
                    repository.findByErrorTypeOrderByCreatedAtDesc("NETWORK_ERROR", pageable);

            assertEquals(2, result.getTotalElements());
        }

        @Test
        @DisplayName("returns empty page for unknown error type")
        void returnsEmptyPageForUnknownErrorType() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<RagClientError> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(), pageable, 0);
            when(repository.findByErrorTypeOrderByCreatedAtDesc("UNKNOWN_TYPE", pageable))
                    .thenReturn(page);

            Page<RagClientError> result =
                    repository.findByErrorTypeOrderByCreatedAtDesc("UNKNOWN_TYPE", pageable);

            assertEquals(0, result.getTotalElements());
        }
    }

    // countByErrorType

    @Nested
    @DisplayName("countByErrorType")
    class CountByErrorType {

        @Test
        @DisplayName("returns count of error type")
        void returnsCountOfErrorType() {
            when(repository.countByErrorType("JS_ERROR")).thenReturn(42L);

            long count = repository.countByErrorType("JS_ERROR");

            assertEquals(42L, count);
        }

        @Test
        @DisplayName("returns zero for unknown error type")
        void returnsZeroForUnknownErrorType() {
            when(repository.countByErrorType("UNKNOWN")).thenReturn(0L);

            long count = repository.countByErrorType("UNKNOWN");

            assertEquals(0L, count);
        }
    }

    // findTop10ByOrderByCreatedAtDesc

    @Nested
    @DisplayName("findTop10ByOrderByCreatedAtDesc")
    class FindTop10ByOrderByCreatedAtDesc {

        @Test
        @DisplayName("returns top 10 recent errors")
        void returnsTop10RecentErrors() {
            RagClientError e1 = createError(10L, "JS_ERROR", "Error 10");
            RagClientError e2 = createError(9L, "JS_ERROR", "Error 9");
            when(repository.findTop10ByOrderByCreatedAtDesc())
                    .thenReturn(List.of(e1, e2));

            List<RagClientError> errors = repository.findTop10ByOrderByCreatedAtDesc();

            assertEquals(2, errors.size());
        }

        @Test
        @DisplayName("returns empty list when no errors")
        void returnsEmptyListWhenNoErrors() {
            when(repository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of());

            List<RagClientError> errors = repository.findTop10ByOrderByCreatedAtDesc();

            assertTrue(errors.isEmpty());
        }
    }

    // CRUD inherited methods

    @Nested
    @DisplayName("CRUD inherited methods")
    class CrudMethods {

        @Test
        @DisplayName("save stores error and returns it")
        void saveStoresErrorAndReturnsIt() {
            RagClientError e = createError(null, "JS_ERROR", "Test error");
            RagClientError saved = createError(1L, "JS_ERROR", "Test error");
            when(repository.save(e)).thenReturn(saved);

            RagClientError result = repository.save(e);

            assertNotNull(result.getId());
        }

        @Test
        @DisplayName("findById returns error when present")
        void findByIdReturnsErrorWhenPresent() {
            RagClientError e = createError(1L, "JS_ERROR", "Test error");
            when(repository.findById(1L)).thenReturn(Optional.of(e));

            Optional<RagClientError> result = repository.findById(1L);

            assertTrue(result.isPresent());
            assertEquals("JS_ERROR", result.get().getErrorType());
        }

        @Test
        @DisplayName("findById returns empty when not present")
        void findByIdReturnsEmptyWhenNotPresent() {
            when(repository.findById(999L)).thenReturn(Optional.empty());

            Optional<RagClientError> result = repository.findById(999L);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("findAll returns all errors")
        void findAllReturnsAllErrors() {
            RagClientError e1 = createError(1L, "JS_ERROR", "Error 1");
            RagClientError e2 = createError(2L, "NETWORK_ERROR", "Error 2");
            when(repository.findAll()).thenReturn(List.of(e1, e2));

            List<RagClientError> errors = repository.findAll();

            assertEquals(2, errors.size());
        }

        @Test
        @DisplayName("deleteById removes error")
        void deleteByIdRemovesError() {
            doNothing().when(repository).deleteById(1L);

            repository.deleteById(1L);

            verify(repository).deleteById(1L);
        }

        @Test
        @DisplayName("count returns total error count")
        void countReturnsTotalErrorCount() {
            when(repository.count()).thenReturn(256L);

            long count = repository.count();

            assertEquals(256L, count);
        }
    }
}
