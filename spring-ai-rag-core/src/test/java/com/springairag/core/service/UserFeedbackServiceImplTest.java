package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.entity.RagUserFeedback;
import com.springairag.core.repository.RagUserFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UserFeedbackServiceImpl Unit Tests
 */
@ExtendWith(MockitoExtension.class)
class UserFeedbackServiceImplTest {

    @Mock
    private RagUserFeedbackRepository repository;

    private UserFeedbackServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserFeedbackServiceImpl(repository, new ObjectMapper());
    }

    // ==================== submitFeedback ====================

    @Test
    @DisplayName("Submitting thumbs-up feedback saves all fields correctly")
    void submitFeedback_thumbsUp_savesCorrectly() {
        when(repository.save(any(RagUserFeedback.class))).thenAnswer(inv -> {
            RagUserFeedback f = inv.getArgument(0);
            f.setId(1L);
            return f;
        });

        RagUserFeedback result = service.submitFeedback(
                "session-1", "什么是 RAG？", "THUMBS_UP",
                null, null, List.of(1L, 2L), List.of(1L), 5000L
        );

        ArgumentCaptor<RagUserFeedback> captor = ArgumentCaptor.forClass(RagUserFeedback.class);
        verify(repository).save(captor.capture());
        RagUserFeedback saved = captor.getValue();

        assertEquals("session-1", saved.getSessionId());
        assertEquals("什么是 RAG？", saved.getQuery());
        assertEquals("THUMBS_UP", saved.getFeedbackType());
        assertNull(saved.getRating());
        assertEquals(5000L, saved.getDwellTimeMs());
        assertEquals("[1,2]", saved.getRetrievedDocumentIds());
        assertEquals("[1]", saved.getSelectedDocumentIds());
        assertEquals(1L, result.getId());
    }

    @Test
    @DisplayName("Submitting rating feedback saves rating field correctly")
    void submitFeedback_rating_savesRating() {
        when(repository.save(any(RagUserFeedback.class))).thenAnswer(inv -> {
            RagUserFeedback f = inv.getArgument(0);
            f.setId(2L);
            return f;
        });

        RagUserFeedback result = service.submitFeedback(
                "session-2", "Spring AI 怎么用？", "RATING",
                4, "回答不错", null, null, null
        );

        ArgumentCaptor<RagUserFeedback> captor = ArgumentCaptor.forClass(RagUserFeedback.class);
        verify(repository).save(captor.capture());
        RagUserFeedback saved = captor.getValue();

        assertEquals("RATING", saved.getFeedbackType());
        assertEquals(4, saved.getRating());
        assertEquals("回答不错", saved.getComment());
        assertNull(saved.getRetrievedDocumentIds());
    }

    @Test
    @DisplayName("Submitting thumbs-down feedback serializes empty doc list as null")
    void submitFeedback_thumbsDown_nullDocIds() {
        when(repository.save(any(RagUserFeedback.class))).thenAnswer(inv -> inv.getArgument(0));

        service.submitFeedback(
                "session-3", "错误的问题", "THUMBS_DOWN",
                null, "完全不相关", null, null, 1000L
        );

        ArgumentCaptor<RagUserFeedback> captor = ArgumentCaptor.forClass(RagUserFeedback.class);
        verify(repository).save(captor.capture());
        RagUserFeedback saved = captor.getValue();

        assertEquals("THUMBS_DOWN", saved.getFeedbackType());
        assertNull(saved.getRetrievedDocumentIds());
        assertEquals("完全不相关", saved.getComment());
    }

    // ==================== getStats ====================

    @Test
    @DisplayName("Feedback stats calculates satisfaction rate correctly")
    void getStats_calculatesSatisfactionRate() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(7);
        ZonedDateTime end = ZonedDateTime.now();

        RagUserFeedback up1 = createFeedback("THUMBS_UP", null, 3000L);
        RagUserFeedback up2 = createFeedback("THUMBS_UP", null, 5000L);
        RagUserFeedback down1 = createFeedback("THUMBS_DOWN", null, 1000L);

        when(repository.countByFeedbackTypeAndCreatedAtBetween("THUMBS_UP", start, end)).thenReturn(2L);
        when(repository.countByFeedbackTypeAndCreatedAtBetween("THUMBS_DOWN", start, end)).thenReturn(1L);
        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end))
                .thenReturn(List.of(up1, up2, down1));

        UserFeedbackService.FeedbackStats stats = service.getStats(start, end);

        assertEquals(3, stats.getTotalFeedbacks());
        assertEquals(2, stats.getThumbsUp());
        assertEquals(1, stats.getThumbsDown());
        assertEquals(2.0 / 3.0, stats.getSatisfactionRate(), 0.001);
        assertEquals(3000.0, stats.getAvgDwellTimeMs(), 0.001);
    }

    @Test
    @DisplayName("Feedback stats returns zeros when no data")
    void getStats_empty_returnsZeros() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(7);
        ZonedDateTime end = ZonedDateTime.now();

        when(repository.countByFeedbackTypeAndCreatedAtBetween(any(), any(), any())).thenReturn(0L);
        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());

        UserFeedbackService.FeedbackStats stats = service.getStats(start, end);

        assertEquals(0, stats.getTotalFeedbacks());
        assertEquals(0.0, stats.getSatisfactionRate());
        assertEquals(0.0, stats.getAvgDwellTimeMs());
    }

    @Test
    @DisplayName("Feedback stats calculates average rating when rating data is present")
    void getStats_withRatings_calculatesAvgRating() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(7);
        ZonedDateTime end = ZonedDateTime.now();

        RagUserFeedback r1 = createFeedback("RATING", 5, null);
        RagUserFeedback r2 = createFeedback("RATING", 3, null);

        when(repository.countByFeedbackTypeAndCreatedAtBetween("THUMBS_UP", start, end)).thenReturn(0L);
        when(repository.countByFeedbackTypeAndCreatedAtBetween("THUMBS_DOWN", start, end)).thenReturn(0L);
        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end))
                .thenReturn(List.of(r1, r2));

        UserFeedbackService.FeedbackStats stats = service.getStats(start, end);

        assertEquals(2, stats.getRatings());
        assertEquals(4.0, stats.getAvgRating(), 0.001);
    }

    // ==================== getHistory ====================

    @Test
    @DisplayName("Get history returns paginated data")
    void getHistory_returnsPagedData() {
        RagUserFeedback f1 = createFeedback("THUMBS_UP", null, null);
        Page<RagUserFeedback> page = new PageImpl<>(List.of(f1));
        when(repository.findAll(any(Pageable.class))).thenReturn(page);

        List<RagUserFeedback> result = service.getHistory(0, 10);

        assertEquals(1, result.size());
        assertEquals("THUMBS_UP", result.get(0).getFeedbackType());
    }

    // ==================== getByType ====================

    @Test
    @DisplayName("Get by type returns data of the specified type")
    void getByType_filtersCorrectly() {
        RagUserFeedback f1 = createFeedback("THUMBS_DOWN", null, null);
        when(repository.findByFeedbackTypeOrderByCreatedAtDesc(eq("THUMBS_DOWN"), any(Pageable.class)))
                .thenReturn(List.of(f1));

        List<RagUserFeedback> result = service.getByType("THUMBS_DOWN", 0, 10);

        assertEquals(1, result.size());
        assertEquals("THUMBS_DOWN", result.get(0).getFeedbackType());
    }

    // ==================== Helper ====================

    private RagUserFeedback createFeedback(String type, Integer rating, Long dwellTime) {
        RagUserFeedback f = new RagUserFeedback();
        f.setSessionId("test-session");
        f.setQuery("test query");
        f.setFeedbackType(type);
        f.setRating(rating);
        f.setDwellTimeMs(dwellTime);
        return f;
    }
}
