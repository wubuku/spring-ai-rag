package com.springairag.core.repository;

import com.springairag.core.entity.RagUserFeedback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RagUserFeedbackRepository unit tests (using Mock).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagUserFeedbackRepository Tests")
class RagUserFeedbackRepositoryTest {

    @Mock
    private RagUserFeedbackRepository repository;

    private RagUserFeedback createFeedback(String feedbackType, String sessionId, int rating) {
        RagUserFeedback f = new RagUserFeedback();
        f.setId(1L);
        f.setFeedbackType(feedbackType);
        f.setSessionId(sessionId);
        f.setQuery("What is Spring AI?");
        f.setRating(rating);
        f.setComment("Good answer");
        f.setRetrievedDocumentIds("1,2,3");
        f.setSelectedDocumentIds("1");
        f.setDwellTimeMs(5000L);
        f.setCreatedAt(ZonedDateTime.now());
        return f;
    }

    @Test
    @DisplayName("findByFeedbackTypeOrderByCreatedAtDesc returns feedback list")
    void findByFeedbackTypeOrderByCreatedAtDesc_found() {
        RagUserFeedback f = createFeedback("THUMBS_UP", "session-1", 5);
        Pageable pageable = PageRequest.of(0, 10);
        when(repository.findByFeedbackTypeOrderByCreatedAtDesc("THUMBS_UP", pageable))
                .thenReturn(List.of(f));

        List<RagUserFeedback> found = repository.findByFeedbackTypeOrderByCreatedAtDesc("THUMBS_UP", pageable);

        assertEquals(1, found.size());
        assertEquals("THUMBS_UP", found.get(0).getFeedbackType());
        verify(repository).findByFeedbackTypeOrderByCreatedAtDesc("THUMBS_UP", pageable);
    }

    @Test
    @DisplayName("findByFeedbackTypeOrderByCreatedAtDesc returns empty when none found")
    void findByFeedbackTypeOrderByCreatedAtDesc_empty() {
        Pageable pageable = PageRequest.of(0, 10);
        when(repository.findByFeedbackTypeOrderByCreatedAtDesc("THUMBS_DOWN", pageable))
                .thenReturn(List.of());

        List<RagUserFeedback> found = repository.findByFeedbackTypeOrderByCreatedAtDesc("THUMBS_DOWN", pageable);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("findByCreatedAtBetweenOrderByCreatedAtDesc returns feedbacks in range")
    void findByCreatedAtBetweenOrderByCreatedAtDesc_found() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(7);
        ZonedDateTime end = ZonedDateTime.now();
        RagUserFeedback f1 = createFeedback("THUMBS_UP", "s1", 5);
        RagUserFeedback f2 = createFeedback("THUMBS_DOWN", "s2", 1);
        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end))
                .thenReturn(List.of(f2, f1));

        List<RagUserFeedback> found = repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);

        assertEquals(2, found.size());
        verify(repository).findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);
    }

    @Test
    @DisplayName("findByCreatedAtBetweenOrderByCreatedAtDesc returns empty when none in range")
    void findByCreatedAtBetweenOrderByCreatedAtDesc_empty() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(30);
        ZonedDateTime end = ZonedDateTime.now().minusDays(28);
        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end))
                .thenReturn(List.of());

        List<RagUserFeedback> found = repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("findBySessionIdOrderByCreatedAtDesc returns feedbacks for session")
    void findBySessionIdOrderByCreatedAtDesc_found() {
        RagUserFeedback f1 = createFeedback("THUMBS_UP", "session-abc", 5);
        RagUserFeedback f2 = createFeedback("COMMENT", "session-abc", 4);
        when(repository.findBySessionIdOrderByCreatedAtDesc("session-abc"))
                .thenReturn(List.of(f2, f1));

        List<RagUserFeedback> found = repository.findBySessionIdOrderByCreatedAtDesc("session-abc");

        assertEquals(2, found.size());
        found.forEach(f -> assertEquals("session-abc", f.getSessionId()));
        verify(repository).findBySessionIdOrderByCreatedAtDesc("session-abc");
    }

    @Test
    @DisplayName("findBySessionIdOrderByCreatedAtDesc returns empty for unknown session")
    void findBySessionIdOrderByCreatedAtDesc_empty() {
        when(repository.findBySessionIdOrderByCreatedAtDesc("unknown-session"))
                .thenReturn(List.of());

        List<RagUserFeedback> found = repository.findBySessionIdOrderByCreatedAtDesc("unknown-session");

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("countByFeedbackType returns correct count")
    void countByFeedbackType() {
        when(repository.countByFeedbackType("THUMBS_UP")).thenReturn(42L);

        long count = repository.countByFeedbackType("THUMBS_UP");

        assertEquals(42L, count);
        verify(repository).countByFeedbackType("THUMBS_UP");
    }

    @Test
    @DisplayName("countByFeedbackType returns zero when none found")
    void countByFeedbackType_zero() {
        when(repository.countByFeedbackType("THUMBS_DOWN")).thenReturn(0L);

        long count = repository.countByFeedbackType("THUMBS_DOWN");

        assertEquals(0L, count);
    }

    @Test
    @DisplayName("countByFeedbackTypeAndCreatedAtBetween returns count in time range")
    void countByFeedbackTypeAndCreatedAtBetween() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(7);
        ZonedDateTime end = ZonedDateTime.now();
        when(repository.countByFeedbackTypeAndCreatedAtBetween("THUMBS_UP", start, end))
                .thenReturn(15L);

        long count = repository.countByFeedbackTypeAndCreatedAtBetween("THUMBS_UP", start, end);

        assertEquals(15L, count);
        verify(repository).countByFeedbackTypeAndCreatedAtBetween("THUMBS_UP", start, end);
    }

    @Test
    @DisplayName("countByFeedbackTypeAndCreatedAtBetween returns zero when none in range")
    void countByFeedbackTypeAndCreatedAtBetween_zero() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(30);
        ZonedDateTime end = ZonedDateTime.now().minusDays(28);
        when(repository.countByFeedbackTypeAndCreatedAtBetween("COMMENT", start, end))
                .thenReturn(0L);

        long count = repository.countByFeedbackTypeAndCreatedAtBetween("COMMENT", start, end);

        assertEquals(0L, count);
    }

    @Test
    @DisplayName("save persists and returns feedback")
    void save() {
        RagUserFeedback f = createFeedback("THUMBS_UP", "session-new", 5);
        when(repository.save(f)).thenReturn(f);

        RagUserFeedback saved = repository.save(f);

        assertNotNull(saved);
        assertEquals("THUMBS_UP", saved.getFeedbackType());
        verify(repository).save(f);
    }

    @Test
    @DisplayName("findAll returns all feedbacks")
    void findAll() {
        RagUserFeedback f1 = createFeedback("THUMBS_UP", "s1", 5);
        RagUserFeedback f2 = createFeedback("THUMBS_DOWN", "s2", 1);
        when(repository.findAll()).thenReturn(List.of(f1, f2));

        List<RagUserFeedback> all = repository.findAll();

        assertEquals(2, all.size());
    }
}
