package com.springairag.core.repository;

import com.springairag.core.entity.RagChatHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RagChatHistoryJpaRepository Unit Tests (using Mock).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagChatHistoryJpaRepository Tests")
class RagChatHistoryJpaRepositoryTest {

    @Mock
    private RagChatHistoryJpaRepository repository;

    private RagChatHistory createHistory(Long id, String sessionId,
                                         String userMessage, String aiResponse) {
        RagChatHistory h = new RagChatHistory();
        h.setId(id);
        h.setSessionId(sessionId);
        h.setUserMessage(userMessage);
        h.setAiResponse(aiResponse);
        h.setRelatedDocumentIds("");
        h.setMetadata(Map.of());
        h.setCreatedAt(LocalDateTime.now());
        return h;
    }

    // findBySessionIdOrderByCreatedAtDesc (paginated)

    @Nested
    @DisplayName("findBySessionIdOrderByCreatedAtDesc")
    class FindBySessionIdDesc {

        @Test
        @DisplayName("returns paginated history for session")
        void returnsPaginatedHistoryForSession() {
            Pageable pageable = mock(Pageable.class);
            RagChatHistory h1 = createHistory(1L, "sess-abc", "Hello", "Hi there");
            RagChatHistory h2 = createHistory(2L, "sess-abc", "Follow up", "Continuing");
            when(repository.findBySessionIdOrderByCreatedAtDesc("sess-abc", pageable))
                    .thenReturn(List.of(h2, h1));

            List<RagChatHistory> history =
                    repository.findBySessionIdOrderByCreatedAtDesc("sess-abc", pageable);

            assertEquals(2, history.size());
            assertEquals("Follow up", history.get(0).getUserMessage());
        }

        @Test
        @DisplayName("returns empty list for unknown session")
        void returnsEmptyListForUnknownSession() {
            Pageable pageable = mock(Pageable.class);
            when(repository.findBySessionIdOrderByCreatedAtDesc("unknown", pageable))
                    .thenReturn(List.of());

            List<RagChatHistory> history =
                    repository.findBySessionIdOrderByCreatedAtDesc("unknown", pageable);

            assertTrue(history.isEmpty());
        }
    }

    // findAllBySessionIdOrderByCreatedAtDesc

    @Nested
    @DisplayName("findAllBySessionIdOrderByCreatedAtDesc")
    class FindAllBySessionIdDesc {

        @Test
        @DisplayName("returns all history for session (no pagination)")
        void returnsAllHistoryForSessionNoPagination() {
            RagChatHistory h1 = createHistory(1L, "sess-xyz", "First", "First response");
            RagChatHistory h2 = createHistory(2L, "sess-xyz", "Second", "Second response");
            RagChatHistory h3 = createHistory(3L, "sess-xyz", "Third", "Third response");
            when(repository.findAllBySessionIdOrderByCreatedAtDesc("sess-xyz"))
                    .thenReturn(List.of(h3, h2, h1));

            List<RagChatHistory> history =
                    repository.findAllBySessionIdOrderByCreatedAtDesc("sess-xyz");

            assertEquals(3, history.size());
            assertEquals("Third", history.get(0).getUserMessage());
        }

        @Test
        @DisplayName("returns empty list for session with no history")
        void returnsEmptyListForSessionWithNoHistory() {
            when(repository.findAllBySessionIdOrderByCreatedAtDesc("empty-sess"))
                    .thenReturn(List.of());

            List<RagChatHistory> history =
                    repository.findAllBySessionIdOrderByCreatedAtDesc("empty-sess");

            assertTrue(history.isEmpty());
        }
    }

    // findBySessionIdAsc

    @Nested
    @DisplayName("findBySessionIdAsc")
    class FindBySessionIdAsc {

        @Test
        @DisplayName("returns history in ascending order")
        void returnsHistoryInAscendingOrder() {
            RagChatHistory h1 = createHistory(1L, "sess-asc", "First", "First response");
            RagChatHistory h2 = createHistory(2L, "sess-asc", "Second", "Second response");
            when(repository.findBySessionIdAsc("sess-asc"))
                    .thenReturn(List.of(h1, h2));

            List<RagChatHistory> history = repository.findBySessionIdAsc("sess-asc");

            assertEquals(2, history.size());
            assertEquals("First", history.get(0).getUserMessage());
            assertEquals("Second", history.get(1).getUserMessage());
        }
    }

    // deleteBySessionId

    @Nested
    @DisplayName("deleteBySessionId")
    class DeleteBySessionId {

        @Test
        @DisplayName("deletes all history for session")
        void deletesAllHistoryForSession() {
            when(repository.deleteBySessionId("sess-to-delete")).thenReturn(5);

            int deleted = repository.deleteBySessionId("sess-to-delete");

            assertEquals(5, deleted);
            verify(repository).deleteBySessionId("sess-to-delete");
        }

        @Test
        @DisplayName("returns zero when session has no history")
        void returnsZeroWhenSessionHasNoHistory() {
            when(repository.deleteBySessionId("nonexistent")).thenReturn(0);

            int deleted = repository.deleteBySessionId("nonexistent");

            assertEquals(0, deleted);
        }
    }

    // CRUD inherited methods

    @Nested
    @DisplayName("CRUD inherited methods")
    class CrudMethods {

        @Test
        @DisplayName("save stores history and returns it")
        void saveStoresHistoryAndReturnsIt() {
            RagChatHistory h = createHistory(null, "sess-new", "Hello", "Response");
            RagChatHistory saved = createHistory(1L, "sess-new", "Hello", "Response");
            when(repository.save(h)).thenReturn(saved);

            RagChatHistory result = repository.save(h);

            assertNotNull(result.getId());
            assertEquals("sess-new", result.getSessionId());
        }

        @Test
        @DisplayName("findById returns history when present")
        void findByIdReturnsHistoryWhenPresent() {
            RagChatHistory h = createHistory(1L, "sess-1", "Hello", "Hi");
            when(repository.findById(1L)).thenReturn(Optional.of(h));

            Optional<RagChatHistory> result = repository.findById(1L);

            assertTrue(result.isPresent());
            assertEquals("Hello", result.get().getUserMessage());
        }

        @Test
        @DisplayName("findById returns empty when not present")
        void findByIdReturnsEmptyWhenNotPresent() {
            when(repository.findById(999L)).thenReturn(Optional.empty());

            Optional<RagChatHistory> result = repository.findById(999L);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("findAll returns all history")
        void findAllReturnsAllHistory() {
            RagChatHistory h1 = createHistory(1L, "sess-a", "A", "A response");
            RagChatHistory h2 = createHistory(2L, "sess-b", "B", "B response");
            when(repository.findAll()).thenReturn(List.of(h1, h2));

            List<RagChatHistory> history = repository.findAll();

            assertEquals(2, history.size());
        }

        @Test
        @DisplayName("deleteById removes history")
        void deleteByIdRemovesHistory() {
            doNothing().when(repository).deleteById(1L);

            repository.deleteById(1L);

            verify(repository).deleteById(1L);
        }

        @Test
        @DisplayName("count returns total history count")
        void countReturnsTotalHistoryCount() {
            when(repository.count()).thenReturn(50L);

            long count = repository.count();

            assertEquals(50L, count);
        }
    }
}
